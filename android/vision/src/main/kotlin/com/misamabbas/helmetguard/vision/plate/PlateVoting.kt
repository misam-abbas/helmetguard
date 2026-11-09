package com.misamabbas.helmetguard.vision.plate

enum class PlateStatus { READ, LOW_CONFIDENCE, UNREADABLE, NO_PLATE_DETECTED }

data class PlateReading(val text: String, val confidence: Float, val formatValid: Boolean)

data class PlateAlternative(val text: String, val confidence: Float)

data class PlateResult(
    /** Empty whenever the plate is unreadable: the app never guesses. */
    val text: String,
    val confidence: Float,
    val status: PlateStatus,
    val alternatives: List<PlateAlternative>,
)

data class PlateVoteSettings(val readConfidence: Float = 0.80f, val minConfidence: Float = 0.50f)

class PlateVoting(private val settings: PlateVoteSettings = PlateVoteSettings()) {

    /**
     * Votes across the crops of one plate. [cropCount] includes crops where OCR found
     * nothing, so a single confident read among five crops cannot pass as certain.
     */
    fun vote(readings: List<PlateReading>, cropCount: Int): PlateResult {
        if (cropCount == 0) return PlateResult("", 0f, PlateStatus.NO_PLATE_DETECTED, emptyList())

        val usable = readings.filter { it.text.isNotEmpty() }
        if (usable.isEmpty()) return PlateResult("", 0f, PlateStatus.UNREADABLE, emptyList())

        val winner = majorityString(usable)
        val agreeing = usable.filter { it.text == winner }
        val agreement = agreeing.size.toFloat() / cropCount
        val meanConfidence = if (agreeing.isEmpty()) 0f else agreeing.map { it.confidence }.average().toFloat()
        val confidence = (agreement + meanConfidence) / 2f
        val valid = agreeing.any { it.formatValid }

        val alternatives = usable.groupBy { it.text }
            .map { (text, group) -> PlateAlternative(text, (group.size.toFloat() / cropCount + group.map { it.confidence }.average().toFloat()) / 2f) }
            .sortedByDescending { it.confidence }
            .take(3)

        return when {
            confidence < settings.minConfidence -> PlateResult("", confidence, PlateStatus.UNREADABLE, alternatives)
            confidence >= settings.readConfidence && valid -> PlateResult(winner, confidence, PlateStatus.READ, alternatives)
            else -> PlateResult(winner, confidence, PlateStatus.LOW_CONFIDENCE, alternatives)
        }
    }

    /** Character-by-character confidence-weighted vote, among readings of the commonest length. */
    private fun majorityString(readings: List<PlateReading>): String {
        val length = readings.groupingBy { it.text.length }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenByDescending { it.key })
            .first().key
        val sameLength = readings.filter { it.text.length == length }
        return buildString(length) {
            for (position in 0 until length) {
                val best = sameLength.groupBy { it.text[position] }
                    .maxByOrNull { (_, group) -> group.sumOf { it.confidence.toDouble() } }!!
                append(best.key)
            }
        }
    }
}
