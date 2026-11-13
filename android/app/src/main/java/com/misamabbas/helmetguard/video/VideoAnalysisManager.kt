package com.misamabbas.helmetguard.video

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.misamabbas.helmetguard.camera.DeviceConditions
import com.misamabbas.helmetguard.camera.HeatLevel
import com.misamabbas.helmetguard.data.DetectionLog
import com.misamabbas.helmetguard.data.DetectionLogWriter
import com.misamabbas.helmetguard.data.SettingsRepository
import com.misamabbas.helmetguard.data.db.AppDatabase
import com.misamabbas.helmetguard.data.db.SessionEntity
import com.misamabbas.helmetguard.data.db.SessionStatus
import com.misamabbas.helmetguard.ml.ModelManifest
import com.misamabbas.helmetguard.ml.ModelStatus
import com.misamabbas.helmetguard.ml.ModelStore
import com.misamabbas.helmetguard.pipeline.FrameOutput
import com.misamabbas.helmetguard.pipeline.FrameProcessor
import com.misamabbas.helmetguard.pipeline.PipelineConfig
import com.misamabbas.helmetguard.pipeline.SessionContext
import com.misamabbas.helmetguard.pipeline.ViolationRecorder
import com.misamabbas.helmetguard.vision.verify.RiderPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

data class AnalysisUi(
    val sessionId: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val frames: Int = 0,
    val alerts: Int = 0,
    val remainingMs: Long = 0,
    val paused: Boolean = false,
    val heatPaused: Boolean = false,
    val finished: Boolean = false,
    val error: String? = null,
    val preview: Bitmap? = null,
    val previewOutput: FrameOutput? = null,
)

/**
 * Owns a running video analysis, so it outlives the screen. The foreground service
 * drives [run]; the screen only reads [ui] and sends pause and cancel.
 */
@Singleton
class VideoAnalysisManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val models: ModelStore,
    private val settings: SettingsRepository,
    private val recorder: ViolationRecorder,
    private val conditions: DeviceConditions,
) {
    private val _ui = MutableStateFlow(AnalysisUi())
    val ui = _ui.asStateFlow()

    @Volatile private var paused = false
    @Volatile private var cancelled = false
    @Volatile private var interrupted = false

    @Volatile var activeSessionId: String? = null
        private set

    val isRunning: Boolean get() = activeSessionId != null

    /** Claims the single analysis slot. False if another video is already running. */
    @Synchronized
    fun begin(sessionId: String): Boolean {
        if (activeSessionId != null) return activeSessionId == sessionId
        activeSessionId = sessionId
        paused = false
        cancelled = false
        interrupted = false
        _ui.value = AnalysisUi(sessionId = sessionId)
        return true
    }

    fun togglePause() {
        paused = !paused
        _ui.update { it.copy(paused = paused) }
    }

    /** Cancel ends the session but keeps every violation already found. */
    fun cancel() {
        cancelled = true
        paused = false
    }

    /** The system ended our time. The session stays resumable from Sessions history. */
    fun interrupt() {
        interrupted = true
        cancelled = true
        paused = false
    }

    suspend fun run(sessionId: String) {
        try {
            val session = db.sessions().byId(sessionId) ?: return
            val manifest = (models.status() as? ModelStatus.Ready)?.manifest
            if (manifest == null) {
                _ui.update { it.copy(error = "Detection models are not installed yet.") }
                return
            }
            analyse(session, manifest)
        } finally {
            synchronized(this) { activeSessionId = null }
        }
    }

    private suspend fun analyse(session: SessionEntity, manifest: ModelManifest) {
        val current = settings.current()
        val policy = if (current.riderPolicy == RiderPolicy.ALL_RIDERS) "all_riders" else "driver_only"
        val sessionContext = SessionContext(session.id, "video", manifest.version, policy, current.jpegQuality, isVideo = true)
        val startedWall = session.startedAt
        val processor = FrameProcessor(
            models.create(manifest.stage1),
            manifest.stage2?.let(models::create),
            PipelineConfig(current.verifier(), current.stage1Confidence, jpegQuality = current.jpegQuality),
            recorder.listenerFor(sessionContext) { ts -> startedWall + ts },
        )
        val log = DetectionLogWriter(DetectionLog.fileFor(context.filesDir, session.id))

        val duration = session.videoDurationMs ?: 0L
        // Resume a little early; duplicate protection absorbs the overlap.
        val startMs = max(0L, session.lastProcessedMs - RESUME_OVERLAP_MS)
        val everyMs = 1000L / max(1, session.sampleFps ?: current.videoSampleFps)
        recorder.updateSession(session.id) { it.copy(status = SessionStatus.ACTIVE) }
        _ui.update { it.copy(durationMs = duration, positionMs = startMs) }

        var frames = session.framesProcessed
        var lastCheckpoint = startMs
        var lastPosition = startMs
        var lastPreviewMs = 0L
        val wallStart = System.currentTimeMillis()
        var thisRun = 0

        try {
            VideoFrameSource(context, Uri.parse(session.videoUri)).decode(startMs, everyMs, { !cancelled }) { frame, ptsMs ->
                waitWhilePaused()
                if (cancelled) return@decode
                val output = processor.process(frame, ptsMs)
                log.append(ptsMs, output)
                frames++
                thisRun++
                lastPosition = ptsMs

                if (ptsMs - lastCheckpoint >= CHECKPOINT_MS) {
                    lastCheckpoint = ptsMs
                    log.flush()
                    val total = frames
                    recorder.updateSession(session.id) { it.copy(lastProcessedMs = ptsMs, framesProcessed = total) }
                }

                val now = System.currentTimeMillis()
                val perFrame = (now - wallStart) / max(1, thisRun)
                val remaining = (duration - ptsMs) / everyMs * perFrame
                val alerts = processor.totals.violations
                // A small copy twice a second is plenty, and keeps the frame loop fast.
                val refreshPreview = now - lastPreviewMs >= PREVIEW_EVERY_MS
                if (refreshPreview) lastPreviewMs = now
                val preview = if (refreshPreview) Bitmap.createScaledBitmap(frame, PREVIEW_WIDTH, PREVIEW_WIDTH * frame.height / frame.width, true) else null
                _ui.update {
                    it.copy(
                        positionMs = ptsMs,
                        frames = frames,
                        alerts = alerts,
                        remainingMs = max(0, remaining),
                        preview = preview ?: it.preview,
                        previewOutput = if (preview != null) output else it.previewOutput,
                    )
                }
            }
            processor.finish(lastPosition + FLUSH_MS)
            log.flush()
            val total = frames
            val status = when {
                interrupted -> SessionStatus.INTERRUPTED
                cancelled -> SessionStatus.CANCELLED
                else -> SessionStatus.COMPLETED
            }
            val elapsed = max(1L, System.currentTimeMillis() - wallStart)
            recorder.updateSession(session.id) {
                it.copy(status = status, endedAt = System.currentTimeMillis(), framesProcessed = total, lastProcessedMs = lastPosition, avgFps = thisRun * 1000f / elapsed)
            }
            _ui.update { it.copy(finished = true) }
        } catch (e: UnsupportedVideoException) {
            fail(session.id, e.message ?: "This video is not supported.")
        } catch (e: Exception) {
            Log.e("HelmetGuard", "Video analysis failed", e)
            fail(session.id, "Analysis stopped. Resume it from Sessions history.")
        } finally {
            log.close()
            processor.close()
        }
    }

    private fun fail(id: String, message: String) {
        recorder.updateSession(id) { it.copy(status = SessionStatus.INTERRUPTED) }
        _ui.update { it.copy(error = message) }
    }

    /** Also holds here while the phone is too hot, and carries on when it cools. */
    private fun waitWhilePaused() {
        while ((paused || conditions.heat() == HeatLevel.SEVERE) && !cancelled) {
            _ui.update { it.copy(heatPaused = !paused) }
            Thread.sleep(POLL_MS)
        }
        _ui.update { it.copy(heatPaused = false) }
    }

    private companion object {
        const val RESUME_OVERLAP_MS = 3_000L
        const val CHECKPOINT_MS = 2_000L
        const val FLUSH_MS = 2_000L
        const val POLL_MS = 300L
        const val PREVIEW_EVERY_MS = 500L
        const val PREVIEW_WIDTH = 640
    }
}
