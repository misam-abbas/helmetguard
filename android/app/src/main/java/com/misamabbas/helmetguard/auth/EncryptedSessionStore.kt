package com.misamabbas.helmetguard.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.exception.NoSessionFoundException
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Keeps login tokens encrypted with a key that never leaves the Android Keystore. */
@Singleton
class EncryptedSessionStore @Inject constructor(@ApplicationContext context: Context) : SessionManager {
    private val file = File(context.noBackupFilesDir, "session.bin")
    private val json = Json { ignoreUnknownKeys = true }

    // The app and the background sync can both refresh the login at the same moment.
    private val writeLock = Mutex()

    override suspend fun saveSession(session: UserSession) = writeLock.withLock {
        withContext(Dispatchers.IO) {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
            val encrypted = cipher.doFinal(json.encodeToString(UserSession.serializer(), session).toByteArray())
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeBytes(byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + encrypted)
            check(temp.renameTo(file)) { "Could not store session" }
        }
    }

    override suspend fun loadSession(): UserSession = withContext(Dispatchers.IO) {
        if (!file.exists()) throw NoSessionFoundException()
        // An unreadable file (for example after the Keystore key is lost) means signing in again.
        runCatching {
            val bytes = file.readBytes()
            val ivSize = bytes[0].toInt()
            val iv = bytes.copyOfRange(1, 1 + ivSize)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv)) }
            json.decodeFromString(UserSession.serializer(), String(cipher.doFinal(bytes, 1 + ivSize, bytes.size - 1 - ivSize)))
        }.getOrElse { throw NoSessionFoundException() }
    }

    override suspend fun deleteSession() {
        writeLock.withLock { withContext(Dispatchers.IO) { file.delete() } }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "helmetguard_session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
