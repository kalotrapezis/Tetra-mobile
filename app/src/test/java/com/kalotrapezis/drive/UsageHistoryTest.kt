package com.kalotrapezis.drive

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class UsageHistoryTest {
    private val zone = ZoneId.of("Europe/Athens")
    private fun at(y: Int, m: Int, d: Int) = ZonedDateTime.of(y, m, d, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
    private fun line(t: Long, usd: Double, f: String = "chat") = Usage.Line(t, f, "m", usd, 0.0, 0.0, "", "", "phone", 1)

    @Test fun `days, months and years add up, newest first, nothing older dropped, an MP3's repeat left out`() {
        val u = Usage(java.io.File("unused"), "phone", zone)
        val h = u.history(listOf(line(at(2025, 12, 31), 0.10), line(at(2026, 10, 1), 0.20), line(at(2026, 11, 8), 0.30), line(at(2026, 11, 8), 0.05),
            line(at(2026, 11, 8), 9.0, f = "mp3")))
        assertEquals(listOf("2026-11-08", "2026-10-01", "2025-12-31"), h.days.map { it.key })
        assertEquals(0.35, h.days.first().usd, 1e-9)
        assertEquals(2, h.days.first().n)
        assertEquals(listOf("2026-11", "2026-10", "2025-12"), h.months.map { it.key })
        assertEquals(listOf("2026", "2025"), h.years.map { it.key })
        assertEquals(0.55, h.years.first().usd, 1e-9)
    }
}
