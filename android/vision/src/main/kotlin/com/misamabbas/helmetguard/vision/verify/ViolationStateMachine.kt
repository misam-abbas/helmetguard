package com.misamabbas.helmetguard.vision.verify

import com.misamabbas.helmetguard.vision.tracker.Track

enum class TrackState { TRACKING, SUSPECT, CLEARED, CONFIRMED, FINALISED }

/**
 * The five-state life of one motorcycle track. A track can reach CONFIRMED at most
 * once, which is the first of the four layers of duplicate prevention.
 */
class ViolationStateMachine(private val settings: VerifierSettings = VerifierSettings()) {
    var state: TrackState = TrackState.TRACKING
        private set
    var confirmedAtMs: Long? = null
        private set

    /** Returns true only on the single call that moves the track to CONFIRMED. */
    fun onObservation(track: Track, votes: HelmetVoteBook, timestampMs: Long): Boolean {
        if (state == TrackState.CLEARED || state == TrackState.CONFIRMED || state == TrackState.FINALISED) return false

        if (state == TrackState.TRACKING && votes.anyNoHelmet) state = TrackState.SUSPECT

        val motion = BikeMotion(track.velocityX, track.growthRate)
        val trackQualifies = track.hits >= settings.minTrackFrames && track.averageConfidence >= settings.minTrackAverageConfidence

        if (trackQualifies && votes.hasConfirmedRider(motion)) {
            state = TrackState.CONFIRMED
            confirmedAtMs = timestampMs
            return true
        }

        if (votes.isHelmetMajority()) state = TrackState.CLEARED
        return false
    }

    /** Returns true only on the single call that moves the track to FINALISED. */
    fun onTick(timestampMs: Long, trackEnded: Boolean): Boolean {
        val confirmedAt = confirmedAtMs
        if (state != TrackState.CONFIRMED || confirmedAt == null) return false
        if (trackEnded || timestampMs - confirmedAt >= settings.finaliseAfterMs) {
            state = TrackState.FINALISED
            return true
        }
        return false
    }
}
