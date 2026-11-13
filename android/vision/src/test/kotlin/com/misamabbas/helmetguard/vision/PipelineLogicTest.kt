package com.misamabbas.helmetguard.vision

import com.misamabbas.helmetguard.vision.evidence.ImageMetrics
import com.misamabbas.helmetguard.vision.geometry.Box
import com.misamabbas.helmetguard.vision.pipeline.Association
import com.misamabbas.helmetguard.vision.pipeline.DetailCandidate
import com.misamabbas.helmetguard.vision.pipeline.DetailDetection
import com.misamabbas.helmetguard.vision.pipeline.DetailKind
import com.misamabbas.helmetguard.vision.pipeline.DetailScheduler
import com.misamabbas.helmetguard.vision.verify.HeadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineLogicTest {
    private val bike = Box(100f, 100f, 400f, 500f)
    private fun rider(l: Float, r: Float) = DetailDetection(Box(l, 110f, r, 400f), DetailKind.RIDER, 0.9f)
    private fun head(x: Float, kind: DetailKind, c: Float = 0.8f) = DetailDetection(Box(x, 120f, x + 40f, 160f), kind, c)

    @Test
    fun `a head belongs to the rider containing it`() {
        val details = Association.associate(bike, listOf(rider(120f, 240f), rider(250f, 380f), head(140f, DetailKind.NO_HELMET), head(300f, DetailKind.HELMET)))
        assertEquals(2, details.riders.size)
        assertEquals(HeadState.NO_HELMET, details.riders.first { it.riderBox.left == 120f }.head)
        assertEquals(HeadState.HELMET, details.riders.first { it.riderBox.left == 250f }.head)
    }

    @Test
    fun `anything outside the motorcycle is discarded`() {
        val neighbour = DetailDetection(Box(600f, 120f, 640f, 160f), DetailKind.NO_HELMET, 0.95f)
        assertTrue(Association.associate(bike, listOf(neighbour)).riders.isEmpty())
    }

    @Test
    fun `the padded crop keeps a head poking above a tight bike box`() {
        val tight = Box(100f, 130f, 400f, 500f)
        val padded = tight.expand(0.08f, 1920f, 1080f)
        assertEquals(76f, padded.left, 0.01f)
        assertEquals(100.4f, padded.top, 0.01f)
        assertEquals(424f, padded.right, 0.01f)
        assertEquals(529.6f, padded.bottom, 0.01f)
        val headAbove = DetailDetection(Box(200f, 105f, 240f, 145f), DetailKind.NO_HELMET, 0.9f)
        assertTrue(Association.associate(tight, listOf(headAbove)).riders.isEmpty())
        assertEquals(HeadState.NO_HELMET, Association.associate(padded, listOf(headAbove)).riders.single().head)
    }

    @Test
    fun `padding stays inside the frame`() {
        assertEquals(Box(0f, 0f, 640f, 360f), Box(5f, 5f, 635f, 355f).expand(0.08f, 640f, 360f))
    }

    @Test
    fun `a head without a rider box still counts as a person`() {
        val details = Association.associate(bike, listOf(head(150f, DetailKind.NO_HELMET)))
        assertEquals(HeadState.NO_HELMET, details.riders.single().head)
    }

    @Test
    fun `scheduler limits crops and favours undecided tracks`() {
        val candidates = listOf(
            DetailCandidate(1, 0, undecided = false, inEvidenceZone = false),
            DetailCandidate(2, 0, undecided = true, inEvidenceZone = false),
            DetailCandidate(3, 0, undecided = false, inEvidenceZone = true),
            DetailCandidate(4, 0, undecided = true, inEvidenceZone = true),
        )
        assertEquals(listOf(4, 2, 3), DetailScheduler.select(candidates, frameIndex = 10))
    }

    @Test
    fun `scheduler visits each track every second frame`() {
        val c = listOf(DetailCandidate(1, 9, undecided = true, inEvidenceZone = true))
        assertTrue(DetailScheduler.select(c, frameIndex = 10).isEmpty())
        assertEquals(listOf(1), DetailScheduler.select(c, frameIndex = 11))
    }

    @Test
    fun `a detailed image is sharper than a flat one`() {
        val flat = IntArray(100) { 0xFF808080.toInt() }
        val checker = IntArray(100) { i -> if ((i + i / 10) % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        assertEquals(0.0, ImageMetrics.laplacianVariance(flat, 10, 10), 0.001)
        assertTrue(ImageMetrics.laplacianVariance(checker, 10, 10) > 1000)
    }

    @Test
    fun `colour histogram is normalised per channel`() {
        val histogram = ImageMetrics.colorHistogram(IntArray(50) { 0xFFFF0000.toInt() })
        assertEquals(1f, histogram[7], 0.001f)
        assertEquals(1f, histogram[8], 0.001f)
        assertEquals(1f, histogram[16], 0.001f)
    }
}
