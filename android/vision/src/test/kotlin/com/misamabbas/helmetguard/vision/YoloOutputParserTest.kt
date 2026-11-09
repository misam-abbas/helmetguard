package com.misamabbas.helmetguard.vision

import com.misamabbas.helmetguard.vision.geometry.Box
import com.misamabbas.helmetguard.vision.pipeline.RawDetection
import com.misamabbas.helmetguard.vision.pipeline.YoloOutputParser
import org.junit.Assert.assertEquals
import org.junit.Test

class YoloOutputParserTest {
    @Test
    fun `end-to-end rows drop weak detections`() {
        val rows = arrayOf(floatArrayOf(10f, 20f, 110f, 220f, 0.9f, 2f), floatArrayOf(0f, 0f, 5f, 5f, 0.1f, 0f))
        val result = YoloOutputParser.parseEndToEnd(rows, 416, 0.3f).single()
        assertEquals(2, result.classId)
        assertEquals(Box(10f, 20f, 110f, 220f), result.box)
    }

    @Test
    fun `normalised boxes are scaled to the input`() {
        val rows = arrayOf(floatArrayOf(0.25f, 0.5f, 0.5f, 1f, 0.9f, 0f))
        assertEquals(Box(104f, 208f, 208f, 416f), YoloOutputParser.parseEndToEnd(rows, 416, 0.3f).single().box)
    }

    @Test
    fun `raw output picks the best class and removes overlaps`() {
        val channels = arrayOf(
            floatArrayOf(100f, 102f, 300f),
            floatArrayOf(100f, 100f, 300f),
            floatArrayOf(50f, 50f, 50f),
            floatArrayOf(50f, 50f, 50f),
            floatArrayOf(0.9f, 0.6f, 0.1f),
            floatArrayOf(0.1f, 0.2f, 0.8f),
        )
        val result = YoloOutputParser.parseRaw(channels, 0.3f)
        assertEquals(2, result.size)
        assertEquals(0.9f, result.first { it.classId == 0 }.confidence, 0.001f)
        assertEquals(1, result.first { it.box.centerX == 300f }.classId)
    }

    @Test
    fun `non-max suppression keeps different classes that overlap`() {
        val box = Box(0f, 0f, 10f, 10f)
        assertEquals(2, YoloOutputParser.nonMaxSuppression(listOf(RawDetection(box, 0, 0.9f), RawDetection(box, 1, 0.8f)), 0.5f).size)
    }
}
