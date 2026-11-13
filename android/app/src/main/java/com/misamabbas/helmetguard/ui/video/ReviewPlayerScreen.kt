package com.misamabbas.helmetguard.ui.video

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.misamabbas.helmetguard.data.DetectionLog
import com.misamabbas.helmetguard.data.EvidenceStore
import com.misamabbas.helmetguard.data.db.AppDatabase
import com.misamabbas.helmetguard.pipeline.FrameOutput
import com.misamabbas.helmetguard.ui.components.EmptyMessage
import com.misamabbas.helmetguard.ui.components.drawDetections
import com.misamabbas.helmetguard.ui.violations.ViolationCard
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReviewViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    db: AppDatabase,
    val store: EvidenceStore,
) : ViewModel() {
    private val id = MutableStateFlow<String?>(null)
    val session = id.flatMapLatest { it?.let(db.sessions()::observe) ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val violations = id.flatMapLatest { value -> value?.let { db.violations().observeList(null, it, "") } ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _log = MutableStateFlow<DetectionLog?>(null)
    val log = _log.asStateFlow()

    fun load(sessionId: String) {
        id.value = sessionId
        viewModelScope.launch {
            _log.value = withContext(Dispatchers.IO) { DetectionLog.read(DetectionLog.fileFor(context.filesDir, sessionId)) }
        }
    }
}

/** Plays the original video with the stored boxes drawn in sync. */
@Composable
fun ReviewPlayerScreen(id: String, viewModel: ReviewViewModel = hiltViewModel()) {
    LaunchedEffect(id) { viewModel.load(id) }
    val session by viewModel.session.collectAsStateWithLifecycle()
    val violations by viewModel.violations.collectAsStateWithLifecycle()
    val log by viewModel.log.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(player) { onDispose { player.release() } }

    val uri = session?.videoUri
    LaunchedEffect(uri) {
        if (uri != null) {
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
        }
    }

    var positionMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(player) {
        while (true) {
            withFrameMillis { }
            positionMs = player.currentPosition
        }
    }

    val current = log
    val ratio = if (current != null && current.frameHeight > 0) current.frameWidth.toFloat() / current.frameHeight else 16f / 9f

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(session?.videoFileName ?: "Review video", style = MaterialTheme.typography.titleMedium, maxLines = 1)

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.heightIn(max = MAX_PLAYER_HEIGHT.dp).aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 1f)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { PlayerView(it).apply { this.player = player } },
                )
                if (current != null) {
                    val boxes = current.at(positionMs)
                    if (boxes.isNotEmpty()) {
                        val output = FrameOutput(current.frameWidth, current.frameHeight, boxes, 0, 0f, 0f)
                        Canvas(Modifier.fillMaxSize()) { drawDetections(output) }
                    }
                }
            }
        }

        if (uri != null && current == null) {
            Text("No stored boxes for this video.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (violations.isEmpty()) {
            EmptyMessage("No AI alerts in this video")
        } else {
            Text("Tap an alert to jump to it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(violations, key = { it.id }) { row ->
                    ViolationCard(row, viewModel.store, onOpen = {
                        row.videoTimestampMs?.let(player::seekTo)
                    })
                }
            }
        }
    }
}

private const val MAX_PLAYER_HEIGHT = 420
