package com.misamabbas.helmetguard.data

import com.misamabbas.helmetguard.pipeline.FrameOutput
import com.misamabbas.helmetguard.pipeline.OverlayBox
import com.misamabbas.helmetguard.pipeline.OverlayKind
import com.misamabbas.helmetguard.vision.geometry.Box
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.util.TreeMap

/** Every processed frame's boxes for one video session, which the review player draws. */
class DetectionLog(val frameWidth: Int, val frameHeight: Int, private val frames: TreeMap<Long, List<OverlayBox>>) {
    val isEmpty: Boolean get() = frames.isEmpty()

    /** The boxes for the frame nearest before [positionMs], or none if that frame is too old. */
    fun at(positionMs: Long, maxAgeMs: Long = 600): List<OverlayBox> {
        val entry = frames.floorEntry(positionMs) ?: return emptyList()
        return if (positionMs - entry.key <= maxAgeMs) entry.value else emptyList()
    }

    companion object {
        fun fileFor(root: File, sessionId: String) = File(File(root, "detections"), "$sessionId.log")

        /** Later lines win, so frames re-processed after a resume simply replace earlier ones. */
        fun read(file: File): DetectionLog? {
            if (!file.exists()) return null
            var width = 0
            var height = 0
            val frames = TreeMap<Long, List<OverlayBox>>()
            file.forEachLine { line ->
                val parsed = DetectionLogFormat.parse(line) ?: return@forEachLine
                width = parsed.width
                height = parsed.height
                frames[parsed.ptsMs] = parsed.boxes
            }
            return if (width == 0) null else DetectionLog(width, height, frames)
        }
    }
}

/** One line per frame: pts, width, height, then a tab-separated entry per box. */
object DetectionLogFormat {
    data class Line(val ptsMs: Long, val width: Int, val height: Int, val boxes: List<OverlayBox>)

    fun format(ptsMs: Long, output: FrameOutput): String = buildString {
        append(ptsMs).append('\t').append(output.frameWidth).append('\t').append(output.frameHeight)
        output.boxes.forEach { box ->
            append('\t').append(box.kind.name)
            append(',').append(box.box.left)
            append(',').append(box.box.top)
            append(',').append(box.box.right)
            append(',').append(box.box.bottom)
            // Last, because a label may itself contain a comma in some number formats.
            append(',').append(box.label.replace('\t', ' '))
        }
    }

    fun parse(line: String): Line? {
        val parts = line.split('\t')
        if (parts.size < 3) return null
        val pts = parts[0].toLongOrNull() ?: return null
        val width = parts[1].toIntOrNull() ?: return null
        val height = parts[2].toIntOrNull() ?: return null
        val boxes = parts.drop(3).mapNotNull(::parseBox)
        return Line(pts, width, height, boxes)
    }

    private fun parseBox(entry: String): OverlayBox? {
        val fields = entry.split(',', limit = 6)
        if (fields.size < 6) return null
        val kind = runCatching { OverlayKind.valueOf(fields[0]) }.getOrNull() ?: return null
        val left = fields[1].toFloatOrNull() ?: return null
        val top = fields[2].toFloatOrNull() ?: return null
        val right = fields[3].toFloatOrNull() ?: return null
        val bottom = fields[4].toFloatOrNull() ?: return null
        return OverlayBox(Box(left, top, right, bottom), fields[5], kind)
    }
}

class DetectionLogWriter(file: File) : Closeable {
    private val writer: BufferedWriter = run {
        file.parentFile?.mkdirs()
        // Append, so resuming an interrupted video keeps what was already logged.
        FileOutputStream(file, true).bufferedWriter()
    }

    fun append(ptsMs: Long, output: FrameOutput) {
        writer.write(DetectionLogFormat.format(ptsMs, output))
        writer.newLine()
    }

    fun flush() = writer.flush()

    override fun close() = writer.close()
}
