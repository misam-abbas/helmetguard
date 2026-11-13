package com.misamabbas.helmetguard.ui.video

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.misamabbas.helmetguard.data.SettingsRepository
import com.misamabbas.helmetguard.data.db.SessionEntity
import com.misamabbas.helmetguard.ml.ModelStatus
import com.misamabbas.helmetguard.ml.ModelStore
import com.misamabbas.helmetguard.pipeline.ViolationRecorder
import com.misamabbas.helmetguard.ui.components.drawDetections
import com.misamabbas.helmetguard.ui.components.formatClip
import com.misamabbas.helmetguard.video.VideoAnalysisManager
import com.misamabbas.helmetguard.video.VideoAnalysisService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

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
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(viewModel::pick) }
    val scope = rememberCoroutineScope()
    val begin = { scope.launch { viewModel.createSession()?.let(onStart) } }
    // The answer only decides whether the progress notification shows; analysis runs either way.
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { begin() }

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
                val needsAsking = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                if (needsAsking) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else begin()
            }, Modifier.fillMaxWidth(), enabled = ui.modelsReady) { Text("Analyse") }
        }
    }
}

private const val EST_MS_PER_FRAME = 90L

@HiltViewModel
class VideoAnalysisViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val manager: VideoAnalysisManager,
) : ViewModel() {
    val ui = manager.ui

    /** False if a different video is already being analysed. */
    fun start(sessionId: String): Boolean {
        if (manager.activeSessionId == sessionId) return true
        if (!manager.begin(sessionId)) return false
        VideoAnalysisService.start(context, sessionId)
        return true
    }

    fun togglePause() = manager.togglePause()

    fun cancel() = manager.cancel()
}

@Composable
fun VideoAnalysisScreen(sessionId: String, onFinished: () -> Unit, viewModel: VideoAnalysisViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var busyElsewhere by remember { mutableStateOf(false) }
    LaunchedEffect(sessionId) { busyElsewhere = !viewModel.start(sessionId) }
    val mine = ui.sessionId == sessionId
    LaunchedEffect(mine, ui.finished) { if (mine && ui.finished) onFinished() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Analysing video", style = MaterialTheme.typography.headlineSmall)
        if (busyElsewhere) {
            Text("Another video is being analysed. Wait for it to finish, then resume this one from Sessions history.", color = MaterialTheme.colorScheme.error)
            return@Column
        }

        val preview = ui.preview
        val output = ui.previewOutput
        if (preview != null) {
            Box(Modifier.fillMaxWidth().aspectRatio(preview.width.toFloat() / preview.height)) {
                Image(preview.asImageBitmap(), "Frame being analysed", Modifier.fillMaxSize())
                if (output != null) Canvas(Modifier.fillMaxSize()) { drawDetections(output) }
            }
        }

        val progress = if (ui.durationMs == 0L) 0f else (ui.positionMs.toFloat() / ui.durationMs).coerceIn(0f, 1f)
        LinearProgressIndicator({ progress }, Modifier.fillMaxWidth())
        Text("${formatClip(ui.positionMs)} of ${formatClip(ui.durationMs)}")
        Text("${ui.frames} frames analysed")
        Text("Time remaining: ${formatClip(ui.remainingMs)}")
        Text("${ui.alerts} AI alerts so far", style = MaterialTheme.typography.titleMedium)
        Text("You can leave this screen. Analysis keeps running in the background.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (ui.heatPaused) Text("Paused while the phone cools down.", color = MaterialTheme.colorScheme.error)
        ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(viewModel::togglePause, Modifier.weight(1f)) { Text(if (ui.paused) "Resume" else "Pause") }
            OutlinedButton(viewModel::cancel, Modifier.weight(1f)) { Text("Cancel") }
        }
    }
}
