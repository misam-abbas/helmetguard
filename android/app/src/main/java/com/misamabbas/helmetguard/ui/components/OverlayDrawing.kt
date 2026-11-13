package com.misamabbas.helmetguard.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.misamabbas.helmetguard.pipeline.FrameOutput
import com.misamabbas.helmetguard.pipeline.OverlayKind
import com.misamabbas.helmetguard.ui.theme.Status
import kotlin.math.min

/**
 * Draws detection boxes over a video shown fitted and centred in this canvas.
 * [glideMs] moves each box along its predicted path, so the overlay looks smooth
 * between processed frames.
 */
fun DrawScope.drawDetections(output: FrameOutput, glideMs: Long = 0) {
    val scale = min(size.width / output.frameWidth, size.height / output.frameHeight)
    val offsetX = (size.width - output.frameWidth * scale) / 2f
    val offsetY = (size.height - output.frameHeight * scale) / 2f
    val seconds = glideMs / 1000f

    output.boxes.forEach { box ->
        val color = when (box.kind) {
            OverlayKind.HELMET -> Status.Helmet
            OverlayKind.NO_HELMET -> Status.NoHelmet
            OverlayKind.UNDECIDED -> Status.Undecided
            OverlayKind.MOTORCYCLE -> Color.White
        }
        val topLeft = Offset(
            offsetX + (box.box.left + box.velocityX * seconds) * scale,
            offsetY + (box.box.top + box.velocityY * seconds) * scale,
        )
        drawRect(color, topLeft, Size(box.box.width * scale, box.box.height * scale), style = Stroke(4f))
        drawLabel(box.label, topLeft, color)
    }
}

fun DrawScope.drawLabel(text: String, at: Offset, color: Color) {
    val paint = android.graphics.Paint().apply {
        this.color = android.graphics.Color.WHITE
        textSize = 30f
        isAntiAlias = true
    }
    val background = android.graphics.Paint().apply { this.color = color.toArgb() }
    val width = paint.measureText(text)
    val top = (at.y - 38f).coerceAtLeast(0f)
    drawContext.canvas.nativeCanvas.apply {
        drawRect(at.x, top, at.x + width + 12f, top + 38f, background)
        drawText(text, at.x + 6f, top + 29f, paint)
    }
}
