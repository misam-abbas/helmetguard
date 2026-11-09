package com.misamabbas.helmetguard.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import com.misamabbas.helmetguard.vision.pipeline.RawDetection
import com.misamabbas.helmetguard.vision.pipeline.YoloOutputParser
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OnnxDetector(modelBytes: ByteArray, override val inputSize: Int, override val classNames: List<String>) : Detector {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(1)
        // XNNPACK does the CPU work with its own thread pool.
        options.addXnnpack(mapOf("intra_op_num_threads" to THREADS.toString()))
        env.createSession(modelBytes, options)
    }
    private val inputName = session.inputNames.first()
    private val pixels = IntArray(inputSize * inputSize)
    private val buffer = ByteBuffer.allocateDirect(inputSize * inputSize * 3 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())

    override fun detect(input: Bitmap, minConfidence: Float): List<RawDetection> {
        input.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        val count = pixels.size
        for (i in 0 until count) {
            val p = pixels[i]
            buffer.put(i, (p shr 16 and 0xFF) / 255f)
            buffer.put(count + i, (p shr 8 and 0xFF) / 255f)
            buffer.put(2 * count + i, (p and 0xFF) / 255f)
        }
        buffer.rewind()

        OnnxTensor.createTensor(env, buffer, shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val batch = (result[0].value as Array<Array<FloatArray>>)[0]
                return if (batch.isNotEmpty() && batch[0].size == END_TO_END_COLUMNS) {
                    YoloOutputParser.parseEndToEnd(batch, inputSize, minConfidence)
                } else {
                    YoloOutputParser.parseRaw(batch, minConfidence)
                }
            }
        }
    }

    override fun close() {
        session.close()
    }

    private companion object {
        const val THREADS = 4
        const val END_TO_END_COLUMNS = 6
    }
}
