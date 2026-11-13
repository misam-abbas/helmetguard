package com.misamabbas.helmetguard.vision.image

/** One plane of a YUV 4:2:0 picture, laid out the way Android's Image hands it over. */
class YuvPlane(val data: ByteArray, val rowStride: Int, val pixelStride: Int)

/**
 * Turns decoded video frames into ARGB pixels without a native library.
 *
 * It uses BT.601 video-range maths in the same fixed-point form as OpenCV's
 * COLOR_YUV2RGBA_NV21, which the app used before, so the models see the same colours.
 * Any plane layout works: planar (I420), interleaved (NV12, NV21) and padded rows.
 */
object Yuv420 {
    /**
     * Converts the [width] x [height] area starting at [left], [top] into [out], one ARGB
     * int per pixel, row after row.
     */
    fun toArgb(y: YuvPlane, u: YuvPlane, v: YuvPlane, left: Int, top: Int, width: Int, height: Int, out: IntArray) {
        require(width > 0 && height > 0 && out.size >= width * height) { "Output too small for $width x $height" }
        val yData = y.data
        val uData = u.data
        val vData = v.data
        var o = 0
        for (row in top until top + height) {
            val yRow = row * y.rowStride
            val uRow = (row shr 1) * u.rowStride
            val vRow = (row shr 1) * v.rowStride
            for (col in left until left + width) {
                val luma = (yData[yRow + col * y.pixelStride].toInt() and 0xFF) - 16
                val c = (if (luma < 0) 0 else luma) * CY + ROUND
                val half = col shr 1
                val cb = (uData[uRow + half * u.pixelStride].toInt() and 0xFF) - 128
                val cr = (vData[vRow + half * v.pixelStride].toInt() and 0xFF) - 128
                val r = clamp((c + CVR * cr) shr SHIFT)
                val g = clamp((c + CVG * cr + CUG * cb) shr SHIFT)
                val b = clamp((c + CUB * cb) shr SHIFT)
                out[o++] = OPAQUE or (r shl 16) or (g shl 8) or b
            }
        }
    }

    private fun clamp(value: Int) = if (value < 0) 0 else if (value > 255) 255 else value

    // OpenCV's ITUR_BT_601 constants: 1.164, 2.018, -0.391, -0.813 and 1.596 scaled by 2^20.
    private const val SHIFT = 20
    private const val ROUND = 1 shl (SHIFT - 1)
    private const val CY = 1220542
    private const val CUB = 2116026
    private const val CUG = -409993
    private const val CVG = -852492
    private const val CVR = 1673527
    private const val OPAQUE = -0x1000000
}
