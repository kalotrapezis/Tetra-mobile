package com.kalotrapezis.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class PillsTest {
    private val zone = ZoneId.of("Europe/Athens")
    private fun dir() = kotlin.io.path.createTempDirectory("pills").toFile()
    private fun at(day: String, time: String) = LocalDate.parse(day).atTime(LocalTime.parse(time)).atZone(zone).toInstant()
    private val evening = Medicine("pill-a", "Depon", "500 mg", 1.0, listOf(LocalTime.of(21, 0)), perBox = 20, stock = 10.0, refillDays = 7, counted = true)
    private val early = Instant.parse("2026-10-01T00:00:00Z")
    /** [m] saved with [n] pills in hand (the stock is worked out from the log). */
    private fun stocked(d: File, m: Medicine = evening, n: Double = 10.0): Medicine { val s = Pills.save(d, m); Pills.addStock(d, s, 0, n, null, early); return Pills.medicines(d).first { it.id == m.id } }

    @Test fun takenTwiceCountsOnce() {
        val d = dir(); stocked(d)
        val due = at("2026-11-07", "21:00")
        Pills.take(d, evening, due, due.plusSeconds(60)); Pills.take(d, evening, due, due.plusSeconds(120))
        assertEquals(9.0, Pills.medicines(d).single().stock, 0.0)
        Pills.undo(d, evening, due, due.plusSeconds(180))
        assertEquals(10.0, Pills.medicines(d).single().stock, 0.0)
        assertEquals(Dose.State.Waiting, Pills.doses(Pills.medicines(d), Pills.log(d), due, due.plusSeconds(60), due.plusSeconds(200), zone).single().state)
    }

    @Test fun skippingATakenDoseGivesItBack() {
        val d = dir(); stocked(d)
        val due = at("2026-11-07", "21:00")
        Pills.take(d, evening, due, due); Pills.skip(d, evening, due, due)
        assertEquals(10.0, Pills.medicines(d).single().stock, 0.0)
    }

    @Test fun yesterdaysForgottenDoseShowsInTheMorning() {
        val d = dir(); Pills.save(d, evening)
        val today = Pills.today(Pills.medicines(d), Pills.log(d), at("2026-11-08", "08:00"), zone)
        assertEquals(listOf(at("2026-11-07", "21:00") to Dose.State.Missed, at("2026-11-08", "21:00") to Dose.State.Waiting), today.map { it.due to it.state })
    }

    @Test fun ringsAtTheTimeThenAgainUntilAnswered() {
        val d = dir(); Pills.save(d, evening)
        val due = at("2026-11-07", "21:00")
        assertEquals(due, Pills.nextRing(Pills.medicines(d), Pills.log(d), at("2026-11-07", "20:00"), zone)?.first)
        assertEquals(due.plusSeconds(1800), Pills.nextRing(Pills.medicines(d), Pills.log(d), due, zone)?.first)
        Pills.take(d, evening, due, due.plusSeconds(60))
        assertEquals(at("2026-11-08", "21:00"), Pills.nextRing(Pills.medicines(d), Pills.log(d), due.plusSeconds(61), zone)?.first)
    }

    @Test fun aBoxAddsItsPillsAndStartsTheRefillOver() {
        val d = dir(); val m = stocked(d, evening.copy(refillAsked = 3))
        val more = Pills.addStock(d, m, 2, 0.5, null, Instant.now())
        assertEquals(50.5, more.stock, 0.0)
        assertEquals(0, more.refillAsked)
        assertEquals(50, more.daysLeft())
    }

    @Test fun lowWhenDaysLeftAtMostRefillDays() {
        assertTrue(!evening.low()) // 10 days left, refill at 7
        assertTrue(evening.copy(stock = 7.0).low())
        assertEquals(3, evening.copy(dose = 0.5, times = listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)), stock = 3.5).daysLeft())
        assertNull(evening.copy(times = emptyList()).daysLeft())
    }

    @Test fun findsByNameAndStrength() {
        val a = evening; val b = evening.copy(id = "pill-b", strength = "1000 mg")
        assertEquals(listOf(b), Pills.find(listOf(a, b), "depon", "1000mg"))
        assertEquals(2, Pills.find(listOf(a, b), "Depon").size)
        assertEquals(listOf(a), Pills.find(listOf(a), "το Depon των 500"))
        assertTrue(Pills.find(listOf(a), "Ponstan").isEmpty())
        assertEquals(listOf(a), Pills.find(listOf(a), "depon 2")) // one alone: the number said with it does not matter
        assertTrue(Pills.find(listOf(a), " ").isEmpty())
    }

    @Test fun fileRoundTrip() {
        val m = evening.copy(days = setOf(java.time.DayOfWeek.MONDAY), until = LocalDate.of(2026, 12, 1), stock = 0.0, counted = false, rev = 4, by = "Tab")
        assertEquals(m, Medicine.parse(m.json())) // the stock is never written: it comes from the log
        assertNotNull(PillEvent.parse(PillEvent(Instant.EPOCH, "pill-a", PillEvent.TAKEN, Instant.EPOCH, 1.0, 9.0).json()))
    }

    @Test fun aNewMedicineIsNotMissedOnItsFirstDay() {
        val d = dir(); Pills.save(d, evening.copy(times = listOf(LocalTime.of(8, 0), LocalTime.of(21, 0)), since = at("2026-11-07", "10:00")))
        val today = Pills.today(Pills.medicines(d), Pills.log(d), at("2026-11-07", "10:05"), zone)
        assertEquals(listOf(at("2026-11-07", "21:00")), today.map { it.due })
    }

    @Test fun undoGivesBackOnlyWhatCameOff() {
        val d = dir(); val m = stocked(d, evening, 0.5)
        val due = at("2026-11-07", "21:00")
        Pills.take(d, m, due, due); Pills.undo(d, m, due, due)
        assertEquals(0.5, Pills.medicines(d).single().stock, 0.0)
    }

    @Test fun notCountedIsNeverLow() {
        val m = Medicine("pill-x", "Vitamin D", times = listOf(LocalTime.of(9, 0)))
        assertNull(m.daysLeft()); assertTrue(!m.low())
        assertEquals(false, Medicine.parse(m.json())!!.counted)
        val d = dir(); Pills.save(d, m)
        assertTrue(Pills.setStock(d, m, 0.0, Instant.now()).counted) // a count of 0 is a count
    }

    @Test fun everyFewDaysFromTheDayItWasAdded() {
        val m = evening.copy(every = 7, since = at("2026-11-02", "10:00"))
        assertTrue(m.takenOn(LocalDate.parse("2026-11-02"), zone)); assertTrue(!m.takenOn(LocalDate.parse("2026-11-05"), zone)); assertTrue(m.takenOn(LocalDate.parse("2026-11-09"), zone))
        assertEquals(70, m.daysLeft()) // 10 pills, one a week
        assertEquals(m.copy(stock = 0.0, counted = false), Medicine.parse(m.json()))
    }

    @Test fun partsOfTheDay() {
        assertEquals(listOf(0, 1, 2, 3, 3), listOf("07:30", "13:30", "21:00", "22:30", "02:00").map { Pills.part(LocalTime.parse(it)) })
    }

    @Test fun takenLateMostDaysSuggestsTheRealTime() {
        val d = dir(); val m = Pills.save(d, evening.copy(since = at("2026-10-20", "10:00")))
        (0..8).forEach { k -> val due = at(LocalDate.of(2026, 10, 20).plusDays(k.toLong()).toString(), "21:00"); Pills.take(d, m, due, due.plusSeconds(85 * 60)) } // 22:25 most nights
        val slots = Pills.slots(Pills.medicines(d), Pills.log(d), at("2026-10-31", "12:00"), zone, 14)
        val advice = Pills.advice(slots, zone)
        assertEquals("move", advice.single().kind); assertEquals(LocalTime.of(22, 30), advice.single().time)
    }

    @Test fun aDosePutOffRingsThenAndNotBefore() {
        val d = dir(); Pills.save(d, evening)
        val due = at("2026-11-07", "21:00")
        val later = mapOf(due to due.plusSeconds(20 * 60).toEpochMilli())
        // 21:00 rang; put off 20 minutes: the next ring is 21:20, not 21:30's reminder, and none by the clock until then
        assertEquals(due.plusSeconds(20 * 60), Pills.nextRing(Pills.medicines(d), Pills.log(d), due.plusSeconds(60), zone, later)?.first)
        assertEquals(due.plusSeconds(30 * 60), Pills.nextRing(Pills.medicines(d), Pills.log(d), due.plusSeconds(21 * 60), zone, later)?.first)
        // until home: no ring by the clock that evening
        assertEquals(at("2026-11-08", "21:00"), Pills.nextRing(Pills.medicines(d), Pills.log(d), due.plusSeconds(60), zone, mapOf(due to Pills.AT_PLACE))?.first)
        Pills.setLater(d, due, Pills.AT_PLACE); assertEquals(Pills.AT_PLACE, Pills.later(d)[due]); Pills.setLater(d, due, null); assertTrue(Pills.later(d).isEmpty())
    }

    @Test fun twoDevicesTakingTheSameDoseCountOnce() {
        val d = dir(); stocked(d)
        val due = at("2026-11-07", "21:00")
        Pills.me = { "Phone" }; Pills.take(d, evening, due, due.plusSeconds(60))
        // the tablet pressed it too before they synced: its own log, the same dose
        File(d, "log.Tablet.jsonl").writeText(PillEvent(due.plusSeconds(90), evening.id, PillEvent.TAKEN, due, 1.0).json() + "\n")
        assertEquals(9.0, Pills.medicines(d).single().stock, 0.0)
        assertEquals(Dose.State.Taken, Pills.today(Pills.medicines(d), Pills.log(d), due.plusSeconds(200), zone).last().state)
        Pills.me = { android.os.Build.MODEL ?: "Android" }
    }

    @Test fun theHighestRevWinsAndADeleteStays() {
        val d = dir(); Pills.me = { "Phone" }
        val a = Pills.save(d, evening)
        Pills.me = { "Tablet" }; Pills.save(d, a.copy(name = "Depon Extra")) // rev 2 in the tablet's file
        assertEquals("Depon Extra", Pills.medicines(d).single().name)
        Pills.me = { "Phone" }; Pills.delete(d, Pills.medicines(d).single())
        assertTrue(Pills.medicines(d).isEmpty())
        assertEquals(setOf("pill-a.Phone.json", "pill-a.Tablet.json"), d.list()!!.filter { it.endsWith(".json") }.toSet())
        Pills.me = { android.os.Build.MODEL ?: "Android" }
    }

    @Test fun aCountAfterTakingStartsAgainFromIt() {
        val d = dir(); stocked(d)
        val due = at("2026-11-07", "21:00")
        Pills.take(d, evening, due, due)
        Pills.setStock(d, evening, 30.0, due.plusSeconds(3600)) // counted the drawer: 30
        Pills.take(d, evening, at("2026-11-08", "21:00"), at("2026-11-08", "21:05"))
        assertEquals(29.0, Pills.medicines(d).single().stock, 0.0)
        Pills.undo(d, evening, due, at("2026-11-08", "22:00")) // undoing a dose from before the count changes nothing
        assertEquals(29.0, Pills.medicines(d).single().stock, 0.0)
    }
}
