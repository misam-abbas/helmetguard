package com.misamabbas.helmetguard.data

import com.misamabbas.helmetguard.data.db.AppDatabase
import com.misamabbas.helmetguard.data.db.SyncState
import com.misamabbas.helmetguard.video.VideoAnalysisManager
import javax.inject.Inject
import javax.inject.Singleton

/** Repairs whatever a crash or force-close left behind. Safe to run on every start. */
@Singleton
class StartupCleanup @Inject constructor(
    private val db: AppDatabase,
    private val store: EvidenceStore,
    private val settings: SettingsRepository,
    private val analysis: VideoAnalysisManager,
) {
    suspend fun run() {
        // A video can still be running in the background when the app is reopened.
        if (!analysis.isRunning) {
            db.sessions().markActiveInterrupted()
            store.removeOrphans()
        }

        // A provisional record whose app died before finalisation is still a real alert,
        // so it is queued without photos rather than thrown away.
        val cutoff = System.currentTimeMillis() - PROVISIONAL_GRACE_MS
        db.violations().staleProvisional(cutoff).forEach { id ->
            db.violations().setSyncState(id, SyncState.QUEUED, "App closed before photos were saved")
        }

        val removeBefore = System.currentTimeMillis() - settings.current().localCleanupDays * DAY_MS
        db.violations().removableEvidence(removeBefore).forEach { file ->
            store.file(file.relativePath).delete()
            db.violations().markRemoved(file.id)
        }
        db.syncLog().trim(System.currentTimeMillis() - 14 * DAY_MS)
    }

    private companion object {
        const val PROVISIONAL_GRACE_MS = 60_000L
        const val DAY_MS = 86_400_000L
    }
}
