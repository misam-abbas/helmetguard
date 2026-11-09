package com.misamabbas.helmetguard.vision

import com.misamabbas.helmetguard.vision.geometry.Box
import com.misamabbas.helmetguard.vision.tracker.ByteTracker
import com.misamabbas.helmetguard.vision.tracker.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerTest {
    private fun box(x: Float, y: Float = 100f, w: Float = 120f, h: Float = 200f) = Box(x, y, x + w, y + h)

    @Test
    fun `a track counts only after three frames`() {
        val tracker = ByteTracker()
        assertTrue(tracker.update(listOf(Detection(box(100f), 0.9f)), 0).active.isEmpty())
        assertTrue(tracker.update(listOf(Detection(box(110f), 0.9f)), 100).active.isEmpty())
        assertEquals(1, tracker.update(listOf(Detection(box(120f), 0.9f)), 200).active.size)
    }

    @Test
    fun `id stays stable while a bike moves`() {
        val tracker = ByteTracker()
        val ids = (0 until 30).flatMap { i ->
            tracker.update(listOf(Detection(box(100f + i * 12f), 0.8f)), i * 100L).active.map { it.id }
        }.toSet()
        assertEquals(setOf(1), ids)
    }

    @Test
    fun `weak detections keep a track alive but never start one`() {
        val tracker = ByteTracker()
        assertTrue(tracker.update(listOf(Detection(box(100f), 0.4f)), 0).active.isEmpty())
        assertTrue(tracker.update(listOf(Detection(box(100f), 0.4f)), 100).active.isEmpty())
        assertTrue(tracker.update(listOf(Detection(box(100f), 0.4f)), 200).active.isEmpty())

        val strong = ByteTracker()
        repeat(4) { i -> strong.update(listOf(Detection(box(100f + i * 5f), 0.9f)), i * 100L) }
        val result = strong.update(listOf(Detection(box(120f), 0.4f)), 400)
        assertEquals(1, result.active.single().id)
        assertEquals(0.4f, result.active.single().confidence, 0.001f)
    }

    @Test
    fun `two bikes that pass each other keep separate ids`() {
        val tracker = ByteTracker()
        var aId = 0
        var bId = 0
        for (i in 0 until 12) {
            val y = 100f
            val a = Detection(Box(100f + i * 20f, y, 220f + i * 20f, y + 200f), 0.9f)
            val b = Detection(Box(900f - i * 20f, y + 300f, 1020f - i * 20f, y + 500f), 0.9f)
            val active = tracker.update(listOf(a, b), i * 100L).active
            if (active.size == 2) {
                val sorted = active.sortedBy { it.box.top }
                if (aId == 0) {
                    aId = sorted[0].id
                    bId = sorted[1].id
                }
                assertEquals(aId, sorted[0].id)
                assertEquals(bId, sorted[1].id)
            }
        }
        assertNotEquals(0, aId)
        assertNotEquals(aId, bId)
    }

    @Test
    fun `a bike survives a short gap and ends after one second unseen`() {
        val tracker = ByteTracker()
        repeat(5) { i -> tracker.update(listOf(Detection(box(100f), 0.9f)), i * 100L) }

        val gap = tracker.update(emptyList(), 900)
        assertEquals(1, gap.active.size)
        assertTrue(gap.ended.isEmpty())

        val back = tracker.update(listOf(Detection(box(100f), 0.9f)), 1200)
        assertEquals(1, back.active.single().id)

        val gone = tracker.update(emptyList(), 2300)
        assertEquals(1, gone.ended.single().id)
        assertTrue(gone.active.isEmpty())
    }

    @Test
    fun `prediction follows the bike's motion`() {
        val tracker = ByteTracker()
        repeat(10) { i -> tracker.update(listOf(Detection(box(100f + i * 10f), 0.9f)), i * 100L) }
        val predicted = tracker.predictedBox(1, 1400)!!
        assertTrue("expected glide forward, got ${predicted.left}", predicted.left > 195f)
    }

    @Test
    fun `flush ends every confirmed track`() {
        val tracker = ByteTracker()
        repeat(4) { i -> tracker.update(listOf(Detection(box(100f), 0.9f)), i * 100L) }
        assertEquals(1, tracker.flush().size)
        assertTrue(tracker.update(emptyList(), 500).active.isEmpty())
    }
}
