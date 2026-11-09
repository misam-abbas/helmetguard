package com.misamabbas.helmetguard.vision.plate

/** One accepted shape of letters followed by digits. Final list comes from real footage. */
data class PlateFormat(val minLetters: Int, val maxLetters: Int, val minDigits: Int, val maxDigits: Int)

data class NormalizedPlate(val text: String, val formatValid: Boolean, val substitutions: Int)

class PlateTextNormalizer(
    private val formats: List<PlateFormat> = listOf(PlateFormat(2, 4, 1, 4)),
    private val ignoredWords: Set<String> = DEFAULT_IGNORED_WORDS,
) {
    /** [lines] are OCR lines sorted top to bottom, so two-line plates stay in order. */
    fun normalize(lines: List<String>): NormalizedPlate {
        val compact = lines
            .flatMap { it.uppercase().split(Regex("[\\s\\-_.:]+")) }
            .map { word -> word.filter { it in 'A'..'Z' || it in '0'..'9' } }
            .filter { it.isNotEmpty() && it !in ignoredWords }
            .joinToString("")
            .let(::stripGluedWords)

        return bestFormatted(compact) ?: NormalizedPlate(compact, false, 0)
    }

    private fun stripGluedWords(text: String): String {
        var result = text
        for (word in ignoredWords.filter { it.length >= 5 }) {
            if (result.length > word.length && result.startsWith(word)) result = result.removePrefix(word)
            if (result.length > word.length && result.endsWith(word)) result = result.removeSuffix(word)
        }
        return result
    }

    /** Tries each letter/number split and keeps the one needing the fewest corrections. */
    private fun bestFormatted(text: String): NormalizedPlate? {
        var best: NormalizedPlate? = null
        for (format in formats) {
            for (letters in format.minLetters..format.maxLetters) {
                val digits = text.length - letters
                if (digits !in format.minDigits..format.maxDigits) continue
                val candidate = corrected(text, letters) ?: continue
                if (best == null || candidate.substitutions < best.substitutions) best = candidate
            }
        }
        return best
    }

    private fun corrected(text: String, letterCount: Int): NormalizedPlate? {
        val out = StringBuilder(text.length)
        var substitutions = 0
        text.forEachIndexed { index, ch ->
            val wantLetter = index < letterCount
            val fixed = when {
                wantLetter && ch in 'A'..'Z' -> ch
                !wantLetter && ch in '0'..'9' -> ch
                wantLetter -> DIGIT_TO_LETTER[ch]?.also { substitutions++ } ?: return null
                else -> LETTER_TO_DIGIT[ch]?.also { substitutions++ } ?: return null
            }
            out.append(fixed)
        }
        return NormalizedPlate(out.toString(), true, substitutions)
    }

    companion object {
        val DEFAULT_IGNORED_WORDS = setOf(
            "PUNJAB", "SINDH", "SIND", "BALOCHISTAN", "KPK", "KP", "KHYBER", "PAKHTUNKHWA",
            "ISLAMABAD", "ICT", "GOVT", "GOVERNMENT", "PAKISTAN", "PAK", "LAHORE", "KARACHI",
        )
        private val DIGIT_TO_LETTER = mapOf('0' to 'O', '1' to 'I', '5' to 'S', '8' to 'B', '2' to 'Z')
        private val LETTER_TO_DIGIT = mapOf('O' to '0', 'Q' to '0', 'D' to '0', 'I' to '1', 'L' to '1', 'S' to '5', 'B' to '8', 'Z' to '2')
    }
}
