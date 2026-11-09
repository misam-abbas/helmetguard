package com.misamabbas.helmetguard.ui.video

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.misamabbas.helmetguard.camera.DeviceConditions
import com.misamabbas.helmetguard.camera.HeatLevel
import com.misamabbas.helmetguard.data.SettingsRepository
import com.misamabbas.helmetguard.data.db.AppDatabase
import com.misamabbas.helmetguard.data.db.SessionEntity
import com.misamabbas.helmetguard.data.db.SessionStatus
import com.misamabbas.helmetguard.ml.ModelStatus
import com.misamabbas.helmetguard.ml.ModelStore
import com.misamabbas.helmetguard.pipeline.FrameProcessor
import com.misamabbas.helmetguard.pipeline.PipelineConfig
import com.misamabbas.helmetguard.pipeline.SessionContext
import com.misamabbas.helmetguard.pipeline.ViolationRecorder
import com.misamabbas.helmetguard.ui.components.formatClip
import com.misamabbas.helmetguard.video.UnsupportedVideoException
import com.misamabbas.helmetguard.video.VideoFrameSource
import com.misamabbas.helmetguard.vision.verify.RiderPolicy
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import kotlin.math.max

data class PickedVideo(val uri: Uri, val name: String, val durationMs: Long, val thumbnail: Bitmap?)

data class VideoSetupUi(val video: PickedVideo? = null, val sampleFps: Int = 10, val modelsReady: Boolean = true, val error: String? = null)

@HiltViewModel
class VideoSetupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    models: ModelStore,
    private val recorder: ViolationRecorder,
) : ViewModel() {
    private val manifest = (models.status() as? ModelStatus.Ready)?.manifest
    private val _ui = MutableStateFlow(VideoSetupUi(modelsReady = manifest != null))
    val ui = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val fps = settings.current().videoSampleFps
            _ui.update { it.copy(sampleFps = fps) }
        }
    }

    fun setSampleFps(value: Int) = _ui.update { it.copy(sampleFps = value) }

    fun pick(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                // Kept so an interrupted job can resume later without asking again.
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                withContext(Dispatchers.IO) { read(uri) }
            }.onSuccess { video -> _ui.update { it.copy(video = video, error = null) } }
                .onFailure { _ui.update { it.copy(error = "That video could not be opened.") } }
        }
    }

    private fun read(uri: Uri): PickedVideo {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "video"
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            return PickedVideo(uri, name, duration, retriever.getFrameAtTime(duration * 500))
        } finally {
            retriever.release()
        }
    }

    suspend fun createSession(): String? {
        val state = _ui.value
        val video = state.video ?: return null
        val current = settings.current()
        val id = UUID.randomUUID().toString()
        recorder.saveSession(
            SessionEntity(
                id = id,
                source = "video",
                startedAt = System.currentTimeMillis(),
                videoUri = video.uri.toString(),
                videoFileName = video.name,
                videoDurationMs = video.durationMs,
                sampleFps = state.sampleFps,
                modelVersion = manifest?.version ?: "",
                settingsJson = """{"sample_fps":${state.sampleFps},"min_no_helmet_observations":${current.minNoHelmetObservations}}""",
            ),
        )
        return id
    }
}

@Composable
fun VideoSetupScreen(onStart: (String) -> Unit, viewModel: VideoSetupViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(viewModel::pick) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Analyse a video", style = MaterialTheme.typography.headlineSmall)
        if (!ui.modelsReady) {
            Text("Detection models are not installed yet. Add them after training.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Button({ picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }, Modifier.fillMaxWidth()) { Text("Pick video") }
        ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        ui.video?.let { video ->
            video.thumbnail?.let {
                Image(it.asImageBitmap(), "Video thumbnail", Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentScale = ContentScale.Crop)
            }
            Text(video.name, style = MaterialTheme.typography.titleSmall)
            Text("Length ${formatClip(video.durationMs)}", style = MaterialTheme.typography.bodyMedium)
            Text("Analyse up to ${ui.sampleFps} frames per second of video", style = MaterialTheme.typography.bodyMedium)
            Slider(ui.sampleFps.toFloat(), { viewModel.setSampleFps(it.toInt()) }, valueRange = 2f..30f, steps = 27)
            Text("Estimated time: about ${formatClip(video.durationMs / 1000 * ui.sampleFps * EST_MS_PER_FRAME)}", style = MaterialTheme.typography.bodySmall)
            Button({
                scope.launch { viewModel.createSession()?.let(onStart) }
            }, Modifier.fillMaxWidth(), enabled = ui.modelsReady) { Text("Analyse") }
        }
    }
}

private const val EST_MS_PER_FRAME = 90L

data class AnalysisUi(
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
)

@HiltViewModel
class VideoAnalysisViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val models: ModelStore,
    private val settings: SettingsRepository,
    private val recorder: ViolationRecorder,
    private val conditions: DeviceConditions,
) : ViewModel() {
    private val _ui = MutableStateFlow(AnalysisUi())
    val ui = _ui.asStateFlow()

    @Volatile private var paused = false
    @Volatile private var cancelled = false
    private var started = false

    fun togglePause() {
        paused = !paused
        _ui.update { it.copy(paused = paused) }
    }

    /** Cancel ends the session but keeps every violation already found. */
    fun cancel() {
        cancelled = true
        paused = false
    }

    fun start(sessionId: String) {
        if (started) return
        started = true
        viewModelScope.launch(Dispatchers.Default) {
            val session = db.sessions().byId(sessionId) ?: return@launch
            val manifest = (models.status() as? ModelStatus.Ready)?.manifest
            if (manifest == null) {
                _ui.update { it.copy(error = "Detection models are not installed yet.") }
                return@launch
            }
            run(session, manifest)
        }
    }

    private suspend fun run(session: SessionEntity, manifest: com.misamabbas.helmetguard.ml.ModelManifest) {
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

        val duration = session.videoDurationMs ?: 0L
        // Resume a little early; duplicate protection absorbs the overlap.
        val startMs = max(0L, session.lastProcessedMs - RESUME_OVERLAP_MS)
        val everyMs = 1000L / max(1, session.sampleFps ?: current.videoSampleFps)
        recorder.updateSession(session.id) { it.copy(status = SessionStatus.ACTIVE) }
        _ui.update { it.copy(durationMs = duration, positionMs = startMs) }

        var frames = session.framesProcessed
        var lastCheckpoint = startMs
        var lastPosition = startMs
        val wallStart = System.currentTimeMillis()
        var thisRun = 0

        try {
            VideoFrameSource(context, Uri.parse(session.videoUri)).decode(startMs, everyMs, { !cancelled }) { frame, ptsMs ->
                waitWhilePaused()
                if (cancelled) return@decode
                processor.process(frame, ptsMs)
                frames++
                thisRun++
                lastPosition = ptsMs

                if (ptsMs - lastCheckpoint >= CHECKPOINT_MS) {
                    lastCheckpoint = ptsMs
                    val total = frames
                    recorder.updateSession(session.id) { it.copy(lastProcessedMs = ptsMs, framesProcessed = total) }
                }

                val perFrame = (System.currentTimeMillis() - wallStart) / max(1, thisRun)
                val remaining = (duration - ptsMs) / everyMs * perFrame
                val alerts = processor.totals.violations
                _ui.update {
                    it.copy(positionMs = ptsMs, frames = frames, alerts = alerts, remainingMs = max(0, remaining))
                }
            }
            processor.finish(lastPosition + FLUSH_MS)
            val total = frames
            val status = if (cancelled) SessionStatus.CANCELLED else SessionStatus.COMPLETED
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
            runBlocking { delay(POLL_MS) }
        }
        _ui.update { it.copy(heatPaused = false) }
    }

    private companion object {
        const val RESUME_OVERLAP_MS = 3_000L
        const val CHECKPOINT_MS = 2_000L
        const val FLUSH_MS = 2_000L
        const val POLL_MS = 300L
    }
}

@Composable
fun VideoAnalysisScreen(sessionId: String, onFinished: () -> Unit, viewModel: VideoAnalysisViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    LaunchedEffect(sessionId) { viewModel.start(sessionId) }
    LaunchedEffect(ui.finished) { if (ui.finished) onFinished() }

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Analysing video", style = MaterialTheme.typography.headlineSmall)
        val progress = if (ui.durationMs == 0L) 0f else (ui.positionMs.toFloat() / ui.durationMs).coerceIn(0f, 1f)
        LinearProgressIndicator({ progress }, Modifier.fillMaxWidth())
        Text("${formatClip(ui.positionMs)} of ${formatClip(ui.durationMs)}")
        Text("${ui.frames} frames analysed")
        Text("Time remaining: ${formatClip(ui.remainingMs)}")
        Text("${ui.alerts} AI alerts so far", style = MaterialTheme.typography.titleMedium)
        if (ui.heatPaused) Text("Paused while the phone cools down.", color = MaterialTheme.colorScheme.error)
        ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(viewModel::togglePause, Modifier.weight(1f)) { Text(if (ui.paused) "Resume" else "Pause") }
            OutlinedButton({ viewModel.cancel() }, Modifier.weight(1f)) { Text("Cancel") }
        }
    }
}
