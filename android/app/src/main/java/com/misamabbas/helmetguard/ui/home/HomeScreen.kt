package com.misamabbas.helmetguard.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.misamabbas.helmetguard.data.SettingsRepository
import com.misamabbas.helmetguard.data.SourceFilter
import com.misamabbas.helmetguard.data.db.AppDatabase
import com.misamabbas.helmetguard.data.db.SourceTotals
import com.misamabbas.helmetguard.ui.components.SourceSwitch
import com.misamabbas.helmetguard.ui.components.StatCard
import com.misamabbas.helmetguard.ui.components.compliancePercent
import com.misamabbas.helmetguard.video.VideoAnalysisManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val settings: SettingsRepository,
    db: AppDatabase,
    analysis: VideoAnalysisManager,
) : ViewModel() {
    /** Unfinished analysis in the background, so the user can get back to it. */
    val runningAnalysis = analysis.ui.map { state -> state.sessionId?.takeIf { !state.finished && analysis.isRunning } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val startOfToday = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    val source = settings.sourceFilter.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SourceFilter.ALL)
    val totals = source.flatMapLatest { db.sessions().observeTotals(startOfToday, it.dbValue) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SourceTotals(0, 0, 0, 0))
    val alerts = source.flatMapLatest { db.violations().observeCountSince(startOfToday, it.dbValue) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val unsynced = db.violations().observeUnsyncedCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun select(value: SourceFilter) {
        viewModelScope.launch { settings.setSourceFilter(value) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onLive: () -> Unit,
    onVideo: () -> Unit,
    onSessions: () -> Unit,
    onSettings: () -> Unit,
    onDiagnostics: () -> Unit,
    onSync: () -> Unit,
    onOpenAnalysis: (String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val runningAnalysis by viewModel.runningAnalysis.collectAsStateWithLifecycle()
    val source by viewModel.source.collectAsStateWithLifecycle()
    val totals by viewModel.totals.collectAsStateWithLifecycle()
    val alerts by viewModel.alerts.collectAsStateWithLifecycle()
    val unsynced by viewModel.unsynced.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("HelmetGuard") },
            actions = {
                IconButton(onSessions) { Icon(Icons.Default.History, "Sessions history") }
                IconButton(onDiagnostics) { Icon(Icons.Default.Info, "Diagnostics and about") }
                IconButton(onSettings) { Icon(Icons.Default.Settings, "Settings") }
            },
        )
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            runningAnalysis?.let { id ->
                OutlinedCard(Modifier.fillMaxWidth().clickable { onOpenAnalysis(id) }) {
                    Text("A video is being analysed. Tap to view progress.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            SourceSwitch(source, viewModel::select)
            Text("Today", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("Motorcycles", totals.motorcycles.toString(), Modifier.weight(1f))
                StatCard("Riders", totals.ridersTotal.toString(), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("Compliance", compliancePercent(totals.ridersHelmet, totals.ridersNoHelmet), Modifier.weight(1f))
                StatCard("AI alerts", alerts.toString(), Modifier.weight(1f))
            }
            OutlinedCard(Modifier.fillMaxWidth().clickable(onClick = onSync)) {
                Text(
                    if (unsynced == 0) "Everything is synced" else "$unsynced records waiting to sync",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            ActionButton("Live camera", Icons.Default.Videocam, onLive)
            ActionButton("Analyse a video", Icons.Default.VideoLibrary, onVideo)
        }
    }
}

@Composable
private fun ActionButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(18.dp))
            Text(label)
        }
    }
}
