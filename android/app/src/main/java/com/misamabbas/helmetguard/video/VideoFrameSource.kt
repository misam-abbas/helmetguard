package com.misamabbas.helmetguard.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.misamabbas.helmetguard.camera.FrameConverter
import com.misamabbas.helmetguard.vision.image.Yuv420
import com.misamabbas.helmetguard.vision.image.YuvPlane
import kotlin.math.max

class UnsupportedVideoException(message: String) : Exception(message)

/**
 * Decodes a video frame by frame with the phone's hardware decoder. Nothing is played,
 * so results are the same on fast and slow phones; slow phones just take longer.
 */
class VideoFrameSource(private val context: Context, private val uri: Uri) {
    private val converter = FrameConverter()

    /**
     * Calls [onFrame] for frames at least [everyMs] apart, starting from [startMs].
     * Stops early when [shouldContinue] returns false.
     */
    fun decode(startMs: Long, everyMs: Long, shouldContinue: () -> Boolean, onFrame: (Bitmap, Long) -> Unit) {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

        val trackIndex = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: run {
            extractor.release()
            throw UnsupportedVideoException("This file has no video track.")
        }
        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)

        val codec = try {
            MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }
        } catch (e: Exception) {
            extractor.release()
            throw UnsupportedVideoException("This phone cannot decode $mime video. Convert it to H.264 and try again.")
        }

        if (startMs > 0) extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var nextDueMs = startMs
        var bitmap: Bitmap? = null
        var pixels = IntArray(0)
        val planes = arrayOfNulls<ByteArray>(3)

        try {
            while (!outputDone && shouldContinue()) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val size = extractor.readSampleData(codec.getInputBuffer(inIndex)!!, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIndex < 0) continue
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true

                val ptsMs = info.presentationTimeUs / 1000
                val wanted = info.size > 0 && ptsMs >= nextDueMs
                if (wanted) {
                    val image = codec.getOutputImage(outIndex)
                    if (image != null) {
                        // Decoders often pad the picture (1088 rows for 1080p video); the crop is the real frame.
                        val crop = image.cropRect.takeUnless { it.isEmpty } ?: Rect(0, 0, image.width, image.height)
                        val w = crop.width()
                        val h = crop.height()
                        if (pixels.size != w * h) pixels = IntArray(w * h)
                        val (y, u, v) = image.planes.mapIndexed { index, plane -> copyPlane(plane, planes, index) }
                        Yuv420.toArgb(y, u, v, crop.left, crop.top, w, h, pixels)
                        image.close()

                        val target = bitmap?.takeIf { it.width == w && it.height == h }
                            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap = it }
                        target.setPixels(pixels, 0, w, 0, 0, w, h)

                        val upright = if (rotation == 0) target else converter.rotate(target, w, h, rotation)
                        onFrame(upright, ptsMs)
                        nextDueMs = max(nextDueMs + everyMs, ptsMs + 1)
                    }
                }
                codec.releaseOutputBuffer(outIndex, false)
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }
    }

    /** Copies one plane out of the decoder's buffer, reusing the array from the last frame. */
    private fun copyPlane(plane: Image.Plane, reuse: Array<ByteArray?>, index: Int): YuvPlane {
        val buffer = plane.buffer
        buffer.rewind()
        val size = buffer.remaining()
        val data = reuse[index]?.takeIf { it.size == size } ?: ByteArray(size).also { reuse[index] = it }
        buffer.get(data, 0, size)
        return YuvPlane(data, plane.rowStride, plane.pixelStride)
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
    }
}
