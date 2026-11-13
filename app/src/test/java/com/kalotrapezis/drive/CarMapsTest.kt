package com.kalotrapezis.drive

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CarMapsTest {
    private fun info(size: Long, modified: Long) = JSONObject().put("size", size).put("modified", modified)

    @Test fun mapsWhenDifferentPlacesOnlyWhenNewer() {
        val dir = Files.createTempDirectory("carmaps").toFile()
        fun mine(path: String, text: String, at: Long) = File(dir, path).apply { parentFile.mkdirs(); writeText(text); setLastModified(at) }
        mine("greece.pmtiles", "1234", 10_000)
        mine("Places/a.json", "car's own", 50_000) // changed on the unit: same age or newer than the phone's
        mine("Places/b.json", "old", 10_000)
        mine("Places/parking.json", "car", 10_000)
        val theirs = JSONObject()
            .put("greece.pmtiles", info(4, 10_000)) // the same
            .put("bulgaria.pmtiles", info(9, 10_000)) // a country more
            .put("Places/a.json", info(3, 40_000)) // other size, older: the unit's stays
            .put("Places/b.json", info(5, 60_000)) // newer on the phone
            .put("Places/parking.json", info(5, 90_000)) // the phone's parking: never the car's
            .put("Places/c.json", info(5, 1_000)) // missing here
            .put("@browser/Bookmarks/phone-1.json", info(5, 1_000))
        val got = CarMaps.wanted(theirs) { File(dir, it) }.map { it.first }
        assertEquals(listOf("@browser/Bookmarks/phone-1.json", "Places/b.json", "Places/c.json", "bulgaria.pmtiles"), got)
        assertEquals(listOf("bulgaria.pmtiles"), got.filterNot(CarMaps::personal))
        dir.deleteRecursively()
    }
}
