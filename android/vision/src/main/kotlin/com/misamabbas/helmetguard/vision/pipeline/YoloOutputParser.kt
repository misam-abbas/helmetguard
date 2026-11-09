package com.misamabbas.helmetguard.vision.pipeline

import com.misamabbas.helmetguard.vision.geometry.Box

/** A detection in the model's own input pixels. */
data class RawDetection(val box: Box, val classId: Int, val confidence: Float)

object YoloOutputParser {
    /** NMS-free output (YOLO26): one row per detection of x1, y1, x2, y2, confidence, class. */
    fun parseEndToEnd(rows: Array<FloatArray>, inputSize: Int, minConfidence: Float): List<RawDetection> {
        val detections = rows.filter { it.size >= 6 && it[4] >= minConfidence }
        val normalized = detections.isNotEmpty() && detections.all { row -> (0..3).all { row[it] <= NORMALIZED_LIMIT } }
        val scale = if (normalized) inputSize.toFloat() else 1f
        return detections.map { RawDetection(Box(it[0] * scale, it[1] * scale, it[2] * scale, it[3] * scale), it[5].toInt(), it[4]) }
    }

    /** Older raw output: rows of cx, cy, w, h then one score per class. These need NMS. */
    fun parseRaw(channels: Array<FloatArray>, minConfidence: Float, iouThreshold: Float = 0.5f): List<RawDetection> {
        if (channels.size < 5) return emptyList()
        val found = ArrayList<RawDetection>()
        for (i in channels[0].indices) {
            var bestClass = 0
            var bestScore = 0f
            for (c in 4 until channels.size) {
                if (channels[c][i] > bestScore) {
                    bestScore = channels[c][i]
                    bestClass = c - 4
                }
            }
            if (bestScore >= minConfidence) {
                found += RawDetection(Box.fromCenter(channels[0][i], channels[1][i], channels[2][i], channels[3][i]), bestClass, bestScore)
            }
        }
        return nonMaxSuppression(found, iouThreshold)
    }

    fun nonMaxSuppression(detections: List<RawDetection>, iouThreshold: Float): List<RawDetection> {
        val kept = ArrayList<RawDetection>()
        for (candidate in detections.sortedByDescending { it.confidence }) {
            if (kept.none { it.classId == candidate.classId && it.box.iou(candidate.box) > iouThreshold }) kept += candidate
        }
        return kept
    }

    private const val NORMALIZED_LIMIT = 1.5f
}
