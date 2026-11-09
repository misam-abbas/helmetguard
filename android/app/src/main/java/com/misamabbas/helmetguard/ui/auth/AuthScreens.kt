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
import com.misamabbas.helmetguard.data.StartupCleanup
import com.misamabbas.helmetguard.data.db.AppDatabase
import com.misamabbas.helmetguard.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

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

    fun signIn(email: String, password: String) = attempt { auth.signIn(email, password) }

    fun signUp(name: String, email: String, password: String) = attempt { auth.signUp(name, email, password) }

    private fun attempt(block: suspend () -> Result<AuthState>) {
        viewModelScope.launch {
            busy.value = true
            error.value = null
            block().onSuccess { _state.value = it }.onFailure {
                error.value = "That did not work. Check your email, password and internet connection."
            }
            busy.value = false
        }
    }

    /** Sign-out is blocked while unsynced records exist, so nothing is lost by accident. */
    suspend fun signOut(): Boolean {
        if (db.violations().unsyncedCount() > 0) return false
        auth.signOut()
        _state.value = AuthState.SignedOut
        return true
    }
}

@Composable
fun SplashScreen(viewModel: AuthViewModel) {
    LaunchedEffect(Unit) { viewModel.startUp() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun SignInScreen(viewModel: AuthViewModel = hiltViewModel()) {
    var signUp by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    val canSubmit = !busy && viewModel.isConfigured && email.isNotBlank() && password.length >= if (signUp) 8 else 1
    val submit = {
        if (canSubmit) {
            if (signUp) viewModel.signUp(name, email, password) else viewModel.signIn(email, password)
        }
    }
    val fieldShape = RoundedCornerShape(12.dp)

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
        Text(
            if (signUp) "Create your account" else "Sign in to HelmetGuard",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            if (signUp) "An admin must approve your account before you can use it." else "You only need internet the first time. After that the app works offline.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!viewModel.isConfigured) {
            Text("This build has no Supabase project set. Add the values to local.properties and rebuild.", color = MaterialTheme.colorScheme.error)
        }
        if (signUp) {
            OutlinedTextField(
                name,
                { name = it },
                label = { Text("Full name") },
                leadingIcon = { Icon(Icons.Default.Person, null) },
                singleLine = true,
                shape = fieldShape,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            email,
            { email = it },
            label = { Text("Email") },
            leadingIcon = { Icon(Icons.Default.Email, null) },
            singleLine = true,
            shape = fieldShape,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            password,
            { password = it },
            label = { Text("Password") },
            leadingIcon = { Icon(Icons.Default.Lock, null) },
            trailingIcon = {
                IconButton({ showPassword = !showPassword }) {
                    Icon(
                        if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        if (showPassword) "Hide password" else "Show password",
                    )
                }
            },
            supportingText = if (signUp) ({ Text("At least 8 characters") }) else null,
            singleLine = true,
            shape = fieldShape,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Button(onClick = { submit() }, enabled = canSubmit, shape = fieldShape, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(22.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
            } else {
                Text(if (signUp) "Create account" else "Sign in", style = MaterialTheme.typography.titleMedium)
            }
        }
        TextButton({ signUp = !signUp }, Modifier.fillMaxWidth()) { Text(if (signUp) "I already have an account" else "Create an account") }
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
