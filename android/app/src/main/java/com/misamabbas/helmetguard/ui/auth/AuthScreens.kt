package com.misamabbas.helmetguard.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.misamabbas.helmetguard.auth.AuthRepository
import com.misamabbas.helmetguard.auth.AuthState
import com.misamabbas.helmetguard.auth.SignUpResult
import com.misamabbas.helmetguard.data.StartupCleanup
import com.misamabbas.helmetguard.data.db.AppDatabase
import com.misamabbas.helmetguard.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jan.supabase.auth.exception.AuthErrorCode
import io.github.jan.supabase.auth.exception.AuthRestException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AuthMode { SIGN_IN, SIGN_UP, VERIFY, FORGOT, RESET }

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val cleanup: StartupCleanup,
    private val sync: SyncScheduler,
    private val db: AppDatabase,
) : ViewModel() {
    private val _state = MutableStateFlow<AuthState?>(null)
    val state: StateFlow<AuthState?> = _state
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val info = MutableStateFlow<String?>(null)
    val mode = MutableStateFlow(AuthMode.SIGN_IN)

    /** The address a sign-up or reset code was sent to. */
    val pendingEmail = MutableStateFlow("")
    val isConfigured: Boolean get() = auth.isConfigured

    fun startUp() {
        viewModelScope.launch {
            runCatching { cleanup.run() }
            refresh()
            sync.schedulePeriodic()
            sync.syncSoon()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            // A failure here must land on a screen, never crash or spin on the splash.
            _state.value = runCatching { auth.resolveState() }.getOrDefault(AuthState.SignedOut)
        }
    }

    fun show(next: AuthMode) {
        mode.value = next
        error.value = null
        info.value = null
    }

    fun signIn(email: String, password: String) = attempt(SIGN_IN_FAILED, onFailure = { failure ->
        // An account whose email isn't confirmed yet goes to the confirm screen, where a new email is one tap away.
        if (failure.authCode() == AuthErrorCode.EmailNotConfirmed) {
            pendingEmail.value = email.trim()
            mode.value = AuthMode.VERIFY
            "Confirm your email first: open the link we sent to ${email.trim()}, or send a new email below."
        } else {
            null
        }
    }) {
        auth.signIn(email, password).map { _state.value = it }
    }

    fun signUp(name: String, email: String, password: String) = attempt(SIGN_UP_FAILED) {
        auth.signUp(name, email, password).map { result ->
            when (result) {
                is SignUpResult.SignedIn -> _state.value = result.state
                SignUpResult.CodeSent -> {
                    pendingEmail.value = email.trim()
                    mode.value = AuthMode.VERIFY
                    info.value = "We emailed ${email.trim()}. Open the link in it to confirm your address. It can take a minute, and may land in spam."
                }
            }
        }
    }

    fun verifyCode(code: String) = attempt(CODE_FAILED) {
        auth.verifySignUpCode(pendingEmail.value, code).map { _state.value = it }
    }

    fun resendCode() = attempt("Could not send a new email. Wait a minute, then try again.") {
        auth.resendSignUpCode(pendingEmail.value).map {
            info.value = "A new email is on its way to ${pendingEmail.value}. It can take a minute, and may land in spam."
        }
    }

    fun sendReset(email: String) = attempt("Could not send the email. Check your internet connection, wait a minute, then try again.") {
        auth.sendPasswordReset(email).map {
            pendingEmail.value = email.trim()
            mode.value = AuthMode.RESET
            info.value = "If ${email.trim()} has an account, an email is on its way. It can take a minute, and may land in spam."
        }
    }

    fun resetPassword(code: String, newPassword: String) = attempt(CODE_FAILED) {
        auth.resetPassword(pendingEmail.value, code, newPassword).map { _state.value = it }
    }

    /** Runs [block]; on failure shows what [onFailure] says, a message for the server's reason, or [fallback]. */
    private fun attempt(fallback: String, onFailure: (Throwable) -> String? = { null }, block: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            busy.value = true
            error.value = null
            block().onFailure { failure ->
                info.value = null
                error.value = onFailure(failure) ?: messageFor(failure) ?: fallback
            }
            busy.value = false
        }
    }

    /** Sign-out is blocked while unsynced records exist, so nothing is lost by accident. */
    suspend fun signOut(): Boolean {
        if (db.violations().unsyncedCount() > 0) return false
        auth.signOut()
        _state.value = AuthState.SignedOut
        show(AuthMode.SIGN_IN)
        return true
    }

    private companion object {
        const val SIGN_IN_FAILED = "That did not work. Check your email, password and internet connection."
        const val SIGN_UP_FAILED = "Could not create the account. Check the details and your internet connection."
        const val CODE_FAILED = "That code is wrong or has expired. Check the email, or ask for a new one."
    }
}

private fun Throwable.authCode(): AuthErrorCode? = (this as? AuthRestException)?.errorCode

/** A plain-language message for the reasons Supabase gives, or null to use the screen's own message. */
private fun messageFor(failure: Throwable): String? = when (failure.authCode()) {
    AuthErrorCode.InvalidCredentials -> "That email and password do not match an account."
    AuthErrorCode.OverEmailSendRateLimit -> "Too many emails have been sent recently, so this one wasn't. Try again later."
    AuthErrorCode.OverRequestRateLimit -> "Too many attempts. Wait a minute, then try again."
    AuthErrorCode.EmailAddressNotAuthorized -> "HelmetGuard can't send email to that address yet. Ask the admin."
    AuthErrorCode.EmailAddressInvalid, AuthErrorCode.ValidationFailed -> "Enter a valid email address."
    AuthErrorCode.WeakPassword -> "Choose a stronger password: at least 8 characters, and not a common one."
    AuthErrorCode.UserAlreadyExists, AuthErrorCode.EmailExists -> "An account with that email already exists. Sign in, or use \"Forgot password?\"."
    AuthErrorCode.SignupDisabled -> "New accounts are turned off. Ask the HelmetGuard admin."
    AuthErrorCode.SamePassword -> "Choose a password different from your current one."
    AuthErrorCode.OtpExpired -> "That code has expired. Ask for a new email."
    else -> null
}

@Composable
fun SplashScreen(viewModel: AuthViewModel) {
    LaunchedEffect(Unit) { viewModel.startUp() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

private const val MIN_PASSWORD = 8

/** Supabase sends 6 digits by default. Projects can raise this to 10. */
private fun cleanCode(value: String) = value.filter(Char::isDigit).take(10)

@Composable
fun SignInScreen(viewModel: AuthViewModel = hiltViewModel()) {
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val info by viewModel.info.collectAsStateWithLifecycle()
    val pendingEmail by viewModel.pendingEmail.collectAsStateWithLifecycle()

    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    val ready = !busy && viewModel.isConfigured
    val canSubmit = ready && when (mode) {
        AuthMode.SIGN_IN -> email.isNotBlank() && password.isNotEmpty()
        AuthMode.SIGN_UP -> name.isNotBlank() && email.isNotBlank() && password.length >= MIN_PASSWORD
        AuthMode.VERIFY -> code.length >= 6
        AuthMode.FORGOT -> email.isNotBlank()
        AuthMode.RESET -> code.length >= 6 && password.length >= MIN_PASSWORD
    }
    val submit = {
        if (canSubmit) {
            when (mode) {
                AuthMode.SIGN_IN -> viewModel.signIn(email, password)
                AuthMode.SIGN_UP -> viewModel.signUp(name, email, password)
                AuthMode.VERIFY -> viewModel.verifyCode(code)
                AuthMode.FORGOT -> viewModel.sendReset(email)
                AuthMode.RESET -> viewModel.resetPassword(code, password)
            }
        }
    }
    val go = { next: AuthMode ->
        code = ""
        if (next == AuthMode.RESET || next == AuthMode.SIGN_UP) password = ""
        viewModel.show(next)
    }
    val fieldShape = RoundedCornerShape(12.dp)

    val (title, subtitle, action) = when (mode) {
        AuthMode.SIGN_IN -> Triple("Sign in to HelmetGuard", "You only need internet the first time. After that the app works offline.", "Sign in")
        AuthMode.SIGN_UP -> Triple("Create your account", "We will email you a link to confirm your address. Then an admin approves your account.", "Create account")
        AuthMode.VERIFY -> Triple(
            "Check your email",
            "We sent a link to $pendingEmail. Open it on this phone or any other device to confirm your address, then come back and sign in. If the email has a code instead, enter it here.",
            "Confirm with code",
        )
        AuthMode.FORGOT -> Triple("Reset your password", "Enter your email and we will send you a link to choose a new password.", "Send link")
        AuthMode.RESET -> Triple(
            "Check your email",
            "Open the link we sent to $pendingEmail to choose a new password on the HelmetGuard website, then sign in here with it. If the email has a code instead, enter it here with your new password.",
            "Save new password",
        )
    }

    @Composable
    fun field(value: String, onChange: (String) -> Unit, label: String, icon: ImageVector, options: KeyboardOptions, secret: Boolean = false, hint: String? = null) {
        OutlinedTextField(
            value,
            onChange,
            label = { Text(label) },
            leadingIcon = { Icon(icon, null) },
            trailingIcon = if (secret) {
                {
                    IconButton({ showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            if (showPassword) "Hide password" else "Show password",
                        )
                    }
                }
            } else {
                null
            },
            supportingText = hint?.let { { Text(it) } },
            singleLine = true,
            shape = fieldShape,
            visualTransformation = if (secret && !showPassword) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = options,
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    val next = KeyboardOptions(imeAction = ImeAction.Next)
    val emailKeys = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next)
    val codeKeys = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Next)
    val passwordKeys = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done)

    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        Box(
            Modifier.size(56.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Shield, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(30.dp))
        }
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!viewModel.isConfigured) {
            Text("This build has no Supabase project set. Add the values to local.properties and rebuild.", color = MaterialTheme.colorScheme.error)
        }

        when (mode) {
            AuthMode.SIGN_IN -> {
                field(email, { email = it }, "Email", Icons.Default.Email, emailKeys)
                field(password, { password = it }, "Password", Icons.Default.Lock, passwordKeys, secret = true)
            }
            AuthMode.SIGN_UP -> {
                field(name, { name = it }, "Full name", Icons.Default.Person, next.copy(capitalization = KeyboardCapitalization.Words))
                field(email, { email = it }, "Email", Icons.Default.Email, emailKeys)
                field(password, { password = it }, "Password", Icons.Default.Lock, passwordKeys, secret = true, hint = "At least $MIN_PASSWORD characters")
            }
            AuthMode.VERIFY -> field(code, { code = cleanCode(it) }, "Code from the email", Icons.Default.Key, codeKeys.copy(imeAction = ImeAction.Done))
            AuthMode.FORGOT -> field(email, { email = it }, "Email", Icons.Default.Email, emailKeys.copy(imeAction = ImeAction.Done))
            AuthMode.RESET -> {
                field(code, { code = cleanCode(it) }, "Code from the email", Icons.Default.Key, codeKeys)
                field(password, { password = it }, "New password", Icons.Default.Lock, passwordKeys, secret = true, hint = "At least $MIN_PASSWORD characters")
            }
        }

        info?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Button(onClick = { submit() }, enabled = canSubmit, shape = fieldShape, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(22.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
            } else {
                Text(action, style = MaterialTheme.typography.titleMedium)
            }
        }

        when (mode) {
            AuthMode.SIGN_IN -> {
                TextButton({ go(AuthMode.FORGOT) }, Modifier.fillMaxWidth()) { Text("Forgot password?") }
                TextButton({ go(AuthMode.SIGN_UP) }, Modifier.fillMaxWidth()) { Text("Create an account") }
            }
            AuthMode.SIGN_UP -> TextButton({ go(AuthMode.SIGN_IN) }, Modifier.fillMaxWidth()) { Text("I already have an account") }
            AuthMode.VERIFY -> {
                OutlinedButton(
                    {
                        if (email.isBlank()) email = pendingEmail
                        go(AuthMode.SIGN_IN)
                    },
                    Modifier.fillMaxWidth().height(48.dp),
                    shape = fieldShape,
                ) { Text("I've confirmed my email: sign in") }
                TextButton({ viewModel.resendCode() }, Modifier.fillMaxWidth(), enabled = ready) { Text("Send a new email") }
            }
            AuthMode.FORGOT -> TextButton({ go(AuthMode.SIGN_IN) }, Modifier.fillMaxWidth()) { Text("Back to sign in") }
            AuthMode.RESET -> {
                OutlinedButton({ go(AuthMode.SIGN_IN) }, Modifier.fillMaxWidth().height(48.dp), shape = fieldShape) { Text("I've chosen a new password: sign in") }
                TextButton({ viewModel.sendReset(pendingEmail) }, Modifier.fillMaxWidth(), enabled = ready) { Text("Send a new email") }
            }
        }
    }
}

@Composable
fun AccountBlockedScreen(title: String, body: String, viewModel: AuthViewModel = hiltViewModel()) {
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton({ viewModel.refresh() }, Modifier.fillMaxWidth()) { Text("Check again") }
    }
}
