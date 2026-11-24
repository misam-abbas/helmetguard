package com.misamabbas.helmetguard.ui.home

import com.misamabbas.helmetguard.data.db.SourceTotals
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.ZoneId

/** The home screen's periods: the same three, counted the same way, as the website's dashboard. */
enum class Period(val label: String) {
    TODAY("Today"),
    WEEK("7 days"),
    MONTH("30 days"),
    ;

    /** Start of the period: midnight Pakistan time for today, as on the website, else 7 or 30 days back from now. */
    fun since(nowMs: Long): Long = when (this) {
        TODAY -> Instant.ofEpochMilli(nowMs).atZone(PAKISTAN).toLocalDate().atStartOfDay(PAKISTAN).toInstant().toEpochMilli()
        WEEK -> nowMs - 7 * DAY_MS
        MONTH -> nowMs - 30 * DAY_MS
    }

    companion object {
        /** The website counts days in Pakistan time, so the app does too, whatever the phone's own zone. */
        val PAKISTAN: ZoneId = ZoneId.of("Asia/Karachi")
        const val DAY_MS = 86_400_000L
    }
}

/**
 * The totals on the home screen. From the server they are exactly the website's dashboard numbers,
 * covering every synced device and the website's own video analyses; from this phone they cover
 * only its own records, which is what it shows when the server can't be reached.
 */
data class HomeStats(
    val motorcycles: Int,
    val ridersTotal: Int,
    val ridersHelmet: Int,
    val ridersNoHelmet: Int,
    val alerts: Int,
    val fromServer: Boolean,
) {
    companion object {
        val EMPTY = HomeStats(0, 0, 0, 0, 0, fromServer = false)

        fun local(totals: SourceTotals, alerts: Int) =
            HomeStats(totals.motorcycles, totals.ridersTotal, totals.ridersHelmet, totals.ridersNoHelmet, alerts, fromServer = false)

        /** From dashboard_summary, the database function behind the website's dashboard. */
        fun fromSummary(summary: JsonObject): HomeStats {
            fun count(key: String) = summary[key]?.jsonPrimitive?.content?.toDoubleOrNull()?.toInt() ?: 0
            return HomeStats(count("motorcycles"), count("riders"), count("riders_helmet"), count("riders_no_helmet"), count("ai_alerts"), fromServer = true)
        }
    }
}
