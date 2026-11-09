package com.misamabbas.helmetguard.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object SyncState {
    const val LOCAL_ONLY = "LOCAL_ONLY"
    const val QUEUED = "QUEUED"
    const val UPLOADING_FILES = "UPLOADING_FILES"
    const val FILES_DONE = "FILES_DONE"
    const val SYNCED = "SYNCED"
    const val RETRYING = "RETRYING"
    const val NEEDS_ATTENTION = "NEEDS_ATTENTION"
}

object SessionStatus {
    const val ACTIVE = "active"
    const val COMPLETED = "completed"
    const val INTERRUPTED = "interrupted"
    const val CANCELLED = "cancelled"
}

@Entity(tableName = "sessions", indices = [Index("startedAt"), Index("source")])
data class SessionEntity(
    @PrimaryKey val id: String,
    val source: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val status: String = SessionStatus.ACTIVE,
    val locationLabel: String? = null,
    val videoUri: String? = null,
    val videoFileName: String? = null,
    val videoDurationMs: Long? = null,
    val sampleFps: Int? = null,
    val lastProcessedMs: Long = 0,
    val framesProcessed: Int = 0,
    val avgFps: Float? = null,
    val motorcyclesTotal: Int = 0,
    val ridersTotal: Int = 0,
    val ridersHelmet: Int = 0,
    val ridersNoHelmet: Int = 0,
    val ridersUnknown: Int = 0,
    val violationsConfirmed: Int = 0,
    val settingsJson: String = "{}",
    val modelVersion: String = "",
    /** The newest change the server has not seen yet. */
    val dirty: Boolean = true,
)

@Entity(
    tableName = "violations",
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId"), Index("detectedAt"), Index("syncState"), Index("source")],
)
data class ViolationEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val trackId: Int,
    val source: String,
    val detectedAt: Long,
    val videoTimestampMs: Long? = null,
    val riderCount: Int = 0,
    val noHelmetCount: Int = 0,
    val ridersJson: String = "[]",
    val helmetPolicy: String = "all_riders",
    val helmetConfidence: Float? = null,
    val motorcycleConfidence: Float? = null,
    val framesObserved: Int = 0,
    val noHelmetFrames: Int = 0,
    val plateTextOcr: String? = null,
    val plateOcrConfidence: Float? = null,
    val plateOcrStatus: String = "no_plate_detected",
    val plateCandidatesJson: String = "[]",
    val ocrEngine: String? = null,
    val modelVersion: String = "",
    val appVersion: String = "",
    val createdAt: Long,
    val syncState: String = SyncState.LOCAL_ONLY,
    val syncError: String? = null,
    val serverSyncedAt: String? = null,
)

@Entity(
    tableName = "evidence_files",
    foreignKeys = [ForeignKey(entity = ViolationEntity::class, parentColumns = ["id"], childColumns = ["violationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("violationId"), Index(value = ["violationId", "kind", "seq"], unique = true)],
)
data class EvidenceFileEntity(
    @PrimaryKey val id: String,
    val violationId: String,
    val kind: String,
    val seq: Int = 0,
    val relativePath: String,
    val width: Int,
    val height: Int,
    val bytes: Long,
    val sha256: String,
    val bboxJson: String? = null,
    val frameTimestampMs: Long? = null,
    val qualityScore: Float? = null,
    val isEnhanced: Boolean = false,
    /** The extra still and backup frame stay on the phone, protecting the 1 GB quota. */
    val uploadable: Boolean = true,
    val uploaded: Boolean = false,
    val removedLocally: Boolean = false,
)

@Entity(tableName = "sync_log", indices = [Index("timestamp")])
data class SyncLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val violationId: String? = null,
    val message: String,
)

data class SourceTotals(
    val motorcycles: Int,
    val ridersHelmet: Int,
    val ridersNoHelmet: Int,
    val ridersTotal: Int,
)

data class ViolationListRow(
    val id: String,
    val source: String,
    val detectedAt: Long,
    val videoTimestampMs: Long?,
    val plateTextOcr: String?,
    val plateOcrStatus: String,
    val syncState: String,
    val videoFileName: String?,
    val thumbPath: String?,
)
