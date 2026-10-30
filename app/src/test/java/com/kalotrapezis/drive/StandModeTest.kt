package com.kalotrapezis.drive

import org.junit.Assert.assertEquals
import org.junit.Test

/** Stand mode's sunrise (StandMode.kt): none without an alarm or far from it, rising over the 20 minutes before it. */
class StandModeTest {
    private val ring = 1_000_000_000L

    @Test fun theSunriseRisesOverTwentyMinutesToTheRing() {
        assertEquals(0f, sunrise(0L, ring), 0f) // no alarm, or a skipped one
        assertEquals(0f, sunrise(ring, ring - SUNRISE_MS - 1), 0f)
        assertEquals(0.5f, sunrise(ring, ring - SUNRISE_MS / 2), 0.001f)
        assertEquals(1f, sunrise(ring, ring), 0f)
    }
}
