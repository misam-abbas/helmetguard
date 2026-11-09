package com.misamabbas.helmetguard.pipeline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import com.misamabbas.helmetguard.BuildConfig
import com.misamabbas.helmetguard.data.EvidenceStore
import com.misamabbas.helmetguard.data.db.AppDatabase
import com.misamabbas.helmetguard.data.db.EvidenceFileEntity
import com.misamabbas.helmetguard.data.db.SessionEntity
import com.misamabbas.helmetguard.data.db.SyncState
import com.misamabbas.helmetguard.data.db.ViolationEntity
import com.misamabbas.helmetguard.ocr.MlKitPlateOcr
import com.misamabbas.helmetguard.ocr.PlateEnhancer
import com.misamabbas.helmetguard.sync.SyncScheduler
import com.misamabbas.helmetguard.vision.geometry.Box
import com.misamabbas.helmetguard.vision.plate.PlateAlternative
import com.misamabbas.helmetguard.vision.plate.PlateReading
import com.misamabbas.helmetguard.vision.plate.PlateStatus
import com.misamabbas.helmetguard.vision.plate.PlateVoting
import com.misamabbas.helmetguard.vision.verify.BikeMotion
import com.misamabbas.helmetguard.vision.verify.Candidate
import com.misamabbas.helmetguard.vision.verify.Deduplicator
import com.misamabbas.helmetguard.vision.verify.RecentEvent
import com.misamabbas.helmetguard.vision.verify.RiderRole
import com.misamabbas.helmetguard.vision.verify.RiderStatus
import com.misamabbas.helmetguard.vision.verify.RiderVerdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.hypot
import kotlin.math.max

/** Everything about one detection session that does not change while it runs. */
data class SessionContext(
    val sessionId: String,
    val source: String,
    val modelVersion: String,
    val helmetPolicy: String,
    val jpegQuality: Int,
    /** Maps frame time to wall-clock time for live capture. Null for video. */
    val isVideo: Boolean,
)

/**
 * Turns track events into saved records. Work runs in order on one background
 * queue, away from the frame loop, so a slow disk never delays detection.
 */
@Singleton
class ViolationRecorder @Inject constructor(
    private val db: AppDatabase,
    private val store: EvidenceStore,
    private val ocr: MlKitPlateOcr,
    private val enhancer: PlateEnhancer,
    private val sync: SyncScheduler,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val voting = PlateVoting()

    init {
        scope.launch {
            for (job in queue) runCatching { job() }
        }
    }

    fun listenerFor(session: SessionContext, wallClockOf: (Long) -> Long): ViolationListener = object : ViolationListener {
        private val dedup = Deduplicator()

        override fun onConfirmed(record: TrackRecord, timestampMs: Long) {
            val track = record.lastTrack ?: return
            val duplicate = dedup.findDuplicate(Candidate(timestampMs, track.box, null, record.histogram))
            if (duplicate != null) return

            val id = UUID.randomUUID().toString()
            record.violationId = id
            dedup.remember(RecentEvent(id, timestampMs, track.box, track.velocityX, track.velocityY, null, record.histogram))

            val entity = ViolationEntity(
                id = id,
                sessionId = session.sessionId,
                trackId = record.trackId,
                source = session.source,
                detectedAt = wallClockOf(timestampMs),
                videoTimestampMs = if (session.isVideo) timestampMs else null,
                helmetPolicy = session.helmetPolicy,
                modelVersion = session.modelVersion,
                appVersion = BuildConfig.VERSION_NAME,
                createdAt = System.currentTimeMillis(),
            )
            // Written straight away, so even a crash before finalisation keeps the violation.
            queue.trySend { db.violations().insert(entity) }
        }

        override fun onFinalised(record: TrackRecord, timestampMs: Long) {
            val id = record.violationId ?: return
            val track = record.lastTrack ?: return
            val verdicts = record.votes.verdicts(BikeMotion(track.velocityX, track.growthRate))
            val frames = record.frames.best().toList()
            val plates = record.plates.best().toList()
            val confidence = record.motorcycleConfidence
            val observed = record.framesObserved
            queue.trySend { finalise(id, session, record.trackId, verdicts, frames, plates, confidence, observed) }
        }

        override fun onTotals(totals: SessionTotals) {
            queue.trySend { saveTotals(session.sessionId, totals) }
        }
    }

    suspend fun saveSession(session: SessionEntity) = db.sessions().upsert(session)

    fun updateSession(id: String, change: (SessionEntity) -> SessionEntity) {
        queue.trySend {
            db.sessions().byId(id)?.let { db.sessions().upsert(change(it).copy(dirty = true)) }
        }
    }

    private suspend fun saveTotals(sessionId: String, totals: SessionTotals) {
        val session = db.sessions().byId(sessionId) ?: return
        db.sessions().upsert(
            session.copy(
                motorcyclesTotal = totals.motorcycles,
                ridersTotal = totals.riders,
                ridersHelmet = totals.ridersHelmet,
                ridersNoHelmet = totals.ridersNoHelmet,
                ridersUnknown = totals.ridersUnknown,
                violationsConfirmed = totals.violations,
                dirty = true,
            ),
        )
    }

    private suspend fun finalise(
        id: String,
        session: SessionContext,
        trackId: Int,
        verdicts: List<RiderVerdict>,
        frames: List<EvidenceFrame>,
        plates: List<PlateCrop>,
        motorcycleConfidence: Float,
        framesObserved: Int,
    ) {
        val existing = db.violations().byId(id) ?: return
        val best = frames.firstOrNull()
        val quality = session.jpegQuality

        val files = LinkedHashMap<String, ByteArray>()
        val described = ArrayList<EvidenceFileEntity>()

        fun add(kind: String, seq: Int, jpeg: ByteArray, w: Int, h: Int, ts: Long?, bbox: JsonArray? = null, enhanced: Boolean = false, upload: Boolean = true) {
            val name = "${kind}_$seq.jpg"
            files[name] = jpeg
            described += EvidenceFileEntity(
                id = UUID.randomUUID().toString(),
                violationId = id,
                kind = kind,
                seq = seq,
                relativePath = "$id/$name",
                width = w,
                height = h,
                bytes = jpeg.size.toLong(),
                sha256 = "",
                bboxJson = bbox?.toString(),
                frameTimestampMs = ts,
                isEnhanced = enhanced,
                uploadable = upload,
            )
        }

        var ridersJson = JsonArray(emptyList())

        if (best != null) {
            val fullWidth = best.width.toFloat()
            val fullHeight = best.height.toFloat()
            add("full_frame", 0, best.jpeg, best.width, best.height, best.timestampMs, normalised(best.motorcycle, fullWidth, fullHeight))
            frames.getOrNull(1)?.let { add("alt_frame", 0, it.jpeg, it.width, it.height, it.timestampMs, upload = false) }

            val decoded = BitmapFactory.decodeByteArray(best.jpeg, 0, best.jpeg.size)
            if (decoded != null) {
                cropJpeg(decoded, best.motorcycle, quality)?.let { (j, w, h) -> add("motorcycle", 0, j, w, h, best.timestampMs) }
                add("thumb", 0, thumbnail(decoded, best.motorcycle), THUMB_WIDTH, THUMB_WIDTH * 3 / 4, best.timestampMs)

                // Riders are matched by where they sit on the bike, not by list order,
                // because detectors do not return people in a stable order between frames.
                val matched = verdicts.associateWith { verdict ->
                    best.riders.minByOrNull { rider ->
                        val r = rider.riderBox.relativeTo(best.motorcycle)
                        hypot(r.centerX - verdict.relativeBox.centerX, r.centerY - verdict.relativeBox.centerY)
                    }
                }
                var riderSeq = 0
                var headSeq = 0
                verdicts.filter { it.status == RiderStatus.NO_HELMET }.forEach { verdict ->
                    val rider = matched[verdict] ?: return@forEach
                    cropJpeg(decoded, rider.riderBox, quality)?.let { (j, w, h) -> add("rider", riderSeq++, j, w, h, best.timestampMs) }
                    rider.headBox?.let { head -> cropJpeg(decoded, head, quality)?.let { (j, w, h) -> add("head", headSeq++, j, w, h, best.timestampMs) } }
                }
                ridersJson = buildJsonArray {
                    verdicts.forEach { verdict -> add(riderJson(verdict, matched[verdict]?.riderBox, fullWidth, fullHeight)) }
                }
                decoded.recycle()
            }
        }

        val ocrResult = readPlate(plates)
        plates.firstOrNull()?.let { plate ->
            add("plate", 0, plate.jpeg, plate.width, plate.height, plate.timestampMs)
            ocrResult.enhanced?.let { enhanced -> add("plate_enhanced", 0, enhanced.first, enhanced.second, enhanced.third, plate.timestampMs, enhanced = true) }
        }

        val noHelmet = verdicts.count { it.status == RiderStatus.NO_HELMET }
        val noHelmetFrames = verdicts.maxOfOrNull { it.noHelmetFrames } ?: 0
        val helmetConfidence = verdicts.filter { it.status == RiderStatus.NO_HELMET }.maxOfOrNull { it.confidence }

        files["meta.json"] = meta(id, session, verdicts, motorcycleConfidence, framesObserved, best?.timestampMs, ocrResult.alternatives).toByteArray()

        val written = store.writeAtomically(id, files).associateBy { it.name }
        val rows = described.map { row ->
            written[row.relativePath.substringAfter('/')]?.let { row.copy(sha256 = it.sha256, bytes = it.bytes) } ?: row
        }

        val updated = existing.copy(
            riderCount = verdicts.size,
            noHelmetCount = noHelmet,
            ridersJson = ridersJson.toString(),
            helmetConfidence = helmetConfidence,
            motorcycleConfidence = motorcycleConfidence,
            framesObserved = framesObserved,
            noHelmetFrames = noHelmetFrames,
            plateTextOcr = ocrResult.text.takeIf { it.isNotEmpty() },
            plateOcrConfidence = ocrResult.confidence.takeIf { ocrResult.status != PlateStatus.NO_PLATE_DETECTED },
            plateOcrStatus = ocrResult.status.name.lowercase(),
            plateCandidatesJson = alternativesJson(ocrResult.alternatives).toString(),
            ocrEngine = ocr.name.takeIf { ocrResult.status != PlateStatus.NO_PLATE_DETECTED },
            trackId = trackId,
            syncState = SyncState.QUEUED,
        )
        db.violations().finalise(updated, rows)
        sync.syncSoon()
    }

    private data class OcrOutcome(
        val text: String,
        val confidence: Float,
        val status: PlateStatus,
        val alternatives: List<PlateAlternative>,
        val enhanced: Triple<ByteArray, Int, Int>?,
    )

    private suspend fun readPlate(plates: List<PlateCrop>): OcrOutcome {
        val readings = ArrayList<PlateReading>()
        var firstEnhanced: Triple<ByteArray, Int, Int>? = null
        for (plate in plates) {
            val crop = BitmapFactory.decodeByteArray(plate.jpeg, 0, plate.jpeg.size) ?: continue
            runCatching {
                val enhanced = enhancer.enhance(crop)
                if (firstEnhanced == null) {
                    firstEnhanced = Triple(compress(enhanced, ENHANCED_QUALITY), enhanced.width, enhanced.height)
                }
                ocr.read(enhanced)?.let { readings += it }
                enhanced.recycle()
            }
            crop.recycle()
        }
        val vote = voting.vote(readings, plates.size)
        return OcrOutcome(vote.text, vote.confidence, vote.status, vote.alternatives, firstEnhanced)
    }

    private fun cropJpeg(source: Bitmap, box: Box, quality: Int): Triple<ByteArray, Int, Int>? {
        val rect = clamp(source, box) ?: return null
        val crop = Bitmap.createBitmap(source, rect.left, rect.top, rect.width(), rect.height())
        val jpeg = compress(crop, quality)
        crop.recycle()
        return Triple(jpeg, rect.width(), rect.height())
    }

    private fun thumbnail(source: Bitmap, focus: Box): ByteArray {
        val rect = clamp(source, focus) ?: Rect(0, 0, source.width, source.height)
        val crop = Bitmap.createBitmap(source, rect.left, rect.top, rect.width(), rect.height())
        val scaled = Bitmap.createScaledBitmap(crop, THUMB_WIDTH, THUMB_WIDTH * 3 / 4, true)
        val jpeg = compress(scaled, THUMB_QUALITY)
        crop.recycle()
        scaled.recycle()
        return jpeg
    }

    private fun clamp(source: Bitmap, box: Box): Rect? {
        val left = box.left.toInt().coerceIn(0, source.width - 1)
        val top = box.top.toInt().coerceIn(0, source.height - 1)
        val right = box.right.toInt().coerceIn(left + 1, source.width)
        val bottom = box.bottom.toInt().coerceIn(top + 1, source.height)
        return if (right - left < 4 || bottom - top < 4) null else Rect(left, top, right, bottom)
    }

    private fun compress(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream(max(16 * 1024, bitmap.byteCount / 10)).also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    private fun normalised(box: Box, width: Float, height: Float) = buildJsonArray {
        add(kotlinx.serialization.json.JsonPrimitive(box.left / width))
        add(kotlinx.serialization.json.JsonPrimitive(box.top / height))
        add(kotlinx.serialization.json.JsonPrimitive(box.right / width))
        add(kotlinx.serialization.json.JsonPrimitive(box.bottom / height))
    }

    private fun riderJson(verdict: RiderVerdict, box: Box?, width: Float, height: Float): JsonObject = buildJsonObject {
        put("role", if (verdict.role == RiderRole.DRIVER) "driver" else "pillion")
        put("status", verdict.status.name.lowercase())
        put("confidence", verdict.confidence)
        if (box != null) put("box", normalised(box, width, height))
    }

    private fun alternativesJson(list: List<PlateAlternative>) = buildJsonArray {
        list.forEach { add(buildJsonObject { put("text", it.text); put("confidence", it.confidence) }) }
    }

    private fun meta(
        id: String,
        session: SessionContext,
        verdicts: List<RiderVerdict>,
        motorcycleConfidence: Float,
        framesObserved: Int,
        frameTimestampMs: Long?,
        alternatives: List<PlateAlternative>,
    ): String = buildJsonObject {
        put("violation_id", id)
        put("session_id", session.sessionId)
        put("model_version", session.modelVersion)
        put("app_version", BuildConfig.VERSION_NAME)
        put("helmet_policy", session.helmetPolicy)
        put("motorcycle_confidence", motorcycleConfidence)
        put("frames_observed", framesObserved)
        frameTimestampMs?.let { put("frame_timestamp_ms", it) }
        put("rider_verdicts", buildJsonArray {
            verdicts.forEach {
                add(buildJsonObject {
                    put("role", it.role.name.lowercase())
                    put("status", it.status.name.lowercase())
                    put("confidence", it.confidence)
                    put("no_helmet_frames", it.noHelmetFrames)
                    put("decided_frames", it.decidedFrames)
                })
            }
        })
        put("plate_candidates", alternativesJson(alternatives))
    }.toString()

    private companion object {
        const val THUMB_WIDTH = 320
        const val THUMB_QUALITY = 70
        const val ENHANCED_QUALITY = 90
    }
}
