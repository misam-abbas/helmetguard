package com.misamabbas.helmetguard.vision

import com.misamabbas.helmetguard.vision.image.Yuv420
import com.misamabbas.helmetguard.vision.image.YuvPlane
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class Yuv420Test {
    private fun rgb(argb: Int) = Triple(argb shr 16 and 0xFF, argb shr 8 and 0xFF, argb and 0xFF)

    private fun close(expected: Triple<Int, Int, Int>, actual: Int, tolerance: Int = 2) {
        val got = rgb(actual)
        assertTrue("expected $expected, got $got", abs(got.first - expected.first) <= tolerance && abs(got.second - expected.second) <= tolerance && abs(got.third - expected.third) <= tolerance)
    }

    /** A planar (I420) picture filled with one colour. */
    private fun solid(width: Int, height: Int, y: Int, u: Int, v: Int) = Triple(
        YuvPlane(ByteArray(width * height) { y.toByte() }, width, 1),
        YuvPlane(ByteArray(width / 2 * height / 2) { u.toByte() }, width / 2, 1),
        YuvPlane(ByteArray(width / 2 * height / 2) { v.toByte() }, width / 2, 1),
    )

    private fun convert(planes: Triple<YuvPlane, YuvPlane, YuvPlane>, width: Int, height: Int): IntArray {
        val out = IntArray(width * height)
        Yuv420.toArgb(planes.first, planes.second, planes.third, 0, 0, width, height, out)
        return out
    }

    @Test
    fun `mid grey stays grey`() {
        close(Triple(128, 128, 128), convert(solid(4, 4, 126, 128, 128), 4, 4)[5])
    }

    @Test
    fun `video black and white map to 0 and 255`() {
        close(Triple(0, 0, 0), convert(solid(2, 2, 16, 128, 128), 2, 2)[0], 0)
        close(Triple(255, 255, 255), convert(solid(2, 2, 235, 128, 128), 2, 2)[0], 0)
    }

    @Test
    fun `primary colours come out right, so U and V are not swapped`() {
        // BT.601 video-range values of pure red, green and blue.
        close(Triple(255, 0, 0), convert(solid(2, 2, 81, 90, 240), 2, 2)[0], 3)
        close(Triple(0, 255, 0), convert(solid(2, 2, 145, 54, 34), 2, 2)[0], 3)
        close(Triple(0, 0, 255), convert(solid(2, 2, 41, 240, 110), 2, 2)[0], 3)
    }

    @Test
    fun `pixels are fully opaque`() {
        assertEquals(0xFF, convert(solid(2, 2, 100, 100, 100), 2, 2)[0] ushr 24)
    }

    @Test
    fun `interleaved NV21 with padded rows gives the same picture as planar`() {
        val width = 4
        val height = 4
        val stride = 8
        val luma = ByteArray(stride * height) { ((it % stride) * 30 + (it / stride) * 7 + 20).toByte() }
        val us = byteArrayOf(60, 90, 120, (-106).toByte())
        val vs = byteArrayOf(200.toByte(), 70, 130.toByte(), 30)

        val planar = Triple(
            YuvPlane(luma, stride, 1),
            YuvPlane(us, 2, 1),
            YuvPlane(vs, 2, 1),
        )
        // NV21: V then U interleaved in one buffer, each chroma row padded to the stride.
        val vu = ByteArray(stride * 2)
        for (row in 0 until 2) for (col in 0 until 2) {
            vu[row * stride + col * 2] = vs[row * 2 + col]
            vu[row * stride + col * 2 + 1] = us[row * 2 + col]
        }
        val interleaved = Triple(
            YuvPlane(luma, stride, 1),
            YuvPlane(vu.copyOfRange(1, vu.size), stride, 2),
            YuvPlane(vu, stride, 2),
        )
        assertArrayEquals(convert(planar, width, height), convert(interleaved, width, height))
    }

    @Test
    fun `a crop rectangle skips the padding rows a decoder adds`() {
        val full = solid(4, 4, 126, 128, 128)
        full.first.data.fill(16, 0, 4) // a black first row, outside the crop
        val out = IntArray(4 * 3)
        Yuv420.toArgb(full.first, full.second, full.third, 0, 1, 4, 3, out)
        out.forEach { close(Triple(128, 128, 128), it) }
    }

    @Test
    fun `each two-by-two block shares one colour sample`() {
        val planes = Triple(
            YuvPlane(ByteArray(16) { 126 }, 4, 1),
            YuvPlane(byteArrayOf(90, 128.toByte(), 128.toByte(), 128.toByte()), 2, 1),
            YuvPlane(byteArrayOf(240.toByte(), 128.toByte(), 128.toByte(), 128.toByte()), 2, 1),
        )
        val out = convert(planes, 4, 4)
        assertEquals(out[0], out[1])
        assertEquals(out[0], out[4])
        assertEquals(out[0], out[5])
        close(Triple(128, 128, 128), out[2])
    }
}
