package com.misamabbas.helmetguard.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Upsert suspend fun upsert(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun byId(id: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observe(id: String): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE (:source IS NULL OR source = :source) ORDER BY startedAt DESC LIMIT 200")
    fun observeAll(source: String?): Flow<List<SessionEntity>>

    @Query("UPDATE sessions SET status = 'interrupted', dirty = 1 WHERE status = 'active'")
    suspend fun markActiveInterrupted(): Int

    @Query("SELECT * FROM sessions WHERE dirty = 1")
    suspend fun dirty(): List<SessionEntity>

    @Query("UPDATE sessions SET dirty = 0 WHERE id = :id AND status != 'active'")
    suspend fun markClean(id: String)

    @Query(
        """SELECT COALESCE(SUM(motorcyclesTotal), 0) AS motorcycles, COALESCE(SUM(ridersHelmet), 0) AS ridersHelmet,
           COALESCE(SUM(ridersNoHelmet), 0) AS ridersNoHelmet, COALESCE(SUM(ridersTotal), 0) AS ridersTotal
           FROM sessions WHERE startedAt >= :since AND (:source IS NULL OR source = :source)""",
    )
    fun observeTotals(since: Long, source: String?): Flow<SourceTotals>
}

@Dao
interface ViolationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(violation: ViolationEntity)

    @Update suspend fun update(violation: ViolationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvidence(files: List<EvidenceFileEntity>)

    @Transaction
    suspend fun finalise(violation: ViolationEntity, files: List<EvidenceFileEntity>) {
        update(violation)
        insertEvidence(files)
    }

    @Query("DELETE FROM violations WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM violations WHERE id = :id")
    suspend fun byId(id: String): ViolationEntity?

    @Query("SELECT * FROM violations WHERE id = :id")
    fun observe(id: String): Flow<ViolationEntity?>

    @Query("SELECT * FROM evidence_files WHERE violationId = :id ORDER BY kind, seq")
    fun observeEvidence(id: String): Flow<List<EvidenceFileEntity>>

    @Query("SELECT * FROM evidence_files WHERE violationId = :id ORDER BY kind, seq")
    suspend fun evidence(id: String): List<EvidenceFileEntity>

    @Query("UPDATE evidence_files SET uploaded = 1 WHERE id = :id")
    suspend fun markUploaded(id: String)

    @Query(
        """SELECT v.id, v.source, v.detectedAt, v.videoTimestampMs, v.plateTextOcr, v.plateOcrStatus, v.syncState,
           s.videoFileName AS videoFileName,
           (SELECT e.relativePath FROM evidence_files e WHERE e.violationId = v.id AND e.kind = 'thumb' AND e.removedLocally = 0 LIMIT 1) AS thumbPath
           FROM violations v JOIN sessions s ON s.id = v.sessionId
           WHERE (:source IS NULL OR v.source = :source)
             AND (:sessionId IS NULL OR v.sessionId = :sessionId)
             AND (:plate = '' OR v.plateTextOcr LIKE '%' || :plate || '%')
           ORDER BY v.detectedAt DESC LIMIT 300""",
    )
    fun observeList(source: String?, sessionId: String?, plate: String): Flow<List<ViolationListRow>>

    @Query("SELECT COUNT(*) FROM violations WHERE createdAt >= :since AND (:source IS NULL OR source = :source)")
    fun observeCountSince(since: Long, source: String?): Flow<Int>

    @Query("SELECT COUNT(*) FROM violations WHERE syncState != 'SYNCED'")
    fun observeUnsyncedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM violations WHERE syncState != 'SYNCED'")
    suspend fun unsyncedCount(): Int

    @Query("SELECT * FROM violations WHERE syncState IN ('QUEUED', 'UPLOADING_FILES', 'FILES_DONE', 'RETRYING') ORDER BY createdAt LIMIT :limit")
    suspend fun pendingSync(limit: Int): List<ViolationEntity>

    @Query("SELECT * FROM violations WHERE syncState != 'SYNCED' ORDER BY createdAt DESC LIMIT 200")
    fun observeQueue(): Flow<List<ViolationEntity>>

    @Query("UPDATE violations SET syncState = :state, syncError = :error WHERE id = :id")
    suspend fun setSyncState(id: String, state: String, error: String? = null)

    @Query("UPDATE violations SET syncState = 'SYNCED', syncError = NULL, serverSyncedAt = :at WHERE id = :id")
    suspend fun markSynced(id: String, at: String)

    @Query("UPDATE violations SET syncState = 'QUEUED', syncError = NULL WHERE syncState = 'NEEDS_ATTENTION'")
    suspend fun retryFailed()

    @Query("SELECT id FROM violations")
    suspend fun allIds(): List<String>

    @Query("SELECT id FROM violations WHERE syncState = 'LOCAL_ONLY' AND createdAt < :before")
    suspend fun staleProvisional(before: Long): List<String>

    /** Full-size images that have been safely in the cloud for long enough to remove. */
    @Query(
        """SELECT e.* FROM evidence_files e JOIN violations v ON v.id = e.violationId
           WHERE v.syncState = 'SYNCED' AND v.createdAt < :before AND e.kind != 'thumb' AND e.removedLocally = 0 AND e.uploaded = 1""",
    )
    suspend fun removableEvidence(before: Long): List<EvidenceFileEntity>

    @Query("UPDATE evidence_files SET removedLocally = 1 WHERE id = :id")
    suspend fun markRemoved(id: String)
}

@Dao
interface SyncLogDao {
    @Insert suspend fun insert(entry: SyncLogEntity)

    @Query("SELECT * FROM sync_log ORDER BY timestamp DESC LIMIT 50")
    fun observeRecent(): Flow<List<SyncLogEntity>>

    @Query("DELETE FROM sync_log WHERE timestamp < :before")
    suspend fun trim(before: Long)
}

@Database(
    entities = [SessionEntity::class, ViolationEntity::class, EvidenceFileEntity::class, SyncLogEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun violations(): ViolationDao
    abstract fun syncLog(): SyncLogDao
}
