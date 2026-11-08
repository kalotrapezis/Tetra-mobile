package com.kalotrapezis.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalTime

class HomeCardsTest {
    private fun mail(id: String, date: Long, seen: Boolean = false, folder: String = "Inbox", gone: Boolean = false) =
        MailEntry(id, "a@b.gr", folder, date, "From $id", "x@y", emptyList(), "Subject $id", seen, false, 0, 0, "", true, "f", gone)

    @Test fun `timer tiles - pinned timers first, then the four times, four in all`() {
        val minutes = { m: Int -> "$m min" }
        assertEquals(listOf("6 min", "15 min", "20 min", "30 min"), HomeCards.timerSlots(emptyList(), minutes = minutes).map { it.label })
        val two = HomeCards.timerSlots(listOf("a" to "Tea", "b" to "Pasta"), minutes = minutes)
        assertEquals(listOf("Tea", "Pasta", "6 min", "15 min"), two.map { it.label })
        assertEquals(listOf("a", "b", null, null), two.map { it.id })
        assertEquals(listOf(null, null, 6, 15), two.map { it.minutes })
        assertEquals(4, HomeCards.timerSlots(List(6) { "$it" to "T$it" }, minutes = minutes).count { it.id != null })
    }

    @Test fun `mail - unread Inbox only, most urgent first then newest, and the count of all`() {
        val list = listOf(mail("old", 1), mail("new", 5), mail("urgent", 2), mail("read", 9, seen = true), mail("sent", 9, folder = "Sent"), mail("gone", 9, gone = true))
        val (n, top) = HomeCards.unread(list, mapOf("urgent" to 3, "read" to 3))
        assertEquals(3, n)
        assertEquals(listOf("urgent", "new", "old"), top.map { it.id })
        assertEquals(listOf("urgent", "new"), HomeCards.unread(list, mapOf("urgent" to 3), n = 2).second.map { it.id })
    }

    @Test fun `pills - the earliest waiting or missed dose is the one to take, and private hides the name`() {
        val med = Medicine("pill-x", "Vitamin D", times = listOf(LocalTime.of(9, 0)))
        val at = { h: Long -> Instant.parse("2026-11-08T00:00:00Z").plusSeconds(h * 3600) }
        val doses = listOf(Dose(med, at(8), Dose.State.Taken), Dose(med, at(14), Dose.State.Waiting), Dose(med, at(9), Dose.State.Missed), Dose(med, at(21), Dose.State.Waiting))
        assertEquals(at(9), HomeCards.toTake(doses))
        assertNull(HomeCards.toTake(listOf(Dose(med, at(8), Dose.State.Taken), Dose(med, at(9), Dose.State.Skipped))))
        assertEquals("09:00  Vitamin D × 1", HomeCards.doseLine(doses[2], "09:00", private = false))
        assertEquals("09:00", HomeCards.doseLine(doses[2], "09:00", private = true))
    }

    @Test fun `urgency dot - none for 0, a colour for 1 to 3`() {
        assertNull(HomeCards.urgencyColor(0))
        assertEquals(3, (1..3).mapNotNull { HomeCards.urgencyColor(it) }.toSet().size)
    }
}
