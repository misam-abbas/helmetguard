package com.misamabbas.helmetguard.ui.violations

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import javax.inject.Inject
import javax.inject.Singleton

/** Remembers a successful unlock briefly, so reviewing several records asks only once. */
@Singleton
class EvidenceLockState @Inject constructor() {
    @Volatile private var unlockedAtMs = 0L

    val isUnlocked: Boolean get() = System.currentTimeMillis() - unlockedAtMs < UNLOCK_MS

    fun markUnlocked() {
        unlockedAtMs = System.currentTimeMillis()
    }

    private companion object {
        const val UNLOCK_MS = 5 * 60 * 1000L
    }
}

private const val ALLOWED = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

private fun authenticate(activity: Activity, onSuccess: () -> Unit, onFailure: (String) -> Unit) {
    if (activity.getSystemService(BiometricManager::class.java).canAuthenticate(ALLOWED) != BiometricManager.BIOMETRIC_SUCCESS) {
        onFailure("This phone has no screen lock. Set one in Android Settings, or turn this protection off in HelmetGuard Settings.")
        return
    }
    BiometricPrompt.Builder(activity)
        .setTitle("Unlock evidence")
        .setSubtitle("Confirm it's you to view this record")
        .setAllowedAuthenticators(ALLOWED)
        .build()
        .authenticate(
            CancellationSignal(),
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFailure(errString.toString())
            },
        )
}

@Composable
fun EvidenceLockGate(onUnlocked: () -> Unit) {
    val activity = LocalActivity.current
    var message by remember { mutableStateOf<String?>(null) }
    val ask = {
        if (activity != null) authenticate(activity, onUnlocked) { message = it }
    }
    LaunchedEffect(Unit) { ask() }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
        Text("This evidence is locked", style = MaterialTheme.typography.titleMedium)
        Text("Use your fingerprint or PIN to open it.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button({ ask() }, Modifier.fillMaxWidth()) { Text("Unlock") }
    }
}
