package com.misamabbas.helmetguard.data

import com.misamabbas.helmetguard.pipeline.FrameOutput
import com.misamabbas.helmetguard.pipeline.OverlayBox
import com.misamabbas.helmetguard.pipeline.OverlayKind
import com.misamabbas.helmetguard.vision.geometry.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TreeMap

class DetectionLogFormatTest {
    private fun output(vararg boxes: OverlayBox) = FrameOutput(1920, 1080, boxes.toList(), 1, 30f, 12f)

    @Test
    fun `a frame survives a round trip`() {
        val box = OverlayBox(Box(10.5f, 20f, 110f, 220f), "#12 Motorcycle 0.88", OverlayKind.NO_HELMET)
        val line = DetectionLogFormat.parse(DetectionLogFormat.format(4500, output(box)))!!
        assertEquals(4500L, line.ptsMs)
        assertEquals(1920, line.width)
        assertEquals(1080, line.height)
        assertEquals(box.box, line.boxes.single().box)
        assertEquals(box.label, line.boxes.single().label)
        assertEquals(OverlayKind.NO_HELMET, line.boxes.single().kind)
    }

    @Test
    fun `a label with a decimal comma stays whole`() {
        val box = OverlayBox(Box(0f, 0f, 5f, 5f), "Helmet 0,91", OverlayKind.HELMET)
        val line = DetectionLogFormat.parse(DetectionLogFormat.format(1, output(box)))!!
        assertEquals("Helmet 0,91", line.boxes.single().label)
    }

    @Test
    fun `a frame with no boxes is valid`() {
        assertTrue(DetectionLogFormat.parse(DetectionLogFormat.format(9, output()))!!.boxes.isEmpty())
    }

    @Test
    fun `damaged lines are skipped`() {
        assertNull(DetectionLogFormat.parse("not a log line"))
        assertNull(DetectionLogFormat.parse("x\t1\t2"))
    }

    @Test
    fun `playback finds the nearest earlier frame but not a stale one`() {
        val box = OverlayBox(Box(0f, 0f, 5f, 5f), "Helmet", OverlayKind.HELMET)
        val log = DetectionLog(100, 100, TreeMap(mapOf(1000L to listOf(box))))
        assertEquals(1, log.at(1200).size)
        assertTrue(log.at(900).isEmpty())
        assertTrue(log.at(2000).isEmpty())
    }
}
