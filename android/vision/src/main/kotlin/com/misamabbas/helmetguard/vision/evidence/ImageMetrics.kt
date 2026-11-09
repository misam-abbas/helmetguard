package com.misamabbas.helmetguard.vision.evidence

object ImageMetrics {
    private const val BINS = 8

    /** Variance of the Laplacian over ARGB [pixels]: the standard blur measure. Higher is sharper. */
    fun laplacianVariance(pixels: IntArray, width: Int, height: Int): Double {
        if (width < 3 || height < 3 || pixels.size < width * height) return 0.0
        var sum = 0.0
        var sumSquares = 0.0
        var count = 0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                val value = 4 * luma(pixels[i]) - luma(pixels[i - 1]) - luma(pixels[i + 1]) - luma(pixels[i - width]) - luma(pixels[i + width])
                sum += value
                sumSquares += value.toDouble() * value
                count++
            }
        }
        val mean = sum / count
        return sumSquares / count - mean * mean
    }

    /** Normalised per-channel colour histogram, used to re-link a bike the tracker lost. */
    fun colorHistogram(pixels: IntArray): FloatArray {
        val histogram = FloatArray(BINS * 3)
        if (pixels.isEmpty()) return histogram
        val shift = 8 - Integer.numberOfTrailingZeros(BINS)
        for (pixel in pixels) {
            histogram[(pixel shr 16 and 0xFF) shr shift] += 1f
            histogram[BINS + ((pixel shr 8 and 0xFF) shr shift)] += 1f
            histogram[2 * BINS + ((pixel and 0xFF) shr shift)] += 1f
        }
        for (i in histogram.indices) histogram[i] /= pixels.size.toFloat()
        return histogram
    }

    private fun luma(pixel: Int): Int = ((pixel shr 16 and 0xFF) * 77 + (pixel shr 8 and 0xFF) * 150 + (pixel and 0xFF) * 29) shr 8
}
