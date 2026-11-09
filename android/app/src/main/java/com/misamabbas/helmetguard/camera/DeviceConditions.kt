package com.misamabbas.helmetguard.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.BatteryManager
import android.os.PowerManager
import androidx.camera.core.ImageProxy
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class HeatLevel(val label: String) { COOL("Cool"), WARM("Warm"), HOT("Hot"), SEVERE("Too hot") }

@Singleton
class DeviceConditions @Inject constructor(@ApplicationContext context: Context) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val battery = context.getSystemService(BatteryManager::class.java)

    fun heat(): HeatLevel = when (power.currentThermalStatus) {
        PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT -> HeatLevel.COOL
        PowerManager.THERMAL_STATUS_MODERATE -> HeatLevel.WARM
        PowerManager.THERMAL_STATUS_SEVERE -> HeatLevel.HOT
        else -> HeatLevel.SEVERE
    }

    fun batteryPercent(): Int = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    /** Slows the AI down when the phone is hot or the battery is low. */
    fun minFrameIntervalMs(): Long = when {
        heat() == HeatLevel.SEVERE -> 1_000
        heat() == HeatLevel.HOT -> 333
        batteryPercent() < LOW_BATTERY -> 200
        heat() == HeatLevel.WARM -> 125
        else -> 0
    }

    private companion object {
        const val LOW_BATTERY = 20
    }
}

/** Turns a camera frame into an upright bitmap, reusing the same memory every frame. */
class FrameConverter {
    private var raw: Bitmap? = null
    private var upright: Bitmap? = null
    private var canvas: Canvas? = null
    private val matrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun convert(image: ImageProxy): Bitmap {
        val plane = image.planes[0]
        val stride = plane.rowStride / plane.pixelStride
        val source = raw?.takeIf { it.width == stride && it.height == image.height }
            ?: Bitmap.createBitmap(stride, image.height, Bitmap.Config.ARGB_8888).also { raw = it }
        plane.buffer.rewind()
        source.copyPixelsFromBuffer(plane.buffer)
        return rotate(source, image.width, image.height, image.imageInfo.rotationDegrees)
    }

    fun rotate(source: Bitmap, width: Int, height: Int, degrees: Int): Bitmap {
        val swap = degrees == 90 || degrees == 270
        val targetW = if (swap) height else width
        val targetH = if (swap) width else height
        val target = upright?.takeIf { it.width == targetW && it.height == targetH }
            ?: Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888).also {
                upright = it
                canvas = Canvas(it)
            }
        matrix.reset()
        matrix.postTranslate(-width / 2f, -height / 2f)
        matrix.postRotate(degrees.toFloat())
        matrix.postTranslate(targetW / 2f, targetH / 2f)
        canvas!!.drawBitmap(source, matrix, paint)
        return target
    }
}
