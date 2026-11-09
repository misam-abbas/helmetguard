package com.misamabbas.helmetguard.vision.evidence

import com.misamabbas.helmetguard.vision.geometry.Box
import kotlin.math.max
import kotlin.math.min

data class FrameQualityInput(
    val motorcycle: Box,
    val frameWidth: Float,
    val frameHeight: Float,
    /** Variance of the Laplacian of the crop, from OpenCV. */
    val sharpness: Double,
    val confidence: Float,
    val plateVisible: Boolean,
    /** Largest overlap (IoU) with any other motorcycle in the frame. */
    val overlapWithOther: Float,
)

object FrameQualityScorer {
    private const val FULL_SIZE_SHARE = 0.25f
    private const val FULL_SHARPNESS = 500.0
    private const val EDGE_PENALTY = 0.25f
    private const val OVERLAP_PENALTY = 0.30f

    /** Higher is better. Weights come from section 5 of the plan. */
    fun score(input: FrameQualityInput): Float {
        val share = input.motorcycle.area / max(1f, input.frameWidth * input.frameHeight)
        val size = min(1f, share / FULL_SIZE_SHARE)
        val sharp = min(1.0, input.sharpness / FULL_SHARPNESS).toFloat()
        val plate = if (input.plateVisible) 1f else 0f

        var score = 0.35f * size + 0.25f * sharp + 0.20f * input.confidence + 0.20f * plate
        if (input.motorcycle.touchesEdge(input.frameWidth, input.frameHeight)) score -= EDGE_PENALTY
        score -= OVERLAP_PENALTY * input.overlapWithOther
        return score
    }
}

/** Keeps only the best [capacity] items, evicting the lowest score. */
class BestFrames<T>(private val capacity: Int = 3, private val onEvict: (T) -> Unit = {}) {
    private data class Entry<T>(val score: Float, val item: T)

    private val entries = mutableListOf<Entry<T>>()

    /** Returns true if [item] was kept. */
    fun offer(score: Float, item: T): Boolean {
        if (entries.size < capacity) {
            entries += Entry(score, item)
            entries.sortByDescending { it.score }
            return true
        }
        if (score <= entries.last().score) {
            onEvict(item)
            return false
        }
        onEvict(entries.removeAt(entries.lastIndex).item)
        entries += Entry(score, item)
        entries.sortByDescending { it.score }
        return true
    }

    fun best(): List<T> = entries.map { it.item }
    fun bestScore(): Float? = entries.firstOrNull()?.score
}
