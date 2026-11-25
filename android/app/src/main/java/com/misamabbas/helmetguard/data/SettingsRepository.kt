package com.misamabbas.helmetguard.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.misamabbas.helmetguard.vision.verify.RiderPolicy
import com.misamabbas.helmetguard.vision.verify.VerifierSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore("settings")

enum class SourceFilter { LIVE, VIDEO, ALL;
    val dbValue: String? get() = when (this) { LIVE -> "live"; VIDEO -> "video"; ALL -> null }
}

/**
 * "Minimum head confidence" for a new install: the same cut-off the website uses with the same
 * models (yolo11n-v3), so the app and the website flag the same riders.
 */
const val DEFAULT_HEAD_CONFIDENCE = 0.55f

data class AppSettings(
    val minNoHelmetObservations: Int = 5,
    val minNoHelmetConfidence: Float = DEFAULT_HEAD_CONFIDENCE,
    val minNoHelmetShare: Float = 0.70f,
    val stage1Confidence: Float = 0.35f,
    val riderPolicy: RiderPolicy = RiderPolicy.ALL_RIDERS,
    val videoSampleFps: Int = 10,
    val jpegQuality: Int = 88,
    val localCleanupDays: Int = 7,
    val wifiOnly: Boolean = false,
    val evidenceLock: Boolean = false,
) {
    fun verifier() = VerifierSettings(
        minNoHelmetObservations = minNoHelmetObservations,
        minNoHelmetConfidence = minNoHelmetConfidence,
        minNoHelmetShare = minNoHelmetShare,
        riderPolicy = riderPolicy,
    )
}

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private object K {
        val observations = intPreferencesKey("min_no_helmet_observations")
        val confidence = floatPreferencesKey("min_no_helmet_confidence")
        val share = floatPreferencesKey("min_no_helmet_share")
        val stage1 = floatPreferencesKey("stage1_confidence")
        val policy = stringPreferencesKey("rider_policy")
        val sample = intPreferencesKey("video_sample_fps")
        val jpeg = intPreferencesKey("jpeg_quality")
        val cleanup = intPreferencesKey("local_cleanup_days")
        val wifi = booleanPreferencesKey("wifi_only")
        val lock = booleanPreferencesKey("evidence_lock")
        val source = stringPreferencesKey("source_filter")
        val deviceId = stringPreferencesKey("device_id")
        val retriedFor = intPreferencesKey("retried_refused_for_version")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map(::read)

    private fun read(p: Preferences) = AppSettings(
        minNoHelmetObservations = p[K.observations] ?: 5,
        minNoHelmetConfidence = p[K.confidence] ?: DEFAULT_HEAD_CONFIDENCE,
        minNoHelmetShare = p[K.share] ?: 0.70f,
        stage1Confidence = p[K.stage1] ?: 0.35f,
        riderPolicy = runCatching { RiderPolicy.valueOf(p[K.policy] ?: "") }.getOrDefault(RiderPolicy.ALL_RIDERS),
        videoSampleFps = p[K.sample] ?: 10,
        jpegQuality = p[K.jpeg] ?: 88,
        localCleanupDays = p[K.cleanup] ?: 7,
        wifiOnly = p[K.wifi] ?: false,
        evidenceLock = p[K.lock] ?: false,
    )

    suspend fun current(): AppSettings = settings.first()

    suspend fun save(value: AppSettings) {
        context.dataStore.edit { p ->
            p[K.observations] = value.minNoHelmetObservations
            p[K.confidence] = value.minNoHelmetConfidence
            p[K.share] = value.minNoHelmetShare
            p[K.stage1] = value.stage1Confidence
            p[K.policy] = value.riderPolicy.name
            p[K.sample] = value.videoSampleFps
            p[K.jpeg] = value.jpegQuality
            p[K.cleanup] = value.localCleanupDays
            p[K.wifi] = value.wifiOnly
            p[K.lock] = value.evidenceLock
        }
    }

    /** The last Live / Prerecorded / All choice, shared by every screen. */
    val sourceFilter: Flow<SourceFilter> = context.dataStore.data.map { p ->
        runCatching { SourceFilter.valueOf(p[K.source] ?: "") }.getOrDefault(SourceFilter.ALL)
    }

    suspend fun setSourceFilter(value: SourceFilter) {
        context.dataStore.edit { it[K.source] = value.name }
    }

    /**
     * True the first time it is asked for a new app version, then false: alerts the server refused
     * get one more try after each update, which may have fixed what was refused.
     */
    suspend fun firstStartOf(versionCode: Int): Boolean {
        if (context.dataStore.data.first()[K.retriedFor] == versionCode) return false
        context.dataStore.edit { it[K.retriedFor] = versionCode }
        return true
    }

    /** Created once on first launch and never changed, so the server sees one device. */
    suspend fun deviceId(): String {
        context.dataStore.data.first()[K.deviceId]?.let { return it }
        val created = UUID.randomUUID().toString()
        context.dataStore.edit { it[K.deviceId] = created }
        return created
    }
}
