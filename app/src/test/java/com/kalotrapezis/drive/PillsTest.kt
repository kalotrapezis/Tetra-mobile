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

    @Test fun takenTwiceCountsOnce() {
        val d = dir(); Pills.save(d, evening)
        val due = at("2026-11-07", "21:00")
        Pills.take(d, evening, due, due.plusSeconds(60)); Pills.take(d, evening, due, due.plusSeconds(120))
        assertEquals(9.0, Pills.medicines(d).single().stock, 0.0)
        Pills.undo(d, evening, due, due.plusSeconds(180))
        assertEquals(10.0, Pills.medicines(d).single().stock, 0.0)
        assertEquals(Dose.State.Waiting, Pills.doses(Pills.medicines(d), Pills.log(d), due, due.plusSeconds(60), due.plusSeconds(200), zone).single().state)
    }

    @Test fun skippingATakenDoseGivesItBack() {
        val d = dir(); Pills.save(d, evening)
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
        val d = dir(); val m = Pills.save(d, evening.copy(refillAsked = 3))
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
        val m = evening.copy(days = setOf(java.time.DayOfWeek.MONDAY), until = LocalDate.of(2026, 12, 1), stock = 2.5)
        assertEquals(m, Medicine.parse(m.json()))
        assertNotNull(PillEvent.parse(PillEvent(Instant.EPOCH, "pill-a", PillEvent.TAKEN, Instant.EPOCH, 1.0, 9.0).json()))
    }

    @Test fun aNewMedicineIsNotMissedOnItsFirstDay() {
        val d = dir(); Pills.save(d, evening.copy(times = listOf(LocalTime.of(8, 0), LocalTime.of(21, 0)), since = at("2026-11-07", "10:00")))
        val today = Pills.today(Pills.medicines(d), Pills.log(d), at("2026-11-07", "10:05"), zone)
        assertEquals(listOf(at("2026-11-07", "21:00")), today.map { it.due })
    }

    @Test fun undoGivesBackOnlyWhatCameOff() {
        val d = dir(); val m = Pills.save(d, evening.copy(stock = 0.5))
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
        assertEquals(m, Medicine.parse(m.json()))
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
}
