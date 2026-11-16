package com.misamabbas.helmetguard.ui.settings

import android.graphics.Bitmap
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.misamabbas.helmetguard.BuildConfig
import com.misamabbas.helmetguard.data.AppSettings
import com.misamabbas.helmetguard.data.SettingsRepository
import com.misamabbas.helmetguard.data.db.AppDatabase
import com.misamabbas.helmetguard.ml.Letterboxer
import com.misamabbas.helmetguard.ml.ModelStatus
import com.misamabbas.helmetguard.ml.ModelStore
import com.misamabbas.helmetguard.sync.SyncScheduler
import com.misamabbas.helmetguard.ui.auth.AuthViewModel
import com.misamabbas.helmetguard.ui.components.SyncBadge
import com.misamabbas.helmetguard.ui.components.formatTime
import com.misamabbas.helmetguard.vision.verify.RiderPolicy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class SyncViewModel @Inject constructor(private val db: AppDatabase, private val scheduler: SyncScheduler) : ViewModel() {
    val queue = db.violations().observeQueue().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val log = db.syncLog().observeRecent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val running = scheduler.isRunning.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun syncNow() {
        viewModelScope.launch {
            db.violations().retryFailed()
            scheduler.syncSoon()
        }
    }
}

@Composable
fun SyncCenterScreen(viewModel: SyncViewModel = hiltViewModel()) {
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Sync center", style = MaterialTheme.typography.headlineSmall)
        Text(if (running) "Uploading now…" else "${queue.size} records waiting", style = MaterialTheme.typography.bodyMedium)
        Button(viewModel::syncNow, Modifier.fillMaxWidth(), enabled = !running) { Text("Sync now") }
        OutlinedCard {
            Text(
                "Some phones delay background uploads to save battery. If sync seems slow, open Android Settings, find HelmetGuard, and set battery use to Unrestricted.",
                Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(queue, key = { it.id }) { violation ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(formatTime(violation.detectedAt), style = MaterialTheme.typography.bodySmall)
                        violation.syncError?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                    }
                    SyncBadge(violation.syncState)
                }
                HorizontalDivider()
            }
        }
    }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(private val repository: SettingsRepository) : ViewModel() {
    val settings = repository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    fun update(change: (AppSettings) -> AppSettings) {
        viewModelScope.launch { repository.save(change(settings.value)) }
    }
}

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    // The same instance AppRoot watches, so signing out returns to the sign-in screen.
    val auth: AuthViewModel = hiltViewModel(LocalActivity.current as ComponentActivity)
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var signOutBlocked by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        Text("Who must wear a helmet", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(s.riderPolicy == RiderPolicy.ALL_RIDERS, { viewModel.update { it.copy(riderPolicy = RiderPolicy.ALL_RIDERS) } }, { Text("Every rider") })
            FilterChip(s.riderPolicy == RiderPolicy.DRIVER_ONLY, { viewModel.update { it.copy(riderPolicy = RiderPolicy.DRIVER_ONLY) } }, { Text("Driver only") })
        }

        SliderRow("Bare-head frames needed", s.minNoHelmetObservations.toString(), s.minNoHelmetObservations.toFloat(), 3f..15f, 11) { v -> viewModel.update { it.copy(minNoHelmetObservations = v.roundToInt()) } }
        SliderRow("Minimum head confidence", "%.2f".format(s.minNoHelmetConfidence), s.minNoHelmetConfidence, 0.3f..0.9f) { v -> viewModel.update { it.copy(minNoHelmetConfidence = v) } }
        SliderRow("Bare-head share of votes", "%.0f%%".format(s.minNoHelmetShare * 100), s.minNoHelmetShare, 0.5f..1f) { v -> viewModel.update { it.copy(minNoHelmetShare = v) } }
        SliderRow("Motorcycle confidence", "%.2f".format(s.stage1Confidence), s.stage1Confidence, 0.2f..0.8f) { v -> viewModel.update { it.copy(stage1Confidence = v) } }
        SliderRow("Video frames per second", s.videoSampleFps.toString(), s.videoSampleFps.toFloat(), 2f..30f, 27) { v -> viewModel.update { it.copy(videoSampleFps = v.roundToInt()) } }
        SliderRow("Evidence JPEG quality", s.jpegQuality.toString(), s.jpegQuality.toFloat(), 60f..95f) { v -> viewModel.update { it.copy(jpegQuality = v.roundToInt()) } }
        SliderRow("Remove synced full images after (days)", s.localCleanupDays.toString(), s.localCleanupDays.toFloat(), 1f..30f, 28) { v -> viewModel.update { it.copy(localCleanupDays = v.roundToInt()) } }

        ToggleRow("Upload on Wi-Fi only", s.wifiOnly) { v -> viewModel.update { it.copy(wifiOnly = v) } }
        ToggleRow("Ask for fingerprint or PIN to open evidence", s.evidenceLock) { v -> viewModel.update { it.copy(evidenceLock = v) } }

        OutlinedButton({
            scope.launch { signOutBlocked = !auth.signOut() }
        }, Modifier.fillMaxWidth()) { Text("Sign out") }
        if (signOutBlocked) Text("Sync your records first. Signing out now could lose them.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SliderRow(label: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
        Slider(current, onChange, valueRange = range, steps = steps)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onChange)
    }
}

data class BenchmarkResult(val stage1Ms: Float, val stage2Ms: Float?)

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(private val models: ModelStore) : ViewModel() {
    val status = models.status()
    private val _result = MutableStateFlow<BenchmarkResult?>(null)
    val result = _result.asStateFlow()
    val running = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    fun benchmark() {
        val manifest = (status as? ModelStatus.Ready)?.manifest ?: return
        viewModelScope.launch {
            running.value = true
            runCatching {
                withContext(Dispatchers.Default) {
                    val stage1 = timed(manifest.stage1.input) { models.create(manifest.stage1) }
                    val stage2 = manifest.stage2?.let { entry -> timed(entry.input) { models.create(entry) } }
                    BenchmarkResult(stage1, stage2)
                }
            }.onSuccess { _result.value = it }.onFailure { error.value = it.message }
            running.value = false
        }
    }

    private inline fun timed(size: Int, create: () -> com.misamabbas.helmetguard.ml.Detector): Float {
        val frame = Bitmap.createBitmap(size * 2, size * 2, Bitmap.Config.ARGB_8888)
        val box = Letterboxer(size)
        box.draw(frame)
        return create().use { detector ->
            repeat(WARM_UP) { detector.detect(box.bitmap, 0.5f) }
            val start = System.nanoTime()
            repeat(RUNS) { detector.detect(box.bitmap, 0.5f) }
            (System.nanoTime() - start) / 1e6f / RUNS
        }
    }

    private companion object {
        const val WARM_UP = 5
        const val RUNS = 30
    }
}

@Composable
fun DiagnosticsScreen(viewModel: DiagnosticsViewModel = hiltViewModel()) {
    val result by viewModel.result.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val status = viewModel.status

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Diagnostics and about", style = MaterialTheme.typography.headlineSmall)
        Text("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}", style = MaterialTheme.typography.bodyMedium)
        Text("App version: ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
        Text(
            when (status) {
                is ModelStatus.Ready -> "Model: ${status.manifest.version}"
                ModelStatus.Missing -> "Model: not installed yet"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(viewModel::benchmark, Modifier.fillMaxWidth(), enabled = status is ModelStatus.Ready && !running) { Text(if (running) "Measuring…" else "Run model benchmark") }
        result?.let {
            Text("Stage 1: %.1f ms per frame".format(it.stage1Ms))
            it.stage2Ms?.let { ms -> Text("Stage 2: %.1f ms per crop".format(ms)) }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        HorizontalDivider()
        Text("Privacy", style = MaterialTheme.typography.titleSmall)
        Text(
            "No face is ever recognised or matched. Head photos are used only to decide helmet or no helmet. Evidence stays in this app's private storage, never in the gallery. Every record is an unverified AI alert until a person reviews it, and HelmetGuard never issues fines.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Licences", style = MaterialTheme.typography.titleSmall)
        Text("HelmetGuard is open source under AGPL-3.0. Uses ONNX Runtime (MIT), Jetpack libraries (Apache-2.0) and supabase-kt (MIT). Models trained with Ultralytics YOLO11 (AGPL-3.0) on helmet-detection-and-license-plate-recognition by on Gia Capital (CC BY 4.0, Roboflow Universe) and HELMET by Siebert and Lin (CC BY 4.0, OSF). Contains information from Trafffic_Violations_Triple_Riding_No_Helmet_Plate_ by DevguruCodes, made available under the ODbL.", style = MaterialTheme.typography.bodySmall)
    }
}
