package com.misamabbas.helmetguard.vision

import com.misamabbas.helmetguard.vision.evidence.BestFrames
import com.misamabbas.helmetguard.vision.evidence.FrameQualityInput
import com.misamabbas.helmetguard.vision.evidence.FrameQualityScorer
import com.misamabbas.helmetguard.vision.geometry.Box
import com.misamabbas.helmetguard.vision.plate.PlateReading
import com.misamabbas.helmetguard.vision.plate.PlateStatus
import com.misamabbas.helmetguard.vision.plate.PlateTextNormalizer
import com.misamabbas.helmetguard.vision.plate.PlateVoting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlateTest {
    private val normalizer = PlateTextNormalizer()
    private val voting = PlateVoting()

    private fun clean(vararg lines: String) = normalizer.normalize(lines.toList())

    @Test
    fun `removes spaces, dashes and province words`() {
        assertEquals("LEA1234", clean("PUNJAB", "LEA-1234").text)
        assertEquals("ABC987", clean("ABC 987", "SINDH").text)
    }

    @Test
    fun `keeps two-line plates in order`() {
        assertEquals("LEA1234", clean("LEA", "1234").text)
    }

    @Test
    fun `fixes letter and number mix-ups by position`() {
        val fixed = clean("LEA12O4")
        assertEquals("LEA1204", fixed.text)
        assertTrue(fixed.formatValid)
        assertEquals("OAB123", clean("0AB123").text)
        assertEquals("LES1234", clean("LE51234").text)
    }

    @Test
    fun `prefers the split needing the fewest corrections`() {
        assertEquals(0, clean("ABCD123").substitutions)
        assertEquals("ABCD123", clean("ABCD123").text)
    }

    @Test
    fun `rejects text that fits no format`() {
        assertFalse(clean("HELLOWORLD").formatValid)
        assertFalse(clean("12").formatValid)
        assertFalse(clean("ABCDE123456").formatValid)
    }

    private fun read(text: String, c: Float = 0.9f, valid: Boolean = true) = PlateReading(text, c, valid)

    @Test
    fun `agreeing crops are read`() {
        val result = voting.vote(List(5) { read("LEA1234") }, 5)
        assertEquals(PlateStatus.READ, result.status)
        assertEquals("LEA1234", result.text)
    }

    @Test
    fun `majority wins character by character`() {
        val result = voting.vote(listOf(read("LEA1234"), read("LEA1234"), read("LEA1284"), read("LEA1234")), 4)
        assertEquals("LEA1234", result.text)
        assertEquals(2, result.alternatives.size)
    }

    @Test
    fun `one confident read among five crops is not certain`() {
        val result = voting.vote(listOf(read("LEA1234", 0.95f)), 5)
        assertEquals(PlateStatus.LOW_CONFIDENCE, result.status)
        assertEquals("LEA1234", result.text)
    }

    @Test
    fun `invalid format is flagged, never read`() {
        assertEquals(PlateStatus.LOW_CONFIDENCE, voting.vote(List(5) { read("HELLO", 0.99f, false) }, 5).status)
    }

    @Test
    fun `uncertain plates store no text`() {
        val result = voting.vote(listOf(read("LEA1234", 0.3f)), 5)
        assertEquals(PlateStatus.UNREADABLE, result.status)
        assertEquals("", result.text)
    }

    @Test
    fun `no text found is unreadable and no crops is no plate`() {
        assertEquals(PlateStatus.UNREADABLE, voting.vote(emptyList(), 5).status)
        assertEquals(PlateStatus.NO_PLATE_DETECTED, voting.vote(emptyList(), 0).status)
    }

    private fun quality(box: Box, sharp: Double = 300.0, c: Float = 0.8f, plate: Boolean = true, overlap: Float = 0f) =
        FrameQualityScorer.score(FrameQualityInput(box, 1920f, 1080f, sharp, c, plate, overlap))

    private val middle = Box(600f, 200f, 1100f, 900f)

    @Test
    fun `closer, sharper frames with a plate score higher`() {
        assertTrue(quality(middle) > quality(Box(600f, 200f, 700f, 300f)))
        assertTrue(quality(middle, sharp = 400.0) > quality(middle, sharp = 50.0))
        assertTrue(quality(middle, plate = true) > quality(middle, plate = false))
    }

    @Test
    fun `cut-off and overlapping bikes are penalised`() {
        assertTrue(quality(middle) > quality(Box(0f, 200f, 500f, 900f)))
        assertTrue(quality(middle) > quality(middle, overlap = 0.5f))
    }

    @Test
    fun `best frames keeps only the top three and reports evictions`() {
        val evicted = mutableListOf<String>()
        val best = BestFrames<String>(3) { evicted += it }
        best.offer(0.5f, "b")
        best.offer(0.9f, "a")
        best.offer(0.1f, "d")
        assertTrue(best.offer(0.3f, "c"))
        assertFalse(best.offer(0.05f, "z"))
        assertEquals(listOf("a", "b", "c"), best.best())
        assertEquals(listOf("d", "z"), evicted)
    }
}
