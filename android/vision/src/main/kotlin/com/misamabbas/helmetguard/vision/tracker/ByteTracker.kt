package com.misamabbas.helmetguard.vision.tracker

import com.misamabbas.helmetguard.vision.geometry.Box

data class Detection(val box: Box, val confidence: Float)

data class TrackerSettings(
    /** Detections at or above this start tracks and are matched first. */
    val highConfidence: Float = 0.50f,
    /** Weaker detections only extend tracks that already exist (the ByteTrack idea). */
    val lowConfidence: Float = 0.35f,
    val matchIou: Float = 0.25f,
    val secondPassIou: Float = 0.40f,
    /** A track counts only after it is seen in this many frames. */
    val confirmHits: Int = 3,
    /** A motorcycle has left once it is unseen for this long. */
    val lostAfterMs: Long = 1_000,
    /** An unconfirmed track is dropped much sooner, so noise never lingers. */
    val tentativeLostAfterMs: Long = 400,
)

/** A read-only snapshot of a track. */
data class Track(
    val id: Int,
    val box: Box,
    val confidence: Float,
    val averageConfidence: Float,
    val hits: Int,
    val confirmed: Boolean,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val velocityX: Float,
    val velocityY: Float,
    val growthRate: Float,
)

data class TrackerResult(
    /** Confirmed tracks seen or predicted in this frame. */
    val active: List<Track>,
    /** Confirmed tracks that ended in this frame. */
    val ended: List<Track>,
)

class ByteTracker(private val settings: TrackerSettings = TrackerSettings()) {
    private class Internal(
        val id: Int,
        val filter: KalmanBoxFilter,
        val firstSeenMs: Long,
        var lastSeenMs: Long,
        var confidence: Float,
    ) {
        var hits = 1
        var confidenceSum = confidence
        var confirmed = false
        var predicted: Box = filter.current()
    }

    private val tracks = mutableListOf<Internal>()
    private var nextId = 1

    fun update(detections: List<Detection>, timestampMs: Long): TrackerResult {
        tracks.forEach { it.predicted = it.filter.predict(timestampMs) }

        val high = detections.filter { it.confidence >= settings.highConfidence }.toMutableList()
        val low = detections.filter { it.confidence >= settings.lowConfidence && it.confidence < settings.highConfidence }.toMutableList()

        val unmatched = tracks.toMutableList()
        match(unmatched, high, settings.matchIou, timestampMs)
        // Second pass: weak detections keep existing tracks alive through motion blur.
        match(unmatched, low, settings.secondPassIou, timestampMs)

        high.forEach { detection ->
            tracks += Internal(nextId++, KalmanBoxFilter(detection.box, timestampMs), timestampMs, timestampMs, detection.confidence)
        }

        val ended = mutableListOf<Track>()
        val iterator = tracks.iterator()
        while (iterator.hasNext()) {
            val track = iterator.next()
            val unseenFor = timestampMs - track.lastSeenMs
            val limit = if (track.confirmed) settings.lostAfterMs else settings.tentativeLostAfterMs
            if (unseenFor > limit) {
                if (track.confirmed) ended += track.snapshot()
                iterator.remove()
            }
        }

        return TrackerResult(tracks.filter { it.confirmed }.map { it.snapshot() }, ended)
    }

    /** Ends every remaining track, for example when a session stops. */
    fun flush(): List<Track> {
        val remaining = tracks.filter { it.confirmed }.map { it.snapshot() }
        tracks.clear()
        return remaining
    }

    /** Where a track should be at [timestampMs], so boxes glide between processed frames. */
    fun predictedBox(trackId: Int, timestampMs: Long): Box? =
        tracks.firstOrNull { it.id == trackId }?.filter?.peek(timestampMs)

    /** Greedy best-overlap-first matching: small, predictable and easy to unit test. */
    private fun match(unmatched: MutableList<Internal>, detections: MutableList<Detection>, minIou: Float, timestampMs: Long) {
        if (unmatched.isEmpty() || detections.isEmpty()) return

        data class Pair(val track: Internal, val detection: Detection, val iou: Float)

        val pairs = unmatched.flatMap { track ->
            detections.mapNotNull { detection ->
                val iou = track.predicted.iou(detection.box)
                if (iou >= minIou) Pair(track, detection, iou) else null
            }
        }.sortedByDescending { it.iou }

        val usedTracks = HashSet<Int>()
        val usedDetections = HashSet<Detection>()
        for (pair in pairs) {
            if (pair.track.id in usedTracks || pair.detection in usedDetections) continue
            usedTracks += pair.track.id
            usedDetections += pair.detection
            apply(pair.track, pair.detection, timestampMs)
        }

        unmatched.removeAll { it.id in usedTracks }
        detections.removeAll(usedDetections)
    }

    private fun apply(track: Internal, detection: Detection, timestampMs: Long) {
        track.filter.update(detection.box)
        track.lastSeenMs = timestampMs
        track.confidence = detection.confidence
        track.hits += 1
        track.confidenceSum += detection.confidence
        if (track.hits >= settings.confirmHits) track.confirmed = true
    }

    private fun Internal.snapshot() = Track(
        id = id,
        box = filter.current(),
        confidence = confidence,
        averageConfidence = confidenceSum / hits,
        hits = hits,
        confirmed = confirmed,
        firstSeenMs = firstSeenMs,
        lastSeenMs = lastSeenMs,
        velocityX = filter.velocityX,
        velocityY = filter.velocityY,
        growthRate = filter.growthRate,
    )
}
