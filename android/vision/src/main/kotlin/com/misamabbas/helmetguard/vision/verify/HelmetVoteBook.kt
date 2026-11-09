package com.misamabbas.helmetguard.vision.verify

import com.misamabbas.helmetguard.vision.geometry.Box
import kotlin.math.abs
import kotlin.math.hypot

enum class HeadState { HELMET, NO_HELMET }
enum class RiderRole { DRIVER, PILLION }
enum class RiderPolicy { ALL_RIDERS, DRIVER_ONLY }

data class VerifierSettings(
    val minTrackFrames: Int = 3,
    val minTrackAverageConfidence: Float = 0.50f,
    val minNoHelmetObservations: Int = 5,
    val minNoHelmetConfidence: Float = 0.55f,
    val minNoHelmetShare: Float = 0.70f,
    val minNoHelmetSpanMs: Long = 500,
    val minHelmetObservations: Int = 5,
    val finaliseAfterMs: Long = 3_000,
    val riderPolicy: RiderPolicy = RiderPolicy.ALL_RIDERS,
)

/**
 * One rider seen in one frame. [box] is relative to the motorcycle box (0..1), so
 * the same person stays near the same position as the bike moves across the frame.
 */
data class RiderObservation(
    val box: Box,
    val head: HeadState?,
    val confidence: Float,
    val timestampMs: Long,
)

data class RiderVerdict(
    val riderIndex: Int,
    val role: RiderRole,
    val status: RiderStatus,
    val confidence: Float,
    val noHelmetFrames: Int,
    val decidedFrames: Int,
    /** Where this rider last sat, relative to the motorcycle box. */
    val relativeBox: Box,
)

enum class RiderStatus { HELMET, NO_HELMET, UNKNOWN }

/** Keeps each rider's own vote history inside one motorcycle's track. */
class HelmetVoteBook(private val settings: VerifierSettings = VerifierSettings()) {
    private class Rider(val index: Int, var box: Box) {
        var seen = 0
        var areaSum = 0f
        var noHelmetCount = 0
        var noHelmetConfidenceSum = 0f
        var helmetCount = 0
        var helmetConfidenceSum = 0f
        var firstNoHelmetMs = Long.MAX_VALUE
        var lastNoHelmetMs = Long.MIN_VALUE
        val averageArea: Float get() = if (seen == 0) 0f else areaSum / seen
        val decided: Int get() = noHelmetCount + helmetCount
    }

    private val riders = mutableListOf<Rider>()

    /** True once any rider has a qualifying bare-head observation. */
    val anyNoHelmet: Boolean get() = riders.any { it.noHelmetCount > 0 }
    val riderCount: Int get() = riders.size

    fun observe(observations: List<RiderObservation>) {
        val free = riders.toMutableList()
        for (observation in observations.sortedByDescending { it.box.area }) {
            val rider = free.minByOrNull { distance(it.box, observation.box) }
                ?.takeIf { distance(it.box, observation.box) <= SAME_RIDER_DISTANCE }
                ?: Rider(riders.size, observation.box).also { riders += it }
            free.remove(rider)
            rider.box = observation.box
            rider.seen += 1
            rider.areaSum += observation.box.area
            record(rider, observation)
        }
    }

    private fun record(rider: Rider, observation: RiderObservation) {
        if (observation.confidence < settings.minNoHelmetConfidence) return
        when (observation.head) {
            HeadState.NO_HELMET -> {
                rider.noHelmetCount += 1
                rider.noHelmetConfidenceSum += observation.confidence
                rider.firstNoHelmetMs = minOf(rider.firstNoHelmetMs, observation.timestampMs)
                rider.lastNoHelmetMs = maxOf(rider.lastNoHelmetMs, observation.timestampMs)
            }
            HeadState.HELMET -> {
                rider.helmetCount += 1
                rider.helmetConfidenceSum += observation.confidence
            }
            null -> Unit
        }
    }

    private fun meetsRules(rider: Rider): Boolean =
        rider.noHelmetCount >= settings.minNoHelmetObservations &&
            rider.noHelmetCount.toFloat() / rider.decided >= settings.minNoHelmetShare &&
            rider.lastNoHelmetMs - rider.firstNoHelmetMs >= settings.minNoHelmetSpanMs

    private fun roles(motion: BikeMotion): Map<Int, RiderRole> {
        val driver = chooseDriver(riders, motion) ?: return emptyMap()
        return riders.associate { it.index to if (it === driver) RiderRole.DRIVER else RiderRole.PILLION }
    }

    /**
     * The driver sits at the front of the bike. Moving sideways, that is the rider
     * leading the motion. Coming toward the camera the driver is nearest and so
     * largest; going away the pillion is nearest, so the driver is smallest.
     */
    private fun chooseDriver(list: List<Rider>, motion: BikeMotion): Rider? {
        if (list.size <= 1) return list.firstOrNull()
        return when {
            abs(motion.velocityX) >= SIDEWAYS_SPEED ->
                if (motion.velocityX > 0) list.maxByOrNull { it.box.centerX } else list.minByOrNull { it.box.centerX }
            motion.growthRate >= 0 -> list.maxByOrNull { it.averageArea }
            else -> list.minByOrNull { it.averageArea }
        }
    }

    fun isHelmetMajority(): Boolean {
        if (riders.isEmpty() || riders.any { it.noHelmetCount > 0 && it.helmetCount < it.noHelmetCount }) return false
        val decided = riders.filter { it.decided > 0 }
        return decided.isNotEmpty() && decided.all {
            it.helmetCount >= settings.minHelmetObservations &&
                it.helmetCount.toFloat() / it.decided >= settings.minNoHelmetShare
        }
    }

    /** True when a rider covered by the rider policy meets every confirmation rule. */
    fun hasConfirmedRider(motion: BikeMotion): Boolean {
        val roleOf = roles(motion)
        return riders.any { rider ->
            meetsRules(rider) && (settings.riderPolicy == RiderPolicy.ALL_RIDERS || roleOf[rider.index] == RiderRole.DRIVER)
        }
    }

    fun verdicts(motion: BikeMotion): List<RiderVerdict> {
        val roleOf = roles(motion)
        return riders.map { rider ->
            val status = when {
                meetsRules(rider) -> RiderStatus.NO_HELMET
                rider.helmetCount >= settings.minHelmetObservations && rider.helmetCount.toFloat() / rider.decided >= settings.minNoHelmetShare -> RiderStatus.HELMET
                else -> RiderStatus.UNKNOWN
            }
            val confidence = when (status) {
                RiderStatus.NO_HELMET -> rider.noHelmetConfidenceSum / rider.noHelmetCount
                RiderStatus.HELMET -> rider.helmetConfidenceSum / rider.helmetCount
                RiderStatus.UNKNOWN -> 0f
            }
            RiderVerdict(rider.index, roleOf[rider.index] ?: RiderRole.DRIVER, status, confidence, rider.noHelmetCount, rider.decided, rider.box)
        }
    }

    private fun distance(a: Box, b: Box) = hypot(a.centerX - b.centerX, a.centerY - b.centerY)

    private companion object {
        const val SAME_RIDER_DISTANCE = 0.30f
        const val SIDEWAYS_SPEED = 40f
    }
}

/** Pixels per second, taken from the motorcycle's track. */
data class BikeMotion(val velocityX: Float, val growthRate: Float)
