package com.misamabbas.helmetguard.vision.verify

import com.misamabbas.helmetguard.vision.geometry.Box
import kotlin.math.min
import kotlin.math.sqrt

/** A confirmed violation remembered briefly, to catch a bike the tracker re-identified. */
data class RecentEvent(
    val violationId: String,
    val timestampMs: Long,
    val lastBox: Box,
    val velocityX: Float,
    val velocityY: Float,
    val colorHistogram: FloatArray?,
)

data class Candidate(
    val timestampMs: Long,
    val box: Box,
    val colorHistogram: FloatArray?,
)

data class DedupSettings(
    val windowMs: Long = 10_000,
    val positionIou: Float = 0.30f,
    /** Position stops being meaningful quickly, because the next bike takes the same lane. */
    val positionWindowMs: Long = 2_000,
    val colorSimilarity: Float = 0.95f,
)

/**
 * Layers two and three of duplicate prevention: a new confirmation inside the window
 * merges into an existing event when its predicted position or its colour agrees.
 * Number plates are not read, so they play no part.
 */
class Deduplicator(private val settings: DedupSettings = DedupSettings()) {
    private val recent = ArrayDeque<RecentEvent>()

    /** The existing violation ID this candidate belongs to, or null if it is new. */
    fun findDuplicate(candidate: Candidate): String? {
        expire(candidate.timestampMs)
        return recent.firstOrNull { matches(it, candidate) }?.violationId
    }

    fun remember(event: RecentEvent) {
        recent.addLast(event)
    }

    private fun expire(nowMs: Long) {
        while (recent.isNotEmpty() && nowMs - recent.first().timestampMs > settings.windowMs) recent.removeFirst()
    }

    private fun matches(event: RecentEvent, candidate: Candidate): Boolean {
        val dtMs = candidate.timestampMs - event.timestampMs
        if (dtMs <= settings.positionWindowMs) {
            val seconds = dtMs / 1000f
            val predicted = Box(
                event.lastBox.left + event.velocityX * seconds,
                event.lastBox.top + event.velocityY * seconds,
                event.lastBox.right + event.velocityX * seconds,
                event.lastBox.bottom + event.velocityY * seconds,
            )
            if (predicted.iou(candidate.box) >= settings.positionIou) return true
        }

        val h1 = event.colorHistogram
        val h2 = candidate.colorHistogram
        return h1 != null && h2 != null && cosine(h1, h2) >= settings.colorSimilarity
    }

    companion object {
        fun cosine(a: FloatArray, b: FloatArray): Float {
            val n = min(a.size, b.size)
            var dot = 0f
            var magA = 0f
            var magB = 0f
            for (k in 0 until n) {
                dot += a[k] * b[k]
                magA += a[k] * a[k]
                magB += b[k] * b[k]
            }
            return if (magA == 0f || magB == 0f) 0f else dot / (sqrt(magA) * sqrt(magB))
        }
    }
}
