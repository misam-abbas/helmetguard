package com.misamabbas.helmetguard.ui.home

import com.misamabbas.helmetguard.data.db.SourceTotals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class HomeStatsTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun `today starts at midnight Pakistan time, as on the website`() {
        assertEquals(ms("2026-11-24T19:00:00Z"), Period.TODAY.since(ms("2026-11-25T01:30:00Z")))
        // 18:59 UTC is still the previous day in Pakistan; 19:00 UTC is midnight there.
        assertEquals(ms("2026-11-23T19:00:00Z"), Period.TODAY.since(ms("2026-11-24T18:59:59Z")))
        assertEquals(ms("2026-11-24T19:00:00Z"), Period.TODAY.since(ms("2026-11-24T19:00:00Z")))
    }

    @Test
    fun `7 and 30 days count back from now, as on the website`() {
        val now = ms("2026-11-25T01:30:00Z")
        assertEquals(ms("2026-11-18T01:30:00Z"), Period.WEEK.since(now))
        assertEquals(ms("2026-10-26T01:30:00Z"), Period.MONTH.since(now))
    }

    @Test
    fun `the server's summary becomes the website's numbers`() {
        val summary = Json.parseToJsonElement(
            """{"sessions": 4, "motorcycles": 31, "riders": 35, "riders_helmet": 20, "riders_no_helmet": 9, "riders_unknown": 6,
               "compliance_pct": 69.0, "ai_alerts": 7, "unreviewed": 5, "reviewed_confirmed": 2, "reviewed_rejected": 0}""",
        ).jsonObject
        val stats = HomeStats.fromSummary(summary)
        assertEquals(HomeStats(31, 35, 20, 9, 7, fromServer = true), stats)
        assertTrue(stats.fromServer)
    }

    @Test
    fun `missing numbers count as zero`() {
        assertEquals(HomeStats(0, 0, 0, 0, 0, fromServer = true), HomeStats.fromSummary(Json.parseToJsonElement("{}").jsonObject))
    }

    @Test
    fun `the phone's own totals are marked as not from the server`() {
        val stats = HomeStats.local(SourceTotals(motorcycles = 3, ridersHelmet = 2, ridersNoHelmet = 1, ridersTotal = 4), alerts = 1)
        assertEquals(3, stats.motorcycles)
        assertEquals(4, stats.ridersTotal)
        assertFalse(stats.fromServer)
    }
}
