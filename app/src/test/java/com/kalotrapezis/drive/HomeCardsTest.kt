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
        assertEquals(at(9), HomeCards.toTake(doses, now = at(12)))
        assertNull(HomeCards.toTake(listOf(Dose(med, at(8), Dose.State.Taken), Dose(med, at(9), Dose.State.Skipped)), now = at(12)))
        // 8 Nov: at 11:38 «Το πήρα» would have taken the 14:00 dose; a later dose waits for its time (half an hour before at most)
        val later = listOf(Dose(med, at(8), Dose.State.Taken), Dose(med, at(14), Dose.State.Waiting))
        assertNull(HomeCards.toTake(later, now = at(11).plusSeconds(38 * 60)))
        assertEquals(at(14), HomeCards.toTake(later, now = at(13).plusSeconds(40 * 60)))
        assertEquals("1/2", HomeCards.takenOf(later))
        assertEquals("09:00  Vitamin D × 1", HomeCards.doseLine(doses[2], "09:00", private = false))
        assertEquals("09:00", HomeCards.doseLine(doses[2], "09:00", private = true))
    }

    @Test fun `the pill button takes the next dose not answered, whenever it is (8 Nov)`() {
        val med = Medicine("pill-x", "Vitamin D", times = listOf(LocalTime.of(9, 0)))
        val at = { h: Long -> Instant.parse("2026-11-08T00:00:00Z").plusSeconds(h * 3600) }
        assertEquals(at(14), HomeCards.nextToTake(listOf(Dose(med, at(8), Dose.State.Taken), Dose(med, at(21), Dose.State.Waiting), Dose(med, at(14), Dose.State.Waiting)))?.due)
        assertNull(HomeCards.nextToTake(listOf(Dose(med, at(8), Dose.State.Taken))))
    }

    @Test fun `history squares - strong on time, light late, empty missed, nothing for doses to come`() {
        val med = Medicine("pill-x", "Vitamin D", times = listOf(LocalTime.of(9, 0)))
        val at = { h: Long -> Instant.parse("2026-11-08T00:00:00Z").plusSeconds(h * 3600) }
        val doses = listOf(Dose(med, at(9), Dose.State.Taken, at(9).plusSeconds(10 * 60)), Dose(med, at(14), Dose.State.Taken, at(15)),
            Dose(med, at(8), Dose.State.Missed), Dose(med, at(21), Dose.State.Waiting), Dose(med, at(10), Dose.State.Skipped))
        assertEquals(listOf(0, 2, 0, 1), HomeCards.waffle(doses)) // in time order: 08 missed, 09 on time, 10 skipped, 14 an hour late
    }

    @Test fun `text on a card's colour - dark on light colours, white on deep ones`() {
        assertEquals(androidx.compose.ui.graphics.Color.White, HomeCards.onColor(androidx.compose.ui.graphics.Color(0xFF00796B)))
        assertEquals(androidx.compose.ui.graphics.Color(0xFF1B1B1B), HomeCards.onColor(androidx.compose.ui.graphics.Color(0xFF8ED11F)))
    }

    @Test fun `urgency dot - none for 0, a colour for 1 to 3`() {
        assertNull(HomeCards.urgencyColor(0))
        assertEquals(3, (1..3).mapNotNull { HomeCards.urgencyColor(it) }.toSet().size)
    }

    @Test fun `missed calls - two days only, none from before the clear, newest first`() {
        val now = 10 * 86_400_000L; val h = 3_600_000L
        fun call(id: Long, at: Long, type: Int = android.provider.CallLog.Calls.MISSED_TYPE) = LoggedCall(id, "69$id", at, 0, type)
        val log = listOf(call(1, now - h), call(2, now - 30 * h), call(3, now - 50 * h), call(4, now - 2 * h, android.provider.CallLog.Calls.INCOMING_TYPE), call(5, now - 3 * h))
        assertEquals(listOf(1L, 5L), HomeCards.missedToShow(log, 0, now).map { it.id })
        assertEquals(listOf(1L, 5L, 2L), HomeCards.missedToShow(log, 0, now, n = 9).map { it.id })
        assertEquals(listOf(1L), HomeCards.missedToShow(log, now - 2 * h, now).map { it.id })
        assertEquals(emptyList<Long>(), HomeCards.missedToShow(log, now, now).map { it.id })
    }
}
