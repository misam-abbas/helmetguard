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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import com.misamabbas.helmetguard.sync.SyncScheduler
import com.misamabbas.helmetguard.ui.components.SourceSwitch
import com.misamabbas.helmetguard.ui.components.StatCard
import com.misamabbas.helmetguard.ui.components.compliancePercent
import com.misamabbas.helmetguard.video.VideoAnalysisManager
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val db: AppDatabase,
    analysis: VideoAnalysisManager,
    private val client: SupabaseClient?,
    sync: SyncScheduler,
) : ViewModel() {
    /** Unfinished analysis in the background, so the user can get back to it. */
    val runningAnalysis = analysis.ui.map { state -> state.sessionId?.takeIf { !state.finished && analysis.isRunning } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val source = settings.sourceFilter.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SourceFilter.ALL)
    private val _period = MutableStateFlow(Period.TODAY)
    val period = _period.asStateFlow()

    /** Every minute while the screen is open: new alerts from other devices show up, and today rolls over at midnight. */
    private val ticks = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(REFRESH_MS)
        }
    }

    /** This phone's own totals for the period, shown when the server can't be reached. */
    private val local: Flow<HomeStats> = combine(_period, source, ticks) { p, s, now -> Triple(p, s, p.since(now)) }
        .distinctUntilChanged()
        .flatMapLatest { (_, s, since) ->
            combine(db.sessions().observeTotals(since, s.dbValue), db.violations().observeCountSince(since, s.dbValue)) { totals, alerts ->
                HomeStats.local(totals, alerts)
            }
        }

    /** The website's totals for the period, fetched again each minute and when a sync has finished. */
    private val server: Flow<Pair<Pair<Period, SourceFilter>, HomeStats>?> =
        combine(_period, source, ticks, sync.isRunning.onStart { emit(false) }) { p, s, _, syncing -> Triple(p, s, syncing) }
            .filter { (_, _, syncing) -> !syncing }
            .mapLatest { (p, s) -> fetch(p, s)?.let { (p to s) to it } }

    val stats = combine(_period, source, local, server.onStart { emit(null) }) { p, s, phone, fromServer ->
        fromServer?.takeIf { it.first == (p to s) }?.second ?: phone
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeStats.EMPTY)

    val unsynced = db.violations().observeUnsyncedCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    init {
        // Send anything waiting first, so the server's totals include this phone's latest records.
        sync.syncSoon()
    }

    fun select(value: SourceFilter) {
        viewModelScope.launch { settings.setSourceFilter(value) }
    }

    fun select(value: Period) {
        _period.value = value
    }

    /** The same database function as the website's dashboard, with the same period, so the numbers match. */
    private suspend fun fetch(period: Period, source: SourceFilter): HomeStats? {
        val supabase = client ?: return null
        return try {
            supabase.auth.awaitInitialization()
            if (supabase.auth.currentSessionOrNull() == null) return null
            val now = System.currentTimeMillis()
            val summary = supabase.postgrest.rpc("dashboard_summary", buildJsonObject {
                put("p_from", Instant.ofEpochMilli(period.since(now)).toString())
                // Like the website, nothing stamped a little after now is cut off.
                put("p_to", Instant.ofEpochMilli(now + Period.DAY_MS).toString())
                source.dbValue?.let { put("p_source", it) }
            }).decodeAs<JsonObject>()
            HomeStats.fromSummary(summary)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline or signed out: the phone's own totals are shown instead, and say so.
            null
        }
    }

    private companion object {
        const val REFRESH_MS = 60_000L
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
    val period by viewModel.period.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
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
            PeriodSwitch(period, viewModel::select)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("Motorcycles", stats.motorcycles.toString(), Modifier.weight(1f))
                StatCard("Riders", stats.ridersTotal.toString(), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("Compliance", compliancePercent(stats.ridersHelmet, stats.ridersNoHelmet), Modifier.weight(1f))
                StatCard("AI alerts", stats.alerts.toString(), Modifier.weight(1f))
            }
            Text(
                if (stats.fromServer) {
                    "The same totals as the website's dashboard: every synced device and the website's video analyses."
                } else {
                    "This phone only: the server can't be reached, so other devices and the website's analyses are left out."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

/** Today, 7 days or 30 days: the same choice as on the website's dashboard. */
@Composable
private fun PeriodSwitch(selected: Period, onSelect: (Period) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        Period.entries.forEachIndexed { index, value ->
            SegmentedButton(
                selected = selected == value,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index, Period.entries.size),
                label = { Text(value.label, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
            )
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
