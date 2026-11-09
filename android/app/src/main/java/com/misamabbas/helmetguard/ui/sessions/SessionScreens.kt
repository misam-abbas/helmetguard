package com.misamabbas.helmetguard.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.misamabbas.helmetguard.data.EvidenceStore
import com.misamabbas.helmetguard.data.SettingsRepository
import com.misamabbas.helmetguard.data.SourceFilter
import com.misamabbas.helmetguard.data.db.AppDatabase
import com.misamabbas.helmetguard.data.db.SessionEntity
import com.misamabbas.helmetguard.data.db.SessionStatus
import com.misamabbas.helmetguard.ui.components.EmptyMessage
import com.misamabbas.helmetguard.ui.components.Pill
import com.misamabbas.helmetguard.ui.components.SourceSwitch
import com.misamabbas.helmetguard.ui.components.SourceTag
import com.misamabbas.helmetguard.ui.components.StatCard
import com.misamabbas.helmetguard.ui.components.compliancePercent
import com.misamabbas.helmetguard.ui.components.formatClip
import com.misamabbas.helmetguard.ui.components.formatTime
import com.misamabbas.helmetguard.ui.theme.Status
import com.misamabbas.helmetguard.ui.violations.ViolationCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SessionsViewModel @Inject constructor(private val settings: SettingsRepository, db: AppDatabase) : ViewModel() {
    val source = settings.sourceFilter.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SourceFilter.ALL)
    val sessions = source.flatMapLatest { db.sessions().observeAll(it.dbValue) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun select(value: SourceFilter) {
        viewModelScope.launch { settings.setSourceFilter(value) }
    }
}

@Composable
fun SessionsScreen(onOpen: (String) -> Unit, onResume: (String) -> Unit, viewModel: SessionsViewModel = hiltViewModel()) {
    val source by viewModel.source.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Sessions history", style = MaterialTheme.typography.headlineSmall)
        SourceSwitch(source, viewModel::select)
        if (sessions.isEmpty()) {
            EmptyMessage("No sessions yet")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sessions, key = { it.id }) { session -> SessionCard(session, onOpen, onResume) }
            }
        }
    }
}

@Composable
private fun SessionCard(session: SessionEntity, onOpen: (String) -> Unit, onResume: (String) -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().clickable { onOpen(session.id) }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(titleOf(session), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1)
                SourceTag(session.source)
            }
            Text(formatTime(session.startedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (session.source == "video") {
                Text("${formatClip(session.lastProcessedMs)} of ${formatClip(session.videoDurationMs)} analysed", style = MaterialTheme.typography.bodySmall)
            }
            Text("${session.motorcyclesTotal} motorcycles · ${session.violationsConfirmed} AI alerts · ${compliancePercent(session.ridersHelmet, session.ridersNoHelmet)} compliance", style = MaterialTheme.typography.bodySmall)
            if (session.status == SessionStatus.INTERRUPTED) {
                Pill("Interrupted", Status.Undecided)
                if (session.source == "video") OutlinedButton({ onResume(session.id) }) { Text("Resume") }
            }
        }
    }
}

private fun titleOf(s: SessionEntity) = if (s.source == "video") s.videoFileName ?: "Video" else s.locationLabel ?: "Unlabelled location"

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SummaryViewModel @Inject constructor(db: AppDatabase, val store: EvidenceStore) : ViewModel() {
    private val id = MutableStateFlow<String?>(null)
    val session = id.flatMapLatest { it?.let(db.sessions()::observe) ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val violations = id.flatMapLatest { value -> value?.let { db.violations().observeList(null, it, "") } ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun load(value: String) {
        id.value = value
    }
}

@Composable
fun SessionSummaryScreen(id: String, onOpenViolation: (String) -> Unit, viewModel: SummaryViewModel = hiltViewModel()) {
    LaunchedEffect(id) { viewModel.load(id) }
    val session by viewModel.session.collectAsStateWithLifecycle()
    val violations by viewModel.violations.collectAsStateWithLifecycle()
    val s = session ?: return
    val plates = violations.count { it.plateTextOcr != null }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(titleOf(s), style = MaterialTheme.typography.headlineSmall, maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("Compliance", compliancePercent(s.ridersHelmet, s.ridersNoHelmet), Modifier.weight(1f))
            StatCard("AI alerts", s.violationsConfirmed.toString(), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("Motorcycles", s.motorcyclesTotal.toString(), Modifier.weight(1f))
            StatCard("Riders", s.ridersTotal.toString(), Modifier.weight(1f))
        }
        Text("With helmet ${s.ridersHelmet} · Without ${s.ridersNoHelmet} · Unknown ${s.ridersUnknown}", style = MaterialTheme.typography.bodySmall)
        Text("Plates read $plates · Unreadable ${violations.size - plates} · Frames ${s.framesProcessed}" + (s.avgFps?.let { " · %.1f FPS".format(it) } ?: ""), style = MaterialTheme.typography.bodySmall)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(violations, key = { it.id }) { row -> ViolationCard(row, viewModel.store, onOpenViolation) }
        }
    }
}
