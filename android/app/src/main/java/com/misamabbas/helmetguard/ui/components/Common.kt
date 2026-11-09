package com.misamabbas.helmetguard.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.misamabbas.helmetguard.data.SourceFilter
import com.misamabbas.helmetguard.data.db.SyncState
import com.misamabbas.helmetguard.ui.theme.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateFormat = DateTimeFormatter.ofPattern("dd MMM, HH:mm").withZone(ZoneId.systemDefault())

fun formatTime(ms: Long): String = dateFormat.format(Instant.ofEpochMilli(ms))

fun formatClip(ms: Long?): String {
    if (ms == null) return "—"
    val total = ms / 1000
    return "%02d:%02d".format(total / 60, total % 60)
}

fun compliancePercent(helmet: Int, noHelmet: Int): String =
    if (helmet + noHelmet == 0) "—" else "%.0f%%".format(100f * helmet / (helmet + noHelmet))

@Composable
fun SourceSwitch(selected: SourceFilter, onSelect: (SourceFilter) -> Unit, modifier: Modifier = Modifier) {
    val options = listOf(SourceFilter.LIVE to "Live camera", SourceFilter.VIDEO to "Prerecorded", SourceFilter.ALL to "All")
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = selected == value,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                label = { Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
            )
        }
    }
}

@Composable
fun SourceTag(source: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(8.dp).background(if (source == "live") Status.Live else Status.Video, CircleShape))
        Text(if (source == "live") "Live" else "Video", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun Pill(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = color,
        modifier = Modifier.background(color.copy(alpha = 0.14f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** Every record carries this until a person reviews it on the dashboard. */
@Composable
fun UnverifiedBadge() = Pill("AI alert – unverified", Status.NoHelmet)

@Composable
fun SyncBadge(state: String) = when (state) {
    SyncState.SYNCED -> Pill("Synced", Status.Helmet)
    SyncState.NEEDS_ATTENTION -> Pill("Needs attention", Status.NoHelmet)
    SyncState.RETRYING -> Pill("Retrying", Status.Undecided)
    SyncState.UPLOADING_FILES, SyncState.FILES_DONE -> Pill("Uploading", Status.Undecided)
    SyncState.LOCAL_ONLY -> Pill("Saving", Status.Undecided)
    else -> Pill("Not synced", Status.Undecided)
}

@Composable
fun PlateText(text: String?, status: String) {
    if (text != null) {
        Text(text, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
    } else {
        Text(
            if (status == "no_plate_detected") "No plate seen" else "Unreadable",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    OutlinedCard(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun EmptyMessage(title: String, body: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (body != null) Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Loads a private evidence image off the main thread, scaled down for lists. */
@Composable
fun LocalImage(file: File?, description: String, modifier: Modifier = Modifier, maxSize: Int = 1280, contentScale: ContentScale = ContentScale.Crop) {
    val image: ImageBitmap? by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) {
            file?.takeIf { it.exists() }?.let { decode(it, maxSize) }
        }
    }
    val current = image
    if (current != null) {
        Image(current, description, modifier.clip(RoundedCornerShape(6.dp)), contentScale = contentScale)
    } else {
        Box(modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

private fun decode(file: File, maxSize: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (bounds.outWidth / sample > maxSize || bounds.outHeight / sample > maxSize) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}
