package com.misamabbas.helmetguard.vision.tracker

import com.misamabbas.helmetguard.vision.geometry.Box
import kotlin.math.max

/**
 * Constant-velocity filter for one box, kept as four independent position and
 * velocity pairs. Works with uneven frame gaps because every step takes a real
 * time delta, which matters when the phone skips frames under load.
 */
class KalmanBoxFilter(initial: Box, timestampMs: Long) {
    private val channels = arrayOf(
        Channel(initial.centerX),
        Channel(initial.centerY),
        Channel(initial.width),
        Channel(initial.height),
    )
    private var lastMs = timestampMs

    /** Advances the state to [timestampMs] and returns the predicted box. */
    fun predict(timestampMs: Long): Box {
        val dt = max(0L, timestampMs - lastMs) / 1000f
        channels.forEach { it.predict(dt) }
        lastMs = timestampMs
        return current()
    }

    fun update(measured: Box) {
        channels[0].update(measured.centerX)
        channels[1].update(measured.centerY)
        channels[2].update(measured.width)
        channels[3].update(measured.height)
    }

    fun current(): Box = Box.fromCenter(
        channels[0].position,
        channels[1].position,
        max(1f, channels[2].position),
        max(1f, channels[3].position),
    )

    /** The box this filter expects at [futureMs], without changing its state. */
    fun peek(futureMs: Long): Box {
        val dt = max(0L, futureMs - lastMs) / 1000f
        return Box.fromCenter(
            channels[0].position + channels[0].velocity * dt,
            channels[1].position + channels[1].velocity * dt,
            max(1f, channels[2].position + channels[2].velocity * dt),
            max(1f, channels[3].position + channels[3].velocity * dt),
        )
    }

    val velocityX: Float get() = channels[0].velocity
    val velocityY: Float get() = channels[1].velocity
    val growthRate: Float get() = channels[3].velocity

    private class Channel(var position: Float) {
        var velocity = 0f
        private var p00 = 100f
        private var p01 = 0f
        private var p10 = 0f
        private var p11 = 1000f

        fun predict(dt: Float) {
            position += velocity * dt
            val newP00 = p00 + dt * (p10 + p01) + dt * dt * p11 + PROCESS_POSITION
            val newP01 = p01 + dt * p11
            val newP10 = p10 + dt * p11
            val newP11 = p11 + PROCESS_VELOCITY
            p00 = newP00
            p01 = newP01
            p10 = newP10
            p11 = newP11
        }

        fun update(measurement: Float) {
            val innovation = measurement - position
            val s = p00 + MEASUREMENT_NOISE
            val k0 = p00 / s
            val k1 = p10 / s
            position += k0 * innovation
            velocity += k1 * innovation
            val newP00 = (1 - k0) * p00
            val newP01 = (1 - k0) * p01
            val newP10 = p10 - k1 * p00
            val newP11 = p11 - k1 * p01
            p00 = newP00
            p01 = newP01
            p10 = newP10
            p11 = newP11
        }
    }

    private companion object {
        const val PROCESS_POSITION = 1f
        const val PROCESS_VELOCITY = 20f
        const val MEASUREMENT_NOISE = 16f
    }
}
