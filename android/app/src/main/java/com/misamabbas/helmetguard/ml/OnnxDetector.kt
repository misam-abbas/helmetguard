package com.misamabbas.helmetguard.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import com.misamabbas.helmetguard.vision.pipeline.RawDetection
import com.misamabbas.helmetguard.vision.pipeline.YoloOutputParser
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** How ONNX Runtime runs a model: its own CPU threads, and XNNPACK's threads if XNNPACK is used. */
data class RuntimeOptions(val cpuThreads: Int, val xnnpackThreads: Int? = null) {
    val label: String get() = listOfNotNull(xnnpackThreads?.let { "XNNPACK $it" }, "CPU $cpuThreads").joinToString(" + ")

    companion object {
        /**
         * Measured on a Pixel 7a: ONNX Runtime's own CPU code on 4 threads ran the bike model in 82 ms,
         * against 131 ms for XNNPACK on 4 threads with 1 thread left for everything XNNPACK can't run,
         * and 183 ms with both on 4 threads, where the two thread pools fight.
         */
        val DEFAULT = RuntimeOptions(cpuThreads = 4)
    }
}

class OnnxDetector(
    modelBytes: ByteArray,
    override val inputSize: Int,
    override val classNames: List<String>,
    runtime: RuntimeOptions = RuntimeOptions.DEFAULT,
) : Detector {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(runtime.cpuThreads)
        runtime.xnnpackThreads?.let { options.addXnnpack(mapOf("intra_op_num_threads" to it.toString())) }
        env.createSession(modelBytes, options)
    }
    private val inputName = session.inputNames.first()
    private val pixels = IntArray(inputSize * inputSize)
    private val planes = FloatArray(inputSize * inputSize * 3)
    private val buffer = ByteBuffer.allocateDirect(inputSize * inputSize * 3 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())

    /** Time the last [detect] spent inside ONNX Runtime, for the benchmark. */
    var lastRunMs = 0f
        private set

    override fun detect(input: Bitmap, minConfidence: Float): List<RawDetection> {
        input.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        // Filled in a plain array, then copied into the native buffer in one go: writing the native
        // buffer one value at a time took longer than the bike model itself (about 150 ms a frame).
        val count = pixels.size
        for (i in 0 until count) {
            val p = pixels[i]
            planes[i] = UNIT[p shr 16 and 0xFF]
            planes[count + i] = UNIT[p shr 8 and 0xFF]
            planes[2 * count + i] = UNIT[p and 0xFF]
        }
        buffer.rewind()
        buffer.put(planes)
        buffer.rewind()

        OnnxTensor.createTensor(env, buffer, shape).use { tensor ->
            val start = System.nanoTime()
            session.run(mapOf(inputName to tensor)).use { result ->
                lastRunMs = (System.nanoTime() - start) / 1e6f
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
        const val END_TO_END_COLUMNS = 6

        /** Each 0..255 channel value as the model's 0..1 input. */
        val UNIT = FloatArray(256) { it / 255f }
    }
}
