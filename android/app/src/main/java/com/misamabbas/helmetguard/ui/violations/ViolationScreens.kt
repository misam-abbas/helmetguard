package com.misamabbas.helmetguard.ui.violations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.misamabbas.helmetguard.data.EvidenceStore
import com.misamabbas.helmetguard.data.SettingsRepository
import com.misamabbas.helmetguard.data.SourceFilter
import com.misamabbas.helmetguard.data.db.AppDatabase
import com.misamabbas.helmetguard.data.db.EvidenceFileEntity
import com.misamabbas.helmetguard.data.db.SyncState
import com.misamabbas.helmetguard.data.db.ViolationEntity
import com.misamabbas.helmetguard.data.db.ViolationListRow
import com.misamabbas.helmetguard.sync.SyncScheduler
import com.misamabbas.helmetguard.ui.components.EmptyMessage
import com.misamabbas.helmetguard.ui.components.LocalImage
import com.misamabbas.helmetguard.ui.components.PlateText
import com.misamabbas.helmetguard.ui.components.SourceSwitch
import com.misamabbas.helmetguard.ui.components.SourceTag
import com.misamabbas.helmetguard.ui.components.SyncBadge
import com.misamabbas.helmetguard.ui.components.UnverifiedBadge
import com.misamabbas.helmetguard.ui.components.formatClip
import com.misamabbas.helmetguard.ui.components.formatTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ViolationsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    db: AppDatabase,
    val store: EvidenceStore,
) : ViewModel() {
    val source = settings.sourceFilter.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SourceFilter.ALL)
    val query = MutableStateFlow("")
    val rows = combine(source, query) { s, q -> s to q.uppercase().filter(Char::isLetterOrDigit) }
        .flatMapLatest { (s, q) -> db.violations().observeList(s.dbValue, null, q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun select(value: SourceFilter) {
        viewModelScope.launch { settings.setSourceFilter(value) }
    }
}

@Composable
fun ViolationsScreen(onOpen: (String) -> Unit, viewModel: ViolationsViewModel = hiltViewModel()) {
    val source by viewModel.source.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val rows by viewModel.rows.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Violations", style = MaterialTheme.typography.headlineSmall)
        SourceSwitch(source, viewModel::select)
        OutlinedTextField(query, { viewModel.query.value = it }, label = { Text("Search plate") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (rows.isEmpty()) {
            EmptyMessage("No violations yet", "AI alerts appear here after a session finds one.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rows, key = { it.id }) { row -> ViolationCard(row, viewModel.store, onOpen) }
            }
        }
    }
}

@Composable
fun ViolationCard(row: ViolationListRow, store: EvidenceStore, onOpen: (String) -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().clickable { onOpen(row.id) }) {
        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LocalImage(row.thumbPath?.let(store::file), "Violation thumbnail", Modifier.width(84.dp).height(63.dp), maxSize = 320)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlateText(row.plateTextOcr, row.plateOcrStatus)
                    SourceTag(row.source)
                }
                Text(
                    if (row.source == "video") "${row.videoFileName ?: "Video"} · ${formatClip(row.videoTimestampMs)}" else formatTime(row.detectedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    UnverifiedBadge()
                    SyncBadge(row.syncState)
                }
            }
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ViolationDetailViewModel @Inject constructor(
    private val db: AppDatabase,
    val store: EvidenceStore,
    private val sync: SyncScheduler,
) : ViewModel() {
    private val id = MutableStateFlow<String?>(null)
    val violation = id.flatMapLatest { it?.let(db.violations()::observe) ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val evidence = id.flatMapLatest { it?.let(db.violations()::observeEvidence) ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun load(value: String) {
        id.value = value
    }

    fun retry() {
        viewModelScope.launch {
            id.value?.let { db.violations().setSyncState(it, SyncState.QUEUED) }
            sync.syncSoon()
        }
    }
}

@Composable
fun ViolationDetailScreen(id: String, viewModel: ViolationDetailViewModel = hiltViewModel()) {
    LaunchedEffect(id) { viewModel.load(id) }
    val violation by viewModel.violation.collectAsStateWithLifecycle()
    val evidence by viewModel.evidence.collectAsStateWithLifecycle()
    val current = violation ?: return

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PlateText(current.plateTextOcr, current.plateOcrStatus)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            UnverifiedBadge()
            SyncBadge(current.syncState)
        }
        current.syncError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (current.syncState != SyncState.SYNCED && current.syncState != SyncState.LOCAL_ONLY) {
            OutlinedButton(viewModel::retry) { Text("Retry sync") }
        }
        EvidenceGallery(evidence, viewModel.store)
        Facts(current)
    }
}

@Composable
private fun EvidenceGallery(files: List<EvidenceFileEntity>, store: EvidenceStore) {
    val order = listOf("full_frame", "motorcycle", "rider", "head", "plate", "plate_enhanced", "alt_frame")
    order.flatMap { kind -> files.filter { it.kind == kind && !it.removedLocally } }.forEach { file ->
        Column {
            LocalImage(store.file(file.relativePath), file.kind, Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentScale = ContentScale.Fit)
            Text(if (file.isEnhanced) "plate (enhanced copy)" else file.kind.replace('_', ' '), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Facts(v: ViolationEntity) {
    @Composable
    fun fact(label: String, value: String) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        fact("Detected", formatTime(v.detectedAt))
        if (v.videoTimestampMs != null) fact("Clip time", formatClip(v.videoTimestampMs))
        fact("Track", "#${v.trackId}")
        fact("Riders without helmet", "${v.noHelmetCount} of ${v.riderCount}")
        fact("Helmet confidence", v.helmetConfidence?.let { "%.2f".format(it) } ?: "—")
        fact("Plate confidence", v.plateOcrConfidence?.let { "%.2f".format(it) } ?: "—")
        fact("Plate status", v.plateOcrStatus.replace('_', ' '))
        fact("Model", v.modelVersion.ifBlank { "—" })
        fact("App", v.appVersion)
    }
}
