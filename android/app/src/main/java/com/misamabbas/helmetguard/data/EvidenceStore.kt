package com.misamabbas.helmetguard.data

import android.content.Context
import com.misamabbas.helmetguard.data.db.AppDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class WrittenFile(val name: String, val relativePath: String, val bytes: Long, val sha256: String)

/** App-private evidence on disk. Other apps and the gallery cannot see these files. */
@Singleton
class EvidenceStore @Inject constructor(@ApplicationContext context: Context, private val db: AppDatabase) {
    private val root = File(context.filesDir, "evidence").apply { mkdirs() }

    fun file(relativePath: String) = File(root, relativePath)

    /**
     * Writes to a temporary folder, flushes each file to disk, then renames the folder
     * into place. A crash at any moment leaves either nothing or a complete folder.
     */
    suspend fun writeAtomically(violationId: String, files: Map<String, ByteArray>): List<WrittenFile> = withContext(Dispatchers.IO) {
        val temp = File(root, "$TEMP_PREFIX$violationId").apply {
            deleteRecursively()
            mkdirs()
        }
        val written = files.map { (name, bytes) ->
            FileOutputStream(File(temp, name)).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            WrittenFile(name, "$violationId/$name", bytes.size.toLong(), sha256(bytes))
        }
        val target = File(root, violationId)
        target.deleteRecursively()
        check(temp.renameTo(target)) { "Could not move evidence into place" }
        written
    }

    /** Removes half-written folders and evidence with no matching database row. */
    suspend fun removeOrphans() = withContext(Dispatchers.IO) {
        val known = db.violations().allIds().toHashSet()
        root.listFiles()?.forEach { folder ->
            if (folder.name.startsWith(TEMP_PREFIX) || folder.name !in known) folder.deleteRecursively()
        }
    }

    suspend fun delete(violationId: String) = withContext(Dispatchers.IO) {
        File(root, violationId).deleteRecursively()
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TEMP_PREFIX = ".tmp-"
    }
}
