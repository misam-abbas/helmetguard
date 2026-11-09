package com.misamabbas.helmetguard.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.misamabbas.helmetguard.vision.geometry.Box
import com.misamabbas.helmetguard.vision.pipeline.RawDetection
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.roundToInt

/** Every model sits behind this, so LiteRT or D-FINE can be swapped in later. */
interface Detector : AutoCloseable {
    val inputSize: Int
    val classNames: List<String>

    /** [input] must be square and exactly [inputSize] pixels. */
    fun detect(input: Bitmap, minConfidence: Float): List<RawDetection>
}

@Serializable data class ModelEntry(val file: String, val input: Int, val classes: List<String>)

@Serializable data class ModelManifest(val version: String, val stage1: ModelEntry, val stage2: ModelEntry? = null)

sealed interface ModelStatus {
    data class Ready(val manifest: ModelManifest) : ModelStatus
    data object Missing : ModelStatus
}

@Singleton
class ModelStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    fun status(): ModelStatus = runCatching {
        val manifest = json.decodeFromString(ModelManifest.serializer(), context.assets.open("models/models.json").bufferedReader().use { it.readText() })
        context.assets.open("models/${manifest.stage1.file}").close()
        ModelStatus.Ready(manifest)
    }.getOrDefault(ModelStatus.Missing)

    fun create(entry: ModelEntry): Detector {
        val bytes = context.assets.open("models/${entry.file}").use { it.readBytes() }
        return OnnxDetector(bytes, entry.input, entry.classes)
    }
}

/** How a letterboxed input maps back to the source image. */
data class LetterboxMapping(val scale: Float, val padX: Float, val padY: Float, val originX: Float, val originY: Float) {
    fun toSource(box: Box) = Box(
        (box.left - padX) / scale + originX,
        (box.top - padY) / scale + originY,
        (box.right - padX) / scale + originX,
        (box.bottom - padY) / scale + originY,
    )
}

/** Draws a frame or crop into one reused square bitmap, so no memory is allocated per frame. */
class Letterboxer(val size: Int) {
    val bitmap: Bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private val dst = RectF()

    fun draw(source: Bitmap, region: Box? = null): LetterboxMapping {
        val left = (region?.left ?: 0f).coerceIn(0f, source.width.toFloat())
        val top = (region?.top ?: 0f).coerceIn(0f, source.height.toFloat())
        val right = (region?.right ?: source.width.toFloat()).coerceIn(left + 1, source.width.toFloat().coerceAtLeast(left + 1))
        val bottom = (region?.bottom ?: source.height.toFloat()).coerceIn(top + 1, source.height.toFloat().coerceAtLeast(top + 1))
        val w = right - left
        val h = bottom - top
        val scale = min(size / w, size / h)
        val padX = (size - w * scale) / 2f
        val padY = (size - h * scale) / 2f

        canvas.drawColor(Color.BLACK)
        src.set(left.toInt(), top.toInt(), right.roundToInt(), bottom.roundToInt())
        dst.set(padX, padY, padX + w * scale, padY + h * scale)
        canvas.drawBitmap(source, src, dst, paint)
        return LetterboxMapping(scale, padX, padY, left, top)
    }
}
