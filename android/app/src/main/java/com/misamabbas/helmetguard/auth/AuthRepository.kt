package com.misamabbas.helmetguard.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.misamabbas.helmetguard.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private val Context.authStore by preferencesDataStore("auth_cache")

@Serializable
data class ProfileRow(val role: String, val status: String)

@Suppress("PropertyName")
@Serializable
data class GraceRow(val offline_grace_days: Int)

sealed interface AuthState {
    data object SignedOut : AuthState
    data object AwaitingApproval : AuthState
    data object Disabled : AuthState
    data object MustGoOnline : AuthState
    data class Ready(val role: String, val developerMode: Boolean = false) : AuthState
}

@Singleton
class AuthRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: SupabaseClient?,
) {
    private object K {
        val role = stringPreferencesKey("role")
        val status = stringPreferencesKey("status")
        val lastOnline = longPreferencesKey("last_online")
        val grace = longPreferencesKey("grace_days")
    }

    val isConfigured: Boolean get() = client != null

    /** Only debug builds without a backend may skip sign-in, for trying the app locally. */
    val canUseDeveloperMode: Boolean get() = BuildConfig.DEBUG && client == null

    /** Decides what the user may do, online or off, without bypassing anything on the server. */
    suspend fun resolveState(): AuthState {
        val supabase = client ?: return if (canUseDeveloperMode) AuthState.Ready("operator", developerMode = true) else AuthState.SignedOut
        supabase.auth.awaitInitialization()
        val session = supabase.auth.currentSessionOrNull() ?: return AuthState.SignedOut

        runCatching { refreshProfile(session.user?.id ?: return AuthState.SignedOut) }

        val cached = context.authStore.data.first()
        val status = cached[K.status] ?: return AuthState.SignedOut
        val lastOnline = cached[K.lastOnline] ?: 0L
        val graceDays = cached[K.grace] ?: DEFAULT_GRACE_DAYS

        return when {
            status == "pending" -> AuthState.AwaitingApproval
            status == "disabled" -> AuthState.Disabled
            System.currentTimeMillis() - lastOnline > graceDays * DAY_MS -> AuthState.MustGoOnline
            else -> AuthState.Ready(cached[K.role] ?: "operator")
        }
    }

    private suspend fun refreshProfile(userId: String) {
        val supabase = client!!
        val profile = supabase.from("profiles").select(Columns.list("role", "status")) { filter { eq("id", userId) } }.decodeSingle<ProfileRow>()
        // Only active accounts can read this, and the admin's value should reach the phone.
        val graceDays = runCatching {
            supabase.from("app_settings").select(Columns.list("offline_grace_days")).decodeSingle<GraceRow>().offline_grace_days.toLong()
        }.getOrNull()
        context.authStore.edit {
            it[K.role] = profile.role
            it[K.status] = profile.status
            it[K.lastOnline] = System.currentTimeMillis()
            if (graceDays != null) it[K.grace] = graceDays
        }
    }

    suspend fun signIn(email: String, password: String): Result<AuthState> = runCatching {
        requireNotNull(client).auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
        resolveState()
    }

    suspend fun signUp(name: String, email: String, password: String): Result<AuthState> = runCatching {
        requireNotNull(client).auth.signUpWith(Email) {
            this.email = email.trim()
            this.password = password
            data = buildJsonObject { put("full_name", name.trim()) }
        }
        if (client.auth.currentSessionOrNull() == null) client.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
        resolveState()
    }

    suspend fun signOut() {
        runCatching { client?.auth?.signOut() }
        context.authStore.edit { it.clear() }
    }

    private companion object {
        const val DEFAULT_GRACE_DAYS = 7L
        const val DAY_MS = 86_400_000L
    }
}
