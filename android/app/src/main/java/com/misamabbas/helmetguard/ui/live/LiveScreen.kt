package com.misamabbas.helmetguard.ui.live

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import com.misamabbas.helmetguard.BuildConfig
import android.provider.Settings
import android.util.Log
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.misamabbas.helmetguard.camera.DeviceConditions
import com.misamabbas.helmetguard.camera.FrameConverter
import com.misamabbas.helmetguard.camera.HeatLevel
import com.misamabbas.helmetguard.camera.MotionMonitor
import com.misamabbas.helmetguard.data.SettingsRepository
import com.misamabbas.helmetguard.data.db.AppDatabase
import com.misamabbas.helmetguard.data.db.SessionEntity
import com.misamabbas.helmetguard.data.db.SessionStatus
import com.misamabbas.helmetguard.ml.ModelStatus
import com.misamabbas.helmetguard.ml.ModelStore
import com.misamabbas.helmetguard.pipeline.FrameOutput
import com.misamabbas.helmetguard.pipeline.FrameProcessor
import com.misamabbas.helmetguard.pipeline.PipelineConfig
import com.misamabbas.helmetguard.pipeline.SessionContext
import com.misamabbas.helmetguard.pipeline.StillPhoto
import com.misamabbas.helmetguard.pipeline.TrackRecord
import com.misamabbas.helmetguard.pipeline.ViolationRecorder
import com.misamabbas.helmetguard.ui.components.drawDetections
import com.misamabbas.helmetguard.ui.components.drawLabel
import com.misamabbas.helmetguard.ui.theme.Status
import com.misamabbas.helmetguard.vision.geometry.Box as VBox
import com.misamabbas.helmetguard.vision.verify.RiderPolicy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.Executors
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

data class LiveUi(
    val running: Boolean = false,
    val modelsReady: Boolean = true,
    val output: FrameOutput? = null,
    val fps: Float = 0f,
    val heat: HeatLevel = HeatLevel.COOL,
    val alerts: Int = 0,
    val roadArea: VBox? = null,
    val evidenceZone: VBox? = null,
    val error: String? = null,
)

@HiltViewModel
class LiveViewModel @Inject constructor(
    private val models: ModelStore,
    private val recorder: ViolationRecorder,
    private val settings: SettingsRepository,
    private val conditions: DeviceConditions,
    private val motion: MotionMonitor,
    db: AppDatabase,
) : ViewModel() {
    /** Set once the camera is bound. Null on phones that cannot run all three use cases. */
    @Volatile var imageCapture: ImageCapture? = null
    private val stillExecutor = Executors.newSingleThreadExecutor()

    private val _ui = MutableStateFlow(LiveUi(modelsReady = models.status() is ModelStatus.Ready))
    val ui = _ui.asStateFlow()
    val unsynced = db.violations().observeUnsyncedCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val converter = FrameConverter()
    private val lock = Any()
    private var processor: FrameProcessor? = null
    private var sessionId: String? = null
    private var lastFrameMs = 0L
    private var lastConditionsMs = 0L
    private var minIntervalMs = 0L
    private var frameCount = 0
    private var startedMs = 0L
    private var lastFrameTs = 0L

    fun setRoadArea(box: VBox?) = _ui.update { it.copy(roadArea = box) }
    fun setEvidenceZone(box: VBox?) = _ui.update { it.copy(evidenceZone = box) }

    fun start(location: String) {
        val status = models.status() as? ModelStatus.Ready ?: return
        viewModelScope.launch {
            runCatching {
                val current = settings.current()
                val id = UUID.randomUUID().toString()
                val policy = if (current.riderPolicy == RiderPolicy.ALL_RIDERS) "all_riders" else "driver_only"
                val settingsJson = buildJsonObject {
                    put("min_no_helmet_observations", current.minNoHelmetObservations)
                    put("min_no_helmet_confidence", current.minNoHelmetConfidence)
                    put("min_no_helmet_share", current.minNoHelmetShare)
                    put("rider_policy", policy)
                }.toString()
                recorder.saveSession(
                    SessionEntity(id = id, source = "live", startedAt = System.currentTimeMillis(), locationLabel = location.ifBlank { null }, settingsJson = settingsJson, modelVersion = status.manifest.version),
                )

                val stage1 = models.create(status.manifest.stage1)
                val stage2 = status.manifest.stage2?.let(models::create)
                val context = SessionContext(id, "live", status.manifest.version, policy, current.jpegQuality, isVideo = false)
                val wallBase = System.currentTimeMillis()
                var frameBase: Long? = null
                val listener = recorder.listenerFor(context, onStillWanted = ::captureStill) { ts ->
                    val base = frameBase ?: ts.also { frameBase = it }
                    wallBase + (ts - base)
                }
                val ui = _ui.value
                synchronized(lock) {
                    processor = FrameProcessor(
                        stage1,
                        stage2,
                        PipelineConfig(current.verifier(), current.stage1Confidence, ui.roadArea, ui.evidenceZone, current.jpegQuality, adaptToFrameRate = true),
                        listener,
                    )
                    sessionId = id
                    frameCount = 0
                    startedMs = System.currentTimeMillis()
                }
                motion.start()
                _ui.update { it.copy(running = true, error = null, alerts = 0) }
            }.onFailure { e ->
                Log.e(TAG, "Could not start session", e)
                _ui.update { it.copy(error = "Could not start: ${e.message}") }
            }
        }
    }

    fun stop() {
        val id: String
        synchronized(lock) {
            val current = processor ?: return
            id = sessionId ?: return
            current.finish(lastFrameTs + FLUSH_MS)
            current.close()
            processor = null
            sessionId = null
        }
        val elapsed = max(1L, System.currentTimeMillis() - startedMs)
        val frames = frameCount
        val moved = motion.stop()
        recorder.updateSession(id) {
            it.copy(
                status = SessionStatus.COMPLETED,
                endedAt = System.currentTimeMillis(),
                framesProcessed = frames,
                avgFps = frames * 1000f / elapsed,
                settingsJson = moved.mergeInto(it.settingsJson),
            )
        }
        _ui.update { it.copy(running = false, output = null) }
    }

    /** One full-resolution photo of a confirmed bike. It is optional, so failures are only logged. */
    private fun captureStill(record: TrackRecord) {
        val capture = imageCapture ?: return
        capture.takePicture(
            stillExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        record.still = StillPhoto(bytes, image.width, image.height)
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.w(TAG, "Still capture failed", exception)
                }
            },
        )
    }

    /** Runs on the analysis thread. CameraX keeps only the newest frame, so nothing piles up. */
    fun analyze(image: ImageProxy) {
        try {
            synchronized(lock) {
                val current = processor ?: return
                val now = System.currentTimeMillis()
                if (now - lastConditionsMs > CONDITIONS_EVERY_MS) {
                    lastConditionsMs = now
                    minIntervalMs = conditions.minFrameIntervalMs()
                    _ui.update { it.copy(heat = conditions.heat()) }
                }
                if (now - lastFrameMs < minIntervalMs) return
                val fps = if (lastFrameMs == 0L) 0f else 1000f / max(1L, now - lastFrameMs)
                lastFrameMs = now

                val frame = converter.convert(image)
                // The time rules (a bare head seen over at least 0.5 s, an alert saved 3 s after it is
                // confirmed) need real time between processed frames. On a Pixel 7a the camera's
                // timestamps advanced only 33 ms per processed frame while each took about 370 ms,
                // which stretched every rule about 11 times and kept alerts open long enough to fill
                // the evidence memory, so later alerts had no photos.
                val timestampMs = SystemClock.elapsedRealtime()
                if (BuildConfig.DEBUG) {
                    Log.d(TRACE, "frame clocks: camera ${image.imageInfo.timestamp / 1_000_000} ms, phone $timestampMs ms")
                }
                lastFrameTs = timestampMs
                val output = current.process(frame, timestampMs)
                frameCount++
                val alerts = current.totals.violations
                _ui.update { it.copy(output = output, fps = it.fps * 0.8f + fps * 0.2f, alerts = alerts) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame failed", e)
        } finally {
            image.close()
        }
    }

    override fun onCleared() {
        stop()
        stillExecutor.shutdown()
    }

    private companion object {
        const val TAG = "HelmetGuard"
        const val TRACE = "HelmetGuardTrace"
        const val CONDITIONS_EVERY_MS = 2000L
        const val FLUSH_MS = 2000L
    }
}

private enum class DrawMode { NONE, ROAD, EVIDENCE }

@Composable
fun LiveScreen(viewModel: LiveViewModel = hiltViewModel()) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasCamera(context)) }
    var refused by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        refused = !ok
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    when {
        granted -> LiveContent(viewModel)
        refused -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
            Text("Camera permission is needed", style = MaterialTheme.typography.titleMedium)
            Text("HelmetGuard analyses the road on this phone. Nothing leaves the phone until you sync it.", style = MaterialTheme.typography.bodyMedium)
            Button({
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }) { Text("Open Settings") }
        }
        else -> Box(Modifier.fillMaxSize())
    }
}

private fun hasCamera(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

@Composable
private fun LiveContent(viewModel: LiveViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val unsynced by viewModel.unsynced.collectAsStateWithLifecycle()
    var location by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(DrawMode.NONE) }

    // The phone stays awake while a session runs.
    val view = LocalView.current
    DisposableEffect(ui.running) {
        view.keepScreenOn = ui.running
        onDispose { view.keepScreenOn = false }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
            CameraPreview(viewModel)
            Overlay(ui, mode) { box ->
                when (mode) {
                    DrawMode.ROAD -> viewModel.setRoadArea(box)
                    DrawMode.EVIDENCE -> viewModel.setEvidenceZone(box)
                    DrawMode.NONE -> Unit
                }
            }
            StatusStrip(ui, unsynced, Modifier.align(Alignment.TopStart))
        }

        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                !ui.modelsReady -> Text("Detection models are not installed yet, so the camera shows no boxes. Add them after training.", style = MaterialTheme.typography.bodySmall, color = Status.Undecided)
                !ui.running -> Text("Mount the phone on a tripod facing one direction of traffic.", style = MaterialTheme.typography.bodySmall)
            }
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (!ui.running) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(mode == DrawMode.ROAD, { mode = if (mode == DrawMode.ROAD) DrawMode.NONE else DrawMode.ROAD }, { Text("Draw road area") })
                    FilterChip(mode == DrawMode.EVIDENCE, { mode = if (mode == DrawMode.EVIDENCE) DrawMode.NONE else DrawMode.EVIDENCE }, { Text("Draw evidence zone") })
                }
                OutlinedTextField(location, { location = it }, label = { Text("Location label") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button({ mode = DrawMode.NONE; viewModel.start(location) }, Modifier.fillMaxWidth(), enabled = ui.modelsReady) { Text("Start session") }
            } else {
                OutlinedButton(viewModel::stop, Modifier.fillMaxWidth()) { Text("Stop session") }
            }
        }
    }
}

@Composable
private fun CameraPreview(viewModel: LiveViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FIT_CENTER
                val future = ProcessCameraProvider.getInstance(ctx)
                future.addListener({
                    val provider = future.get()
                    val selector = ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                        .build()
                    val preview = Preview.Builder().setResolutionSelector(selector).build().also { it.surfaceProvider = surfaceProvider }
                    val analysis = ImageAnalysis.Builder()
                        .setResolutionSelector(selector)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()
                        .also { it.setAnalyzer(executor, viewModel::analyze) }
                    val still = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                    provider.unbindAll()
                    try {
                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, still)
                        viewModel.imageCapture = still
                    } catch (e: IllegalArgumentException) {
                        // Some phones cannot run all three at once, and the still is the optional one.
                        Log.w("HelmetGuard", "Running without still capture", e)
                        viewModel.imageCapture = null
                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    }
                }, ContextCompat.getMainExecutor(context))
            }
        },
    )
}

@Composable
private fun Overlay(ui: LiveUi, mode: DrawMode, onDrawn: (VBox) -> Unit) {
    var start by remember { mutableStateOf<Offset?>(null) }
    var end by remember { mutableStateOf<Offset?>(null) }

    // Ticks every display frame during a session, so boxes glide between AI results.
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(ui.running) {
        while (ui.running) {
            withFrameMillis { }
            now = SystemClock.uptimeMillis()
        }
    }

    val gesture = if (mode == DrawMode.NONE) Modifier else Modifier.pointerInput(mode) {
        detectDragGestures(
            onDragStart = { start = it; end = it },
            onDrag = { change, _ -> end = change.position },
            onDragEnd = {
                val a = start
                val b = end
                if (a != null && b != null) {
                    onDrawn(VBox(min(a.x, b.x) / size.width, min(a.y, b.y) / size.height, max(a.x, b.x) / size.width, max(a.y, b.y) / size.height))
                }
                start = null
                end = null
            },
        )
    }

    Canvas(Modifier.fillMaxSize().then(gesture)) {
        ui.roadArea?.let { drawZone(it, Status.Plate, "Road area") }
        ui.evidenceZone?.let { drawZone(it, Status.Undecided, "Evidence zone") }
        val a = start
        val b = end
        if (a != null && b != null) {
            drawRect(Color.White, Offset(min(a.x, b.x), min(a.y, b.y)), ComposeSize(kotlin.math.abs(a.x - b.x), kotlin.math.abs(a.y - b.y)), style = Stroke(3f))
        }

        val output = ui.output ?: return@Canvas
        drawDetections(output, glideMs = (now - output.producedAtMs).coerceIn(0L, MAX_GLIDE_MS))
    }
}

private const val MAX_GLIDE_MS = 200L

private fun DrawScope.drawZone(box: VBox, color: Color, label: String) {
    val topLeft = Offset(box.left * size.width, box.top * size.height)
    drawRect(color.copy(alpha = 0.12f), topLeft, ComposeSize(box.width * size.width, box.height * size.height))
    drawRect(color, topLeft, ComposeSize(box.width * size.width, box.height * size.height), style = Stroke(3f))
    drawLabel(label, topLeft, color)
}

@Composable
private fun StatusStrip(ui: LiveUi, unsynced: Int, modifier: Modifier) {
    val output = ui.output
    Row(modifier.background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val white = Color.White
        val style = MaterialTheme.typography.labelSmall
        Text("%.1f FPS".format(ui.fps), color = white, style = style)
        output?.let { Text("%.0f+%.0f ms".format(it.stage1Ms, it.stage2Ms), color = white, style = style) }
        Text("${output?.activeTracks ?: 0} tracks", color = white, style = style)
        Text("${ui.alerts} alerts", color = white, style = style)
        Text(ui.heat.label, color = if (ui.heat == HeatLevel.COOL) white else Status.Undecided, style = style)
        Text("$unsynced to sync", color = white, style = style)
    }
}
