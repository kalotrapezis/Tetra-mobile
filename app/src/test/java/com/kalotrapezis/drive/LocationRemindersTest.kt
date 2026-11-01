package com.kalotrapezis.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocationRemindersTest {
    private val shop = PlaceReminder("a", "supermarket", "", 0.0, 0.0, "Ψώνια")
    private val home = PlaceReminder("b", "", "Σπίτι", 40.64, 22.94, "")
    private val pois = listOf(
        Poi("supermarket", 40.6401, 22.9441, "Near"), Poi("supermarket", 40.70, 22.94, "Far"),
        Poi("supermarket", 41.5, 22.94, "Too far"), Poi("pharmacy", 40.6400, 22.9440, "Not a shop"),
    )

    @Test fun `each kind watches its nearest shops, a spot is always watched`() {
        val w = LocationReminders.targets(listOf(shop, home), pois, 40.64, 22.944)
        assertEquals(listOf("Near", "Σπίτι", "Far"), w.map { it.name })
    }

    @Test fun `without a location only the spots`() =
        assertEquals(listOf("Σπίτι"), LocationReminders.targets(listOf(shop, home), pois, null, null).map { it.name })

    private fun note(id: String, title: String, at: Long, vararg items: Pair<String, Boolean>, trashed: Long? = null) = Note(id, title, "", true,
        items.mapIndexed { i, (t, c) -> CheckItem("$i", t, c, i) }, emptyList(), null, false, null, trashed, 0, at)

    @Test fun `the newest checklist of those words with something left`() {
        val notes = listOf(note("1", "Ψώνια", 1, "γάλα" to false), note("2", "ψωνια σαββατου", 3, "ψωμί" to false),
            note("3", "Ψώνια", 5, "αυγά" to true), note("4", "Ψώνια", 9, "τυρί" to false, trashed = 9), note("5", "Δουλειές", 7, "τράπεζα" to false))
        assertEquals("2", LocationReminders.note(notes, "Ψώνια")?.id)
        assertEquals("5", LocationReminders.note(notes, "")?.id)
        assertNull(LocationReminders.note(notes, "φαρμακείο"))
    }
}
