package com.kalotrapezis.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class OpenHoursTest {
    // Friday 20 Nov 2026
    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, d, h, m)

    @Test fun aSupermarket() {
        val h = "Mo-Fr 08:00-21:00; Sa 08:00-20:00; Su off"
        assertEquals(OpenHours.Now(true, at(20, 21)), OpenHours.now(h, at(20, 17, 38)))
        assertEquals(OpenHours.Now(false, at(21, 8)), OpenHours.now(h, at(20, 22)))
        assertEquals(OpenHours.Now(false, at(23, 8)), OpenHours.now(h, at(21, 20, 30))) // Saturday evening: Monday
    }

    @Test fun lunchBreakAndPastMidnight() {
        assertEquals(OpenHours.Now(false, at(20, 17)), OpenHours.now("Mo-Sa 08:00-14:00,17:00-21:00", at(20, 15)))
        assertEquals(OpenHours.Now(true, at(21, 2)), OpenHours.now("Mo-Su 18:00-02:00", at(21, 1)))
        assertEquals(OpenHours.Now(true, null), OpenHours.now("24/7", at(20, 3)))
        assertEquals(OpenHours.Now(true, at(23, 0)), OpenHours.now("Fr-Mo 09:00-14:00; Sa,Su 00:00-24:00", at(21, 12))) // all weekend, to Monday 00:00
    }

    @Test fun notUnderstoodShowsNothing() {
        assertNull(OpenHours.now("", at(20, 12)))
        assertNull(OpenHours.now("Jan-Mar Mo-Fr 08:00-12:00", at(20, 12)))
        assertNull(OpenHours.now("sunrise-sunset", at(20, 12)))
    }
}
