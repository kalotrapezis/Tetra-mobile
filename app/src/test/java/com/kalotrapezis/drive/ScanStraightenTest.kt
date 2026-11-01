package com.kalotrapezis.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScanStraightenTest {
    @Test fun `a turned picture is enlarged just enough to leave no corner empty`() {
        assertEquals(1f, ScanStraighten.cover(100, 200, 0f), 1e-4f)
        // a square turned 45° needs √2
        assertEquals(1.4142f, ScanStraighten.cover(100, 100, 45f), 1e-3f)
        assertEquals(ScanStraighten.cover(80, 120, 7f), ScanStraighten.cover(80, 120, -7f), 1e-6f)
    }

    @Test fun `the slope is the lines' median, not a stray caption's`() {
        fun line(y: Float, deg: Double, len: Float = 800f) = (0..5).map { k -> val x = k * len / 5; ScanPoint(x, y + x * Math.tan(Math.toRadians(deg)).toFloat()) }
        val lines = listOf(line(100f, 3.0), line(200f, 3.0), line(300f, 3.0), line(400f, -20.0, 100f))
        assertEquals(3f, ScanStraighten.slope(lines)!!, 0.01f)
        assertNull(ScanStraighten.slope(listOf(line(100f, 3.0))))
    }

    @Test fun `a page whose lines sag in the middle is pulled flat, a flat one is left alone`() {
        // lines y = c + 0.05·(x − 0.5)²·4, sagging 5% at the edges against the middle
        fun curved(c: Float) = (0..8).map { k -> val x = 0.1f + k * 0.1f; ScanPoint(x, c + 0.2f * (x - 0.5f) * (x - 0.5f)) }
        val grid = ScanStraighten.bend(listOf(curved(.2f), curved(.4f), curved(.6f), curved(.8f)))!!
        val n = ScanStraighten.GRID + 1
        // at a line's height, the shift between its edge and its middle is the sag itself
        val row = Math.round(.4f * ScanStraighten.GRID) * n
        val edge = grid[row + Math.round(.1f * ScanStraighten.GRID)]; val middle = grid[row + ScanStraighten.GRID / 2]
        assertEquals(0.2f * 0.4f * 0.4f, edge - middle, 0.01f)
        val flat = ScanStraighten.bend((1..4).map { i -> (0..8).map { k -> ScanPoint(0.1f + k * 0.1f, i * 0.2f + k * 0.01f) } })!!
        assert(flat.all { kotlin.math.abs(it) < 1e-4f }) { "a straight (if tilted) page must not be bent" }
        assertNull(ScanStraighten.bend(listOf(curved(.2f), curved(.4f))))
    }

    @Test fun `one odd line cannot fold the page`() {
        fun curved(c: Float, k: Float = 0.2f) = (0..8).map { i -> val x = 0.1f + i * 0.1f; ScanPoint(x, c + k * (x - 0.5f) * (x - 0.5f)) }
        val grid = ScanStraighten.bend(listOf(curved(.1f), curved(.25f), curved(.4f, -0.3f), curved(.55f), curved(.7f), curved(.85f)))!!
        val n = ScanStraighten.GRID + 1
        val row = Math.round(.4f * ScanStraighten.GRID) * n
        assertEquals(0.2f * 0.4f * 0.4f, grid[row + Math.round(.1f * ScanStraighten.GRID)] - grid[row + ScanStraighten.GRID / 2], 0.01f)
    }
}
