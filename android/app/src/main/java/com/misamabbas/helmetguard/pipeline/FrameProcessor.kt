package com.misamabbas.helmetguard.pipeline

import android.graphics.Bitmap
import com.misamabbas.helmetguard.ml.Detector
import com.misamabbas.helmetguard.ml.Letterboxer
import com.misamabbas.helmetguard.vision.evidence.BestFrames
import com.misamabbas.helmetguard.vision.evidence.FrameQualityInput
import com.misamabbas.helmetguard.vision.evidence.FrameQualityScorer
import com.misamabbas.helmetguard.vision.evidence.ImageMetrics
import com.misamabbas.helmetguard.vision.geometry.Box
import com.misamabbas.helmetguard.vision.pipeline.AssociatedRider
import com.misamabbas.helmetguard.vision.pipeline.Association
import com.misamabbas.helmetguard.vision.pipeline.BikeDetails
import com.misamabbas.helmetguard.vision.pipeline.DetailCandidate
import com.misamabbas.helmetguard.vision.pipeline.DetailDetection
import com.misamabbas.helmetguard.vision.pipeline.DetailKind
import com.misamabbas.helmetguard.vision.pipeline.DetailScheduler
import com.misamabbas.helmetguard.vision.tracker.ByteTracker
import com.misamabbas.helmetguard.vision.tracker.Detection
import com.misamabbas.helmetguard.vision.tracker.Track
import com.misamabbas.helmetguard.vision.tracker.TrackerSettings
import com.misamabbas.helmetguard.vision.verify.BikeMotion
import com.misamabbas.helmetguard.vision.verify.HeadState
import com.misamabbas.helmetguard.vision.verify.HelmetVoteBook
import com.misamabbas.helmetguard.vision.verify.RiderObservation
import com.misamabbas.helmetguard.vision.verify.RiderStatus
import com.misamabbas.helmetguard.vision.verify.TrackState
import com.misamabbas.helmetguard.vision.verify.VerifierSettings
import com.misamabbas.helmetguard.vision.verify.ViolationStateMachine
import java.io.ByteArrayOutputStream
import kotlin.math.max

enum class OverlayKind { MOTORCYCLE, HELMET, NO_HELMET, UNDECIDED, PLATE }

data class OverlayBox(val box: Box, val label: String, val kind: OverlayKind)

data class FrameOutput(
    val frameWidth: Int,
    val frameHeight: Int,
    val boxes: List<OverlayBox>,
    val activeTracks: Int,
    val stage1Ms: Float,
    val stage2Ms: Float,
)

data class SessionTotals(
    val motorcycles: Int = 0,
    val riders: Int = 0,
    val ridersHelmet: Int = 0,
    val ridersNoHelmet: Int = 0,
    val ridersUnknown: Int = 0,
    val violations: Int = 0,
)

/** A compressed copy of one good frame, with what was seen in it. */
class EvidenceFrame(
    val jpeg: ByteArray,
    val width: Int,
    val height: Int,
    val timestampMs: Long,
    val motorcycle: Box,
    val riders: List<AssociatedRider>,
    val score: Float,
)

class PlateCrop(val jpeg: ByteArray, val width: Int, val height: Int, val timestampMs: Long)

data class PipelineConfig(
    val verifier: VerifierSettings,
    val stage1Confidence: Float,
    /** Road area in 0..1 frame coordinates. Null means the whole frame. */
    val roadArea: Box? = null,
    val evidenceZone: Box? = null,
    val jpegQuality: Int = 88,
)

/** Everything the recorder needs about one track. */
class TrackRecord(val trackId: Int, val verifier: VerifierSettings, memory: EvidenceMemory) {
    val votes = HelmetVoteBook(verifier)
    val machine = ViolationStateMachine(verifier)
    val frames = BestFrames<EvidenceFrame>(3) { memory.release(it.jpeg.size) }
    val plates = BestFrames<PlateCrop>(5) { memory.release(it.jpeg.size) }
    var lastDetailFrame = 0L
    var violationId: String? = null
    var framesObserved = 0
    var motorcycleConfidenceSum = 0f
    var histogram: FloatArray? = null
    var lastTrack: Track? = null
    var plateCandidates = 0
    val motorcycleConfidence: Float get() = if (framesObserved == 0) 0f else motorcycleConfidenceSum / framesObserved
}

/** Hard cap on compressed frames held in memory, so low-memory phones never run out. */
class EvidenceMemory(private val capacityBytes: Int = 24 * 1024 * 1024) {
    private var used = 0
    fun tryReserve(bytes: Int): Boolean {
        if (used + bytes > capacityBytes) return false
        used += bytes
        return true
    }
    fun release(bytes: Int) {
        used = max(0, used - bytes)
    }
}

interface ViolationListener {
    fun onConfirmed(record: TrackRecord, timestampMs: Long)
    fun onFinalised(record: TrackRecord, timestampMs: Long)
    fun onTotals(totals: SessionTotals)
}

/**
 * One frame in, one result out. The live camera and video analysis both use this
 * class, so the two modes always behave alike. Not thread-safe: call from one thread.
 */
class FrameProcessor(
    private val stage1: Detector,
    private val stage2: Detector?,
    private val config: PipelineConfig,
    private val listener: ViolationListener,
) {
    private val tracker = ByteTracker(TrackerSettings(lowConfidence = config.stage1Confidence))
    private val memory = EvidenceMemory()
    private val records = HashMap<Int, TrackRecord>()
    private val stage1Box = Letterboxer(stage1.inputSize)
    private val stage2Box = stage2?.let { Letterboxer(it.inputSize) }
    private val metricsBox = Letterboxer(METRICS_SIZE)
    private val metricPixels = IntArray(METRICS_SIZE * METRICS_SIZE)
    private val jpegStream = ByteArrayOutputStream(512 * 1024)
    private val kinds: List<DetailKind?> = stage2?.classNames?.map(::kindOf).orEmpty()

    private var frameIndex = 0L
    var totals = SessionTotals()
        private set

    fun process(frame: Bitmap, timestampMs: Long): FrameOutput {
        frameIndex++
        val width = frame.width.toFloat()
        val height = frame.height.toFloat()

        val t0 = System.nanoTime()
        val mapping = stage1Box.draw(frame)
        val detections = stage1.detect(stage1Box.bitmap, config.stage1Confidence)
            .map { Detection(mapping.toSource(it.box), it.confidence) }
            .filter { isInRoad(it.box, width, height) }
        val stage1Ms = (System.nanoTime() - t0) / 1e6f

        val result = tracker.update(detections, timestampMs)
        result.active.forEach { track -> record(track).lastTrack = track }

        val t1 = System.nanoTime()
        val details = runDetail(frame, result.active, timestampMs, width, height)
        val stage2Ms = (System.nanoTime() - t1) / 1e6f

        result.active.forEach { track ->
            val record = record(track)
            if (record.machine.onTick(timestampMs, trackEnded = false)) listener.onFinalised(record, timestampMs)
        }
        result.ended.forEach { endTrack(it, timestampMs) }

        return FrameOutput(frame.width, frame.height, overlay(result.active, details), result.active.size, stage1Ms, stage2Ms)
    }

    /** Ends every open track when the session stops, so nothing already seen is lost. */
    fun finish(timestampMs: Long) {
        tracker.flush().forEach { endTrack(it, timestampMs) }
    }

    fun close() {
        stage1.close()
        stage2?.close()
    }

    private fun record(track: Track) = records.getOrPut(track.id) { TrackRecord(track.id, config.verifier, memory) }

    private fun isInRoad(box: Box, width: Float, height: Float): Boolean {
        val area = config.roadArea ?: return true
        return area.contains(box.centerX / width, box.bottom / height)
    }

    private fun isInEvidenceZone(box: Box, width: Float, height: Float): Boolean {
        val zone = config.evidenceZone ?: return false
        return zone.contains(box.centerX / width, box.centerY / height)
    }

    private fun runDetail(frame: Bitmap, active: List<Track>, timestampMs: Long, width: Float, height: Float): Map<Int, BikeDetails> {
        val detector = stage2 ?: return emptyMap()
        val box = stage2Box ?: return emptyMap()

        val candidates = active.map { track ->
            val record = record(track)
            val undecided = record.machine.state == TrackState.TRACKING || record.machine.state == TrackState.SUSPECT
            DetailCandidate(track.id, record.lastDetailFrame, undecided, isInEvidenceZone(track.box, width, height))
        }
        val selected = DetailScheduler.select(candidates, frameIndex).toHashSet()
        val output = HashMap<Int, BikeDetails>()

        for (track in active) {
            if (track.id !in selected) continue
            val record = record(track)
            record.lastDetailFrame = frameIndex
            record.framesObserved++
            record.motorcycleConfidenceSum += track.confidence

            val mapping = box.draw(frame, track.box)
            val found = detector.detect(box.bitmap, DETAIL_MIN_CONFIDENCE).mapNotNull { raw ->
                kinds.getOrNull(raw.classId)?.let { DetailDetection(mapping.toSource(raw.box), it, raw.confidence) }
            }
            val details = Association.associate(track.box, found)
            output[track.id] = details

            observe(record, track, details, frame, timestampMs, active, width, height)
        }
        return output
    }

    private fun observe(
        record: TrackRecord,
        track: Track,
        details: BikeDetails,
        frame: Bitmap,
        timestampMs: Long,
        active: List<Track>,
        width: Float,
        height: Float,
    ) {
        record.votes.observe(
            details.riders.map { rider ->
                RiderObservation(rider.riderBox.relativeTo(track.box), rider.head, rider.headConfidence, timestampMs)
            },
        )

        val confirmedNow = record.machine.onObservation(track, record.votes, timestampMs)
        val state = record.machine.state

        if (state == TrackState.SUSPECT || state == TrackState.CONFIRMED) {
            collectEvidence(record, track, details, frame, timestampMs, active, width, height)
        }
        if (confirmedNow) listener.onConfirmed(record, timestampMs)
    }

    private fun collectEvidence(
        record: TrackRecord,
        track: Track,
        details: BikeDetails,
        frame: Bitmap,
        timestampMs: Long,
        active: List<Track>,
        width: Float,
        height: Float,
    ) {
        metricsBox.draw(frame, track.box)
        metricsBox.bitmap.getPixels(metricPixels, 0, METRICS_SIZE, 0, 0, METRICS_SIZE, METRICS_SIZE)
        val sharpness = ImageMetrics.laplacianVariance(metricPixels, METRICS_SIZE, METRICS_SIZE)
        if (record.histogram == null) record.histogram = ImageMetrics.colorHistogram(metricPixels)

        val score = FrameQualityScorer.score(
            FrameQualityInput(
                motorcycle = track.box,
                frameWidth = width,
                frameHeight = height,
                sharpness = sharpness,
                confidence = track.confidence,
                plateVisible = details.plate != null,
                overlapWithOther = active.filter { it.id != track.id }.maxOfOrNull { it.box.iou(track.box) } ?: 0f,
            ),
        )

        // JPEG compression is the slow part, so only do it for a frame that would be kept.
        val worst = record.frames.best().lastOrNull()?.score
        if (record.frames.best().size < 3 || (worst != null && score > worst)) {
            val jpeg = compress(frame)
            if (memory.tryReserve(jpeg.size)) {
                record.frames.offer(score, EvidenceFrame(jpeg, frame.width, frame.height, timestampMs, track.box, details.riders, score))
            }
        }

        details.plate?.let { collectPlate(record, it, frame, sharpness, timestampMs) }
    }

    private fun collectPlate(record: TrackRecord, plate: DetailDetection, frame: Bitmap, sharpness: Double, timestampMs: Long) {
        val padX = plate.box.width * PLATE_PADDING
        val padY = plate.box.height * PLATE_PADDING
        val left = (plate.box.left - padX).toInt().coerceIn(0, frame.width - 1)
        val top = (plate.box.top - padY).toInt().coerceIn(0, frame.height - 1)
        val right = (plate.box.right + padX).toInt().coerceIn(left + 1, frame.width)
        val bottom = (plate.box.bottom + padY).toInt().coerceIn(top + 1, frame.height)
        val w = right - left
        val h = bottom - top
        if (h < MIN_PLATE_HEIGHT) return
        val aspect = w.toFloat() / h
        if (aspect !in MIN_PLATE_ASPECT..MAX_PLATE_ASPECT) return

        val score = h / 100f + (sharpness / 500.0).toFloat().coerceAtMost(1f) + plate.confidence
        record.plateCandidates++
        val crop = Bitmap.createBitmap(frame, left, top, w, h)
        val jpeg = compress(crop)
        crop.recycle()
        if (memory.tryReserve(jpeg.size)) record.plates.offer(score, PlateCrop(jpeg, w, h, timestampMs))
    }

    private fun compress(bitmap: Bitmap): ByteArray {
        jpegStream.reset()
        bitmap.compress(Bitmap.CompressFormat.JPEG, config.jpegQuality, jpegStream)
        return jpegStream.toByteArray()
    }

    private fun endTrack(track: Track, timestampMs: Long) {
        val record = records.remove(track.id) ?: return
        record.lastTrack = track
        if (record.machine.onTick(timestampMs, trackEnded = true)) listener.onFinalised(record, timestampMs)

        val verdicts = record.votes.verdicts(BikeMotion(track.velocityX, track.growthRate))
        totals = totals.copy(
            motorcycles = totals.motorcycles + 1,
            riders = totals.riders + verdicts.size,
            ridersHelmet = totals.ridersHelmet + verdicts.count { it.status == RiderStatus.HELMET },
            ridersNoHelmet = totals.ridersNoHelmet + verdicts.count { it.status == RiderStatus.NO_HELMET },
            ridersUnknown = totals.ridersUnknown + verdicts.count { it.status == RiderStatus.UNKNOWN },
            violations = totals.violations + if (record.violationId != null) 1 else 0,
        )
        record.frames.best().forEach { memory.release(it.jpeg.size) }
        record.plates.best().forEach { memory.release(it.jpeg.size) }
        listener.onTotals(totals)
    }

    private fun overlay(active: List<Track>, details: Map<Int, BikeDetails>): List<OverlayBox> {
        val boxes = ArrayList<OverlayBox>()
        for (track in active) {
            val state = records[track.id]?.machine?.state
            val kind = when (state) {
                TrackState.CLEARED -> OverlayKind.HELMET
                TrackState.CONFIRMED, TrackState.FINALISED -> OverlayKind.NO_HELMET
                else -> OverlayKind.UNDECIDED
            }
            boxes += OverlayBox(track.box, "#${track.id} Motorcycle ${"%.2f".format(track.confidence)}", kind)
            details[track.id]?.let { bike ->
                bike.riders.forEach { rider ->
                    val head = rider.headBox ?: return@forEach
                    when (rider.head) {
                        HeadState.HELMET -> boxes += OverlayBox(head, "Helmet ${"%.2f".format(rider.headConfidence)}", OverlayKind.HELMET)
                        HeadState.NO_HELMET -> boxes += OverlayBox(head, "No helmet ${"%.2f".format(rider.headConfidence)}", OverlayKind.NO_HELMET)
                        null -> Unit
                    }
                }
                bike.plate?.let { boxes += OverlayBox(it.box, "Plate", OverlayKind.PLATE) }
            }
        }
        return boxes
    }

    private fun kindOf(name: String): DetailKind? = when (name.lowercase()) {
        "rider" -> DetailKind.RIDER
        "helmet" -> DetailKind.HELMET
        "no_helmet" -> DetailKind.NO_HELMET
        "plate" -> DetailKind.PLATE
        else -> null
    }

    private companion object {
        const val METRICS_SIZE = 160
        const val DETAIL_MIN_CONFIDENCE = 0.25f
        const val PLATE_PADDING = 0.08f
        const val MIN_PLATE_HEIGHT = 20
        const val MIN_PLATE_ASPECT = 0.8f
        const val MAX_PLATE_ASPECT = 6f
    }
}
