package com.misamabbas.helmetguard.vision

import com.misamabbas.helmetguard.vision.geometry.Box
import com.misamabbas.helmetguard.vision.tracker.Track
import com.misamabbas.helmetguard.vision.verify.BikeMotion
import com.misamabbas.helmetguard.vision.verify.Candidate
import com.misamabbas.helmetguard.vision.verify.Deduplicator
import com.misamabbas.helmetguard.vision.verify.HeadState
import com.misamabbas.helmetguard.vision.verify.HelmetVoteBook
import com.misamabbas.helmetguard.vision.verify.RecentEvent
import com.misamabbas.helmetguard.vision.verify.RiderObservation
import com.misamabbas.helmetguard.vision.verify.RiderPolicy
import com.misamabbas.helmetguard.vision.verify.RiderRole
import com.misamabbas.helmetguard.vision.verify.RiderStatus
import com.misamabbas.helmetguard.vision.verify.TrackState
import com.misamabbas.helmetguard.vision.verify.VerifierSettings
import com.misamabbas.helmetguard.vision.verify.ViolationStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerificationTest {
    private val front = Box(0.55f, 0.05f, 0.95f, 0.65f)
    private val back = Box(0.05f, 0.05f, 0.45f, 0.65f)

    private fun track(hits: Int = 10, avg: Float = 0.8f) = Track(
        id = 1, box = Box(0f, 0f, 100f, 100f), confidence = avg, averageConfidence = avg, hits = hits,
        confirmed = true, firstSeenMs = 0, lastSeenMs = 0, velocityX = 100f, velocityY = 0f, growthRate = 0f,
    )

    private fun rider(box: Box, head: HeadState?, t: Long, c: Float = 0.8f) = RiderObservation(box, head, c, t)

    private fun feed(machine: ViolationStateMachine, votes: HelmetVoteBook, heads: List<HeadState?>, stepMs: Long = 150, t: Track = track()): Int {
        var fired = 0
        heads.forEachIndexed { i, head ->
            votes.observe(listOf(rider(front, head, i * stepMs)))
            if (machine.onObservation(t, votes, i * stepMs)) fired++
        }
        return fired
    }

    @Test
    fun `five bare-head frames over half a second confirm exactly once`() {
        val machine = ViolationStateMachine()
        val fired = feed(machine, HelmetVoteBook(), List(20) { HeadState.NO_HELMET })
        assertEquals(1, fired)
        assertEquals(TrackState.CONFIRMED, machine.state)
    }

    @Test
    fun `a single bad frame can never trigger a violation`() {
        val machine = ViolationStateMachine()
        assertEquals(0, feed(machine, HelmetVoteBook(), listOf(HeadState.NO_HELMET)))
        assertEquals(TrackState.SUSPECT, machine.state)
    }

    @Test
    fun `five frames inside half a second are not enough`() {
        val machine = ViolationStateMachine()
        assertEquals(0, feed(machine, HelmetVoteBook(), List(5) { HeadState.NO_HELMET }, stepMs = 50))
    }

    @Test
    fun `a flickering helmet is not flagged`() {
        val machine = ViolationStateMachine()
        val votes = HelmetVoteBook()
        val heads = List(30) { if (it % 2 == 0) HeadState.NO_HELMET else HeadState.HELMET }
        assertEquals(0, feed(machine, votes, heads))
        assertTrue(machine.state != TrackState.CONFIRMED)
    }

    @Test
    fun `consistent helmets clear the track for good`() {
        val machine = ViolationStateMachine()
        feed(machine, HelmetVoteBook(), List(10) { HeadState.HELMET })
        assertEquals(TrackState.CLEARED, machine.state)
    }

    @Test
    fun `low confidence observations do not count`() {
        val votes = HelmetVoteBook()
        repeat(20) { i -> votes.observe(listOf(rider(front, HeadState.NO_HELMET, i * 150L, 0.40f))) }
        assertFalse(votes.anyNoHelmet)
    }

    @Test
    fun `a weak track cannot confirm`() {
        val machine = ViolationStateMachine()
        assertEquals(0, feed(machine, HelmetVoteBook(), List(20) { HeadState.NO_HELMET }, t = track(avg = 0.4f)))
    }

    @Test
    fun `finalises when the track ends or three seconds pass, once`() {
        val machine = ViolationStateMachine()
        feed(machine, HelmetVoteBook(), List(10) { HeadState.NO_HELMET })
        val at = machine.confirmedAtMs!!
        assertFalse(machine.onTick(at + 1000, trackEnded = false))
        assertTrue(machine.onTick(at + 3000, trackEnded = false))
        assertFalse(machine.onTick(at + 4000, trackEnded = true))
        assertEquals(TrackState.FINALISED, machine.state)
    }

    @Test
    fun `each rider keeps their own votes`() {
        val votes = HelmetVoteBook()
        repeat(10) { i ->
            votes.observe(listOf(rider(front, HeadState.HELMET, i * 150L), rider(back, HeadState.NO_HELMET, i * 150L)))
        }
        val verdicts = votes.verdicts(BikeMotion(velocityX = 100f, growthRate = 0f))
        assertEquals(2, verdicts.size)
        // Moving right, the rider further right is the driver.
        assertEquals(RiderStatus.HELMET, verdicts.single { it.role == RiderRole.DRIVER }.status)
        assertEquals(RiderStatus.NO_HELMET, verdicts.single { it.role == RiderRole.PILLION }.status)
    }

    @Test
    fun `driver-only policy ignores a bare-headed pillion`() {
        val settings = VerifierSettings(riderPolicy = RiderPolicy.DRIVER_ONLY)
        val votes = HelmetVoteBook(settings)
        repeat(10) { i ->
            votes.observe(listOf(rider(front, HeadState.HELMET, i * 150L), rider(back, HeadState.NO_HELMET, i * 150L)))
        }
        assertFalse(votes.hasConfirmedRider(BikeMotion(100f, 0f)))

        val all = HelmetVoteBook()
        repeat(10) { i ->
            all.observe(listOf(rider(front, HeadState.HELMET, i * 150L), rider(back, HeadState.NO_HELMET, i * 150L)))
        }
        assertTrue(all.hasConfirmedRider(BikeMotion(100f, 0f)))
    }

    private fun event(histogram: FloatArray? = null, box: Box = Box(100f, 100f, 300f, 400f), vx: Float = 0f) =
        RecentEvent("v1", 0, box, vx, 0f, histogram)

    @Test
    fun `position compares against where the bike should be by now`() {
        val moving = Deduplicator().apply { remember(event(vx = 200f)) }
        assertEquals("v1", moving.findDuplicate(Candidate(1000, Box(300f, 100f, 500f, 400f), null)))
        // The next bike arriving where the first one was is a new event.
        assertNull(moving.findDuplicate(Candidate(1000, Box(100f, 100f, 300f, 400f), null)))
    }

    @Test
    fun `position alone stops counting after two seconds`() {
        val dedup = Deduplicator().apply { remember(event()) }
        assertNull(dedup.findDuplicate(Candidate(5000, Box(100f, 100f, 300f, 400f), null)))
    }

    @Test
    fun `very similar colour merges, different colour does not`() {
        val histogram = floatArrayOf(0.9f, 0.1f, 0f)
        val dedup = Deduplicator().apply { remember(event(histogram = histogram)) }
        assertEquals("v1", dedup.findDuplicate(Candidate(4000, Box(0f, 0f, 10f, 10f), floatArrayOf(0.88f, 0.12f, 0f))))
        assertNull(dedup.findDuplicate(Candidate(4000, Box(0f, 0f, 10f, 10f), floatArrayOf(0f, 0.1f, 0.9f))))
    }

    @Test
    fun `forgets events after ten seconds`() {
        val histogram = floatArrayOf(0.9f, 0.1f, 0f)
        val dedup = Deduplicator().apply { remember(event(histogram = histogram)) }
        assertNull(dedup.findDuplicate(Candidate(10_001, Box(100f, 100f, 300f, 400f), histogram)))
    }
}
