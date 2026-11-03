package com.kalotrapezis.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Directions' camera by car (carView): closer as the car slows, tilted further at speed, the junction cases as before. */
class MapsCarViewTest {
    private fun at(kmh: Double, toNext: Double = 5000.0, sign: Int = 0, j: Junction? = null) = carView(j, sign, toNext, kmh / 3.6)

    @Test fun bySpeedOnAStraightRoad() {
        assertEquals(17.25 to 45.0, at(0.0))
        assertEquals(16.75 to 45.0, at(30.0))
        assertEquals(16.0 to 50.0, at(70.0))
        assertEquals(15.0 to 60.0, at(120.0))
        // a smooth curve: never further in as the speed rises, never less tilted; in quarter and 5° steps
        val views = (0..140).map { at(it.toDouble()) }
        views.zipWithNext().forEach { (a, b) -> assertTrue("$a → $b", b.first <= a.first && b.second >= a.second) }
        views.forEach { (z, t) -> assertEquals(0.0, z * 4 % 1, 1e-9); assertEquals(0.0, t % 5, 1e-9) }
    }

    @Test fun aTurnNearKeepsTheCloserZoom() {
        assertEquals(16.5 to 60.0, at(120.0, toNext = 800.0)) // ~24 s ahead: not further out than 16.5, still looking ahead
        assertEquals(17.5 to 55.0, at(25.0, toNext = 100.0, sign = 2)) // a turn slowed for
        val busy = Junction(listOf(-170.0, -30.0, 0.0, 20.0, 90.0), 2, roundabout = false)
        assertEquals(17.75 to 55.0, at(40.0, toNext = 0.0, j = busy)) // at a busy crossing
        assertEquals(17.0 to 55.0, at(40.0, toNext = 150.0, j = busy)) // closer step by step from 250 m
        assertEquals(16.75 to 55.0, at(30.0, toNext = 240.0, j = busy)) // never out first: in town the speed's zoom is closer
    }
}
