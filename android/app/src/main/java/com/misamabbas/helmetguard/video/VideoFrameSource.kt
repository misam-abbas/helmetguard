package com.misamabbas.helmetguard.video

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.misamabbas.helmetguard.camera.FrameConverter
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
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
        check(OpenCVLoader.initLocal()) { "OpenCV failed to load" }
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
        val yuv = Mat()
        val rgba = Mat()
        var bitmap: Bitmap? = null
        var nv21 = ByteArray(0)

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
                        val w = image.width
                        val h = image.height
                        if (nv21.size != w * h * 3 / 2) nv21 = ByteArray(w * h * 3 / 2)
                        toNv21(image, nv21)
                        image.close()

                        if (yuv.rows() != h * 3 / 2 || yuv.cols() != w) yuv.create(h * 3 / 2, w, CvType.CV_8UC1)
                        yuv.put(0, 0, nv21)
                        // OpenCV handles the colour-format differences between phones.
                        Imgproc.cvtColor(yuv, rgba, Imgproc.COLOR_YUV2RGBA_NV21)

                        val target = bitmap?.takeIf { it.width == w && it.height == h }
                            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap = it }
                        Utils.matToBitmap(rgba, target)

                        val upright = if (rotation == 0) target else converter.rotate(target, w, h, rotation)
                        onFrame(upright, ptsMs)
                        nextDueMs = max(nextDueMs + everyMs, ptsMs + 1)
                    }
                }
                codec.releaseOutputBuffer(outIndex, false)
            }
        } finally {
            yuv.release()
            rgba.release()
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }
    }

    /** Copies any flexible YUV 4:2:0 layout into one NV21 array. */
    private fun toNv21(image: Image, out: ByteArray) {
        val w = image.width
        val h = image.height
        val (yPlane, uPlane, vPlane) = image.planes.let { Triple(it[0], it[1], it[2]) }

        var position = 0
        val row = ByteArray(yPlane.rowStride.coerceAtLeast(w))
        for (y in 0 until h) {
            val buffer = yPlane.buffer
            buffer.position(y * yPlane.rowStride)
            buffer.get(row, 0, minOf(w, buffer.remaining()))
            System.arraycopy(row, 0, out, position, w)
            position += w
        }

        for (y in 0 until h / 2) {
            for (x in 0 until w / 2) {
                out[position++] = vPlane.buffer.get(y * vPlane.rowStride + x * vPlane.pixelStride)
                out[position++] = uPlane.buffer.get(y * uPlane.rowStride + x * uPlane.pixelStride)
            }
        }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
    }
}
