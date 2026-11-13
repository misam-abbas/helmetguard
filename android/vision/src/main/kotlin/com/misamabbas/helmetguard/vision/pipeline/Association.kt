package com.misamabbas.helmetguard.vision.pipeline

import com.misamabbas.helmetguard.vision.geometry.Box
import com.misamabbas.helmetguard.vision.verify.HeadState

enum class DetailKind { RIDER, HELMET, NO_HELMET }

data class DetailDetection(val box: Box, val kind: DetailKind, val confidence: Float)

data class AssociatedRider(
    val riderBox: Box,
    val headBox: Box?,
    val head: HeadState?,
    val headConfidence: Float,
)

data class BikeDetails(val riders: List<AssociatedRider>)

object Association {
    /**
     * Assigns heads to riders inside one motorcycle. Anything centred outside the
     * motorcycle box belongs to a neighbouring bike and is discarded.
     */
    fun associate(motorcycle: Box, detections: List<DetailDetection>): BikeDetails {
        val inside = detections.filter { motorcycle.contains(it.box.centerX, it.box.centerY) }
        val riderBoxes = inside.filter { it.kind == DetailKind.RIDER }.sortedByDescending { it.confidence }
        val heads = inside.filter { it.kind == DetailKind.HELMET || it.kind == DetailKind.NO_HELMET }
            .sortedByDescending { it.confidence }

        val taken = HashSet<Int>()
        val riders = ArrayList<AssociatedRider>()

        for (rider in riderBoxes) {
            val headIndex = heads.indices.firstOrNull { index ->
                index !in taken && rider.box.contains(heads[index].box.centerX, heads[index].box.centerY)
            }
            if (headIndex != null) taken += headIndex
            val head = headIndex?.let { heads[it] }
            riders += AssociatedRider(rider.box, head?.box, head?.toHeadState(), head?.confidence ?: 0f)
        }

        // A head the detector saw without a rider box is still a person on this bike.
        heads.forEachIndexed { index, head ->
            if (index !in taken && riders.none { it.riderBox.contains(head.box.centerX, head.box.centerY) }) {
                riders += AssociatedRider(head.box, head.box, head.toHeadState(), head.confidence)
            }
        }

        return BikeDetails(riders)
    }

    private fun DetailDetection.toHeadState() = if (kind == DetailKind.HELMET) HeadState.HELMET else HeadState.NO_HELMET
}

/** One track competing for the stage-2 detector this frame. */
data class DetailCandidate(val trackId: Int, val lastDetailFrame: Long, val undecided: Boolean, val inEvidenceZone: Boolean)

object DetailScheduler {
    /** At most [maxCrops] per frame, each track every second frame, undecided tracks first. */
    fun select(candidates: List<DetailCandidate>, frameIndex: Long, maxCrops: Int = 3, everyNthFrame: Int = 2): List<Int> =
        candidates
            .filter { frameIndex - it.lastDetailFrame >= everyNthFrame }
            .sortedWith(
                compareByDescending<DetailCandidate> { it.undecided }
                    .thenByDescending { it.inEvidenceZone }
                    .thenBy { it.lastDetailFrame },
            )
            .take(maxCrops)
            .map { it.trackId }
}
