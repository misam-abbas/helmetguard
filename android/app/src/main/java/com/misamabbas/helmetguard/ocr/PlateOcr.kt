package com.misamabbas.helmetguard.ocr

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.misamabbas.helmetguard.vision.plate.PlateReading
import com.misamabbas.helmetguard.vision.plate.PlateTextNormalizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.CLAHE
import org.opencv.imgproc.Imgproc
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.math.roundToInt

interface PlateOcrEngine {
    val name: String
    suspend fun read(enhanced: Bitmap): PlateReading?
}

@Singleton
class MlKitPlateOcr @Inject constructor() : PlateOcrEngine {
    override val name = "mlkit-text-16.0.1"
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val normalizer = PlateTextNormalizer()

    override suspend fun read(enhanced: Bitmap): PlateReading? = suspendCancellableCoroutine { continuation ->
        recognizer.process(InputImage.fromBitmap(enhanced, 0))
            .addOnSuccessListener { text ->
                // Sorted top to bottom so two-line plates keep their order.
                val lines = text.textBlocks.flatMap { it.lines }.sortedBy { it.boundingBox?.top ?: 0 }
                val confidences = lines.flatMap { it.elements }.map { it.confidence }
                if (lines.isEmpty()) {
                    continuation.resume(null)
                } else {
                    val plate = normalizer.normalize(lines.map { it.text })
                    continuation.resume(PlateReading(plate.text, if (confidences.isEmpty()) 0f else confidences.average().toFloat(), plate.formatValid))
                }
            }
            .addOnFailureListener { continuation.resume(null) }
    }
}

/** Builds the labelled, contrast-boosted copy that OCR reads. The original crop is never changed. */
@Singleton
class PlateEnhancer @Inject constructor(@ApplicationContext private val context: Context) {
    private val ready by lazy { OpenCVLoader.initLocal() }

    fun enhance(crop: Bitmap): Bitmap {
        check(ready) { "OpenCV failed to load in ${context.packageName}" }
        val rgba = Mat()
        val gray = Mat()
        val big = Mat()
        val equalized = Mat()
        val blurred = Mat()
        val sharp = Mat()
        val output = Mat()
        var clahe: CLAHE? = null
        try {
            Utils.bitmapToMat(crop, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val factor = TARGET_HEIGHT.toDouble() / crop.height
            Imgproc.resize(gray, big, Size(0.0, 0.0), factor, factor, Imgproc.INTER_CUBIC)
            clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
            clahe.apply(big, equalized)
            Imgproc.GaussianBlur(equalized, blurred, Size(0.0, 0.0), 1.5)
            Core.addWeighted(equalized, 1.5, blurred, -0.5, 0.0, sharp)
            Imgproc.cvtColor(sharp, output, Imgproc.COLOR_GRAY2RGBA)
            val result = Bitmap.createBitmap((crop.width * factor).roundToInt(), TARGET_HEIGHT, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(output, result)
            return result
        } finally {
            listOf(rgba, gray, big, equalized, blurred, sharp, output).forEach { it.release() }
        }
    }

    private companion object {
        const val TARGET_HEIGHT = 96
    }
}
