package com.kalotrapezis.drive

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The turn card's crossing pictures, drawn as on the car's screen: written to files/junctions to look at (adb pull), with
 * junctions.png, a sheet of them all on the card's grey, each big (the phone's card) and small (Android Auto's corner).
 */
@RunWith(AndroidJUnit4::class)
class JunctionPictureDeviceTest {
    @Test fun draw() {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "junctions").apply { mkdirs() }
        // from MapsJunctionTest and JunctionGraphTest: real crossings and each kind of picture
        val cases = linkedMapOf(
            "drama-ethnikis-amynis (4 roads)" to Junction(listOf(-180.0, -109.0, -11.0, 69.0), 1, roads = listOf(1, 1, 1, 1)),
            "sharp-left-3-roads" to Junction(listOf(-180.0, -142.0, 0.0), 1),
            "sharp-right" to Junction(listOf(-180.0, -32.0, 113.0), 2),
            "slight-right" to Junction(listOf(-180.0, -90.0, 0.0, 32.0), 3),
            "t-junction-left" to Junction(listOf(-180.0, -90.0, 90.0), 1),
            "main-road-right" to Junction(listOf(-180.0, -90.0, 0.0, 90.0), 3, roads = listOf(0, 2, 0, 2)),
            "drama-five-roads" to Junction(listOf(-180.0, -127.0, -53.0, -2.0, 54.0), 4, roads = listOf(1, 0, 0, 1, 1)),
            "fork-keep-left" to Junction(listOf(-180.0, -6.0, 4.0), 1),
            "fork-keep-right" to Junction(listOf(-180.0, -20.0, 34.0), 2),
            "corinth-exit-left" to Junction(listOf(-180.0, -4.0, 0.5), 1, roads = listOf(2, -1, 2)),
            "motorway-exit-right" to Junction(listOf(-180.0, 0.0, 20.0), 2, roads = listOf(2, 2, -1)),
            "motorway-stay" to Junction(listOf(-180.0, 0.0, 20.0), 1, roads = listOf(2, 2, -1)),
            "u-turn-left" to Junction(listOf(-180.0, -179.0, -90.0, 90.0), 1),
            "u-turn-dual" to Junction(listOf(-180.0, -166.0, 0.0), 1, roads = listOf(2, 2, 1)),
            "staggered-left-right" to Junction(listOf(-180.0, -90.0, 90.0), 1, roads = listOf(0, 2, 2, 2, 0), then = listOf(-90.0, 0.0), thenTaken = 1),
            "5dromoi-exit2" to Junction(listOf(-180.0, -122.0, -37.0, 45.0, 107.0), 2, roundabout = true, exit = 2),
            "karaiskaki-exit2" to Junction(listOf(-180.0, -93.0, -5.0, 0.0, 89.0), 3, roundabout = true, exit = 2),
            "roundabout-exit4" to Junction(listOf(-180.0, -90.0, 0.0, 90.0), 0, roundabout = true, exit = 4),
            "roundabout-left-traffic" to Junction(listOf(-180.0, -90.0, 0.0, 90.0), 1, roundabout = true, exit = 1),
        )
        // the lanes strips: Ελαιώνας' exit, Κόρινθος' left fork to Τρίπολη, a town's turn lanes
        val strips = mapOf(
            "lanes-elaionas" to Lanes(MapsLanes.parse("through|through|slight_right"), setOf(2)),
            "lanes-corinth" to Lanes(MapsLanes.parse("slight_left|slight_left|through|through"), setOf(0, 1)),
            "lanes-town" to Lanes(MapsLanes.parse("left|through|through;right"), setOf(2)),
        )
        for ((name, l) in strips) File(dir, "$name.png").outputStream().use { lanesPicture(l, 120).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val big = 240; val small = 96; val cell = big + small + 36; val cols = 4; val rows = (cases.size + cols - 1) / cols
        val sheet = android.graphics.Bitmap.createBitmap(cols * cell, rows * (big + 44), android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(sheet).apply { drawColor(0xFF303134.toInt()) }
        val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8EAED.toInt(); textSize = 15f }
        for ((k, e) in cases.entries.withIndex()) {
            val (name, j) = e
            val b = junctionPicture(j, 400, left = name.contains("left-traffic"))
            File(dir, "${name.substringBefore(' ')}.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(b.width == 400)
            val x = (k % cols) * cell + 12f; val y = (k / cols) * (big + 44) + 8f
            c.drawBitmap(junctionPicture(j, big, left = name.contains("left-traffic")), x, y, null)
            c.drawBitmap(junctionPicture(j, small, left = name.contains("left-traffic")), x + big + 12, y + big - small, null)
            c.drawText(name, x, y + big + 26, text)
        }
        File(dir, "junctions.png").outputStream().use { sheet.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
