package com.misamabbas.helmetguard.vision.geometry

import kotlin.math.max
import kotlin.math.min

/** An axis-aligned box in frame pixels. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = max(0f, right - left)
    val height: Float get() = max(0f, bottom - top)
    val area: Float get() = width * height
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun iou(other: Box): Float {
        val overlapW = min(right, other.right) - max(left, other.left)
        val overlapH = min(bottom, other.bottom) - max(top, other.top)
        if (overlapW <= 0f || overlapH <= 0f) return 0f
        val overlap = overlapW * overlapH
        return overlap / (area + other.area - overlap)
    }

    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

    /** This box expressed as 0..1 positions inside [parent]. */
    fun relativeTo(parent: Box): Box {
        val w = parent.width.takeIf { it > 0f } ?: 1f
        val h = parent.height.takeIf { it > 0f } ?: 1f
        return Box((left - parent.left) / w, (top - parent.top) / h, (right - parent.left) / w, (bottom - parent.top) / h)
    }

    fun touchesEdge(frameWidth: Float, frameHeight: Float, marginPx: Float = 2f): Boolean =
        left <= marginPx || top <= marginPx || right >= frameWidth - marginPx || bottom >= frameHeight - marginPx

    companion object {
        fun fromCenter(cx: Float, cy: Float, w: Float, h: Float) = Box(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }
}
