package com.misamabbas.helmetguard.sync

import android.content.Context
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.misamabbas.helmetguard.BuildConfig
import com.misamabbas.helmetguard.data.EvidenceStore
import com.misamabbas.helmetguard.data.SettingsRepository
import com.misamabbas.helmetguard.data.db.AppDatabase
import com.misamabbas.helmetguard.data.db.SessionEntity
import com.misamabbas.helmetguard.data.db.SyncLogEntity
import com.misamabbas.helmetguard.data.db.SyncState
import com.misamabbas.helmetguard.data.db.ViolationEntity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.exceptions.BadRequestRestException
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext context: Context,
    private val settings: SettingsRepository,
) {
    private val workManager = WorkManager.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val isRunning: Flow<Boolean> = workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_ONCE)
        .map { list -> list.any { it.state == WorkInfo.State.RUNNING } }

    private suspend fun constraints() = Constraints.Builder()
        .setRequiredNetworkType(if (settings.current().wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .build()

    /** After each new violation and at app start. The unique name stops parallel runs. */
    fun syncSoon() {
        scope.launch {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            workManager.enqueueUniqueWork(UNIQUE_ONCE, ExistingWorkPolicy.KEEP, request)
        }
    }

    /** A safety net every 15 minutes, the shortest Android allows. */
    fun schedulePeriodic() {
        scope.launch {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints()).build()
            workManager.enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }

    private companion object {
        const val UNIQUE_ONCE = "helmetguard-sync"
        const val UNIQUE_PERIODIC = "helmetguard-sync-periodic"
    }
}

private class RejectedByServer(message: String) : Exception(message)

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val client: SupabaseClient?,
    private val db: AppDatabase,
    private val store: EvidenceStore,
    private val settings: SettingsRepository,
) : CoroutineWorker(context, params) {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun doWork(): Result {
        val supabase = client ?: return Result.success()
        supabase.auth.awaitInitialization()
        if (supabase.auth.currentSessionOrNull() == null) return Result.success()

        return try {
            // A fresh token every run: nothing reaches the server on stale credentials.
            supabase.auth.refreshCurrentSession()
            val userId = supabase.auth.currentUserOrNull()?.id ?: return Result.success()
            val deviceId = settings.deviceId()

            upsertDevice(supabase, userId, deviceId)
            db.sessions().dirty().forEach { upsertSession(supabase, it, userId, deviceId) }

            var needsRetry = false
            do {
                val batch = db.violations().pendingSync(BATCH)
                for (violation in batch) {
                    if (!syncOne(supabase, violation, userId, deviceId)) needsRetry = true
                }
            } while (batch.size == BATCH && !needsRetry)

            if (needsRetry) Result.retry() else Result.success()
        } catch (e: IOException) {
            log(null, "Network problem: ${e.message}")
            Result.retry()
        } catch (e: HttpRequestException) {
            log(null, "Network problem: ${e.message}")
            Result.retry()
        } catch (e: RestException) {
            log(null, "Sign-in required: ${e.error}")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Records stay safe on the phone, so anything unexpected just tries again later.
            log(null, "Sync stopped: ${e.message}")
            Result.retry()
        }
    }

    private suspend fun syncOne(supabase: SupabaseClient, violation: ViolationEntity, userId: String, deviceId: String): Boolean {
        val id = violation.id
        return try {
            db.violations().setSyncState(id, SyncState.UPLOADING_FILES)
            val files = db.violations().evidence(id).filter { it.uploadable }
            for (file in files) {
                if (file.uploaded) continue
                val bytes = store.file(file.relativePath).takeIf { it.exists() }?.readBytes() ?: continue
                val name = "${file.kind}_${file.seq}"
                if (bytes.size > MAX_FILE_BYTES) {
                    log(id, "Photo $name left out: larger than the 5 MB limit")
                    continue
                }
                // A photo the server refuses is left out and the alert goes on without it: one bad
                // picture (a 400 from storage on a head crop, on 2026-11-25) used to hold back the
                // whole alert for good. Network problems still stop here and are tried again later.
                try {
                    // Always the same path, so a retry overwrites instead of duplicating.
                    supabase.storage.from(BUCKET).upload(pathOf(userId, id, file.kind, file.seq), bytes) { upsert = true }
                    db.violations().markUploaded(file.id)
                } catch (e: RestException) {
                    log(id, "Photo $name left out, refused by the server: ${e.message}")
                }
            }
            db.violations().setSyncState(id, SyncState.FILES_DONE)

            val response = supabase.postgrest.rpc("sync_violation", buildJsonObject {
                put("payload", payload(violation, deviceId, userId))
            }).decodeAs<JsonElement>()
            val syncedAt = response.jsonObject["synced_at"]?.jsonPrimitive?.content ?: Instant.now().toString()
            db.violations().markSynced(id, syncedAt)
            true
        } catch (e: BadRequestRestException) {
            attention(id, "Rejected by the server: ${e.error}")
            true
        } catch (e: RestException) {
            attention(id, "Rejected by the server: ${e.error}")
            true
        } catch (e: IllegalStateException) {
            attention(id, e.message ?: "Could not prepare this record")
            true
        } catch (e: IOException) {
            retrying(id, e.message)
            false
        } catch (e: HttpRequestException) {
            retrying(id, e.message)
            false
        }
    }

    private suspend fun retrying(id: String, reason: String?) {
        db.violations().setSyncState(id, SyncState.RETRYING, reason ?: "Temporary network problem")
    }

    private suspend fun attention(id: String, reason: String) {
        db.violations().setSyncState(id, SyncState.NEEDS_ATTENTION, reason)
        log(id, reason)
    }

    private suspend fun log(id: String?, message: String) {
        db.syncLog().insert(SyncLogEntity(timestamp = System.currentTimeMillis(), violationId = id, message = message))
    }

    private fun pathOf(userId: String, violationId: String, kind: String, seq: Int) = "$userId/$violationId/${kind}_$seq.jpg"

    private suspend fun upsertDevice(supabase: SupabaseClient, userId: String, deviceId: String) {
        supabase.from("devices").upsert(buildJsonObject {
            put("id", deviceId)
            put("owner_id", userId)
            put("label", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("android_version", Build.VERSION.RELEASE)
            put("app_version", BuildConfig.VERSION_NAME)
            put("last_sync_at", Instant.now().toString())
        })
    }

    private suspend fun upsertSession(supabase: SupabaseClient, s: SessionEntity, userId: String, deviceId: String) {
        supabase.from("detection_sessions").upsert(buildJsonObject {
            put("id", s.id)
            put("device_id", deviceId)
            put("operator_id", userId)
            put("source", s.source)
            put("started_at", Instant.ofEpochMilli(s.startedAt).toString())
            s.endedAt?.let { put("ended_at", Instant.ofEpochMilli(it).toString()) }
            put("status", s.status)
            put("location_label", s.locationLabel)
            put("video_file_name", s.videoFileName)
            s.videoDurationMs?.let { put("video_duration_ms", it) }
            put("frames_processed", s.framesProcessed)
            s.avgFps?.let { put("avg_fps", it) }
            put("motorcycles_total", s.motorcyclesTotal)
            put("riders_total", s.ridersTotal)
            put("riders_helmet", s.ridersHelmet)
            put("riders_no_helmet", s.ridersNoHelmet)
            put("riders_unknown", s.ridersUnknown)
            put("violations_confirmed", s.violationsConfirmed)
            put("settings", json.parseToJsonElement(s.settingsJson))
            put("model_version", s.modelVersion)
        })
        db.sessions().markClean(s.id)
    }

    private suspend fun payload(v: ViolationEntity, deviceId: String, userId: String) = buildJsonObject {
        put("id", v.id)
        put("session_id", v.sessionId)
        put("device_id", deviceId)
        put("track_id", v.trackId)
        put("source", v.source)
        put("detected_at", Instant.ofEpochMilli(v.detectedAt).toString())
        v.videoTimestampMs?.let { put("video_timestamp_ms", it) }
        put("rider_count", v.riderCount)
        put("no_helmet_count", v.noHelmetCount)
        put("riders", json.parseToJsonElement(v.ridersJson))
        put("helmet_policy", v.helmetPolicy)
        v.helmetConfidence?.let { put("helmet_confidence", it) }
        v.motorcycleConfidence?.let { put("motorcycle_confidence", it) }
        put("frames_observed", v.framesObserved)
        put("no_helmet_frames", v.noHelmetFrames)
        put("plate_text_ocr", v.plateTextOcr)
        v.plateOcrConfidence?.let { put("plate_ocr_confidence", it) }
        put("plate_ocr_status", v.plateOcrStatus)
        put("plate_candidates", json.parseToJsonElement(v.plateCandidatesJson))
        put("ocr_engine", v.ocrEngine)
        put("model_version", v.modelVersion)
        put("app_version", v.appVersion)
        put("client_created_at", Instant.ofEpochMilli(v.createdAt).toString())
        put("evidence", buildJsonArray {
            db.violations().evidence(v.id).filter { it.uploadable && it.uploaded }.forEach { file ->
                add(buildJsonObject {
                    put("id", file.id)
                    put("kind", file.kind)
                    put("seq", file.seq)
                    put("storage_path", pathOf(userId, v.id, file.kind, file.seq))
                    put("width", file.width)
                    put("height", file.height)
                    put("bytes", file.bytes)
                    put("sha256", file.sha256)
                    file.bboxJson?.let { put("bbox", json.parseToJsonElement(it)) }
                    file.frameTimestampMs?.let { put("frame_timestamp_ms", it) }
                    put("is_enhanced", file.isEnhanced)
                })
            }
        })
    }

    private companion object {
        const val BUCKET = "evidence"
        const val BATCH = 20
        const val MAX_FILE_BYTES = 5 * 1024 * 1024
    }
}
