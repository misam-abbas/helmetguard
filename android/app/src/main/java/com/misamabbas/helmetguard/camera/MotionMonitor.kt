package com.misamabbas.helmetguard.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.sqrt

/** How much the phone moved during a session, in radians per second. */
data class MotionStats(val rms: Float, val peak: Float, val samples: Int) {
    /** Adds these numbers to a session's settings, so shaky sessions can be filtered out. */
    fun mergeInto(settingsJson: String): String {
        val existing = runCatching { Json.parseToJsonElement(settingsJson).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        return JsonObject(
            existing + mapOf(
                "motion_rms_rad_s" to JsonPrimitive(rms),
                "motion_peak_rad_s" to JsonPrimitive(peak),
                "motion_samples" to JsonPrimitive(samples),
            ),
        ).toString()
    }
}

/** Reads the gyroscope during a live session. A tripod should score close to zero. */
@Singleton
class MotionMonitor @Inject constructor(@ApplicationContext context: Context) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private var sumSquares = 0.0
    private var peak = 0f
    private var samples = 0

    fun start() {
        sumSquares = 0.0
        peak = 0f
        samples = 0
        sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop(): MotionStats {
        sensors.unregisterListener(this)
        val rms = if (samples == 0) 0f else sqrt(sumSquares / samples).toFloat()
        return MotionStats(rms, peak, samples)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = event.values
        val squared = x * x + y * y + z * z
        sumSquares += squared
        peak = max(peak, sqrt(squared))
        samples++
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
