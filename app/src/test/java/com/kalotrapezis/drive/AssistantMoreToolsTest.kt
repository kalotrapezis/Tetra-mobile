package com.kalotrapezis.drive

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** data_status, note_pin, note_color, open_url and brief_show (8 Nov), on a scratch Tetra folder. */
class AssistantMoreToolsTest {
    private val zone = ZoneId.of("Europe/Athens")
    private val now = ZonedDateTime.of(2026, 10, 29, 9, 0, 0, 0, zone)
    private val tetra = scratchDir("assistant-more")
    private fun tools(hotspot: String? = null) = AssistantTools(tetra, "Phone", zone, { now }, hotspotDevice = hotspot)
    private val data = File(tetra, TetraSystem.onDisk(DataFiles.FOLDER))

    private fun month(device: String, days: List<DataDay>, month: String = "2026-10") =
        File(File(data, "usage").apply { mkdirs() }, "$month.$device.jsonl").writeText(DataFiles.text(DataMonth(device, days, emptyList(), emptyList(), emptyList())))
    private fun phoneDays(each: Long) = (1..29).map { DataDay(LocalDate.of(2026, 10, it), each, 0, 0, 0, 0, false) }

    @Test fun dataOnTrackSaysSoWithTheFigures() {
        File(data.apply { mkdirs() }, "plan.json").writeText(DataPlan().toJson()) // 10 GB, renews on the 1st
        month("P", phoneDays(100_000_000))
        val r = tools().run("data_status", JSONObject())
        assertTrue(r.forModel, r.forModel.startsWith("2.9 GB of 10 GB used (29%)"))
        assertTrue(r.forModel, r.forModel.contains("3 days left") && r.forModel.endsWith("on track."))
        val c = r.cards.single() as ResultCard.Data
        assertEquals(2_900_000_000L, c.used); assertEquals(0L, c.cut); assertEquals(3, c.daysLeft); assertEquals(100_000_000L, c.average)
    }

    @Test fun dataGoingOverSaysHowMuchADayKeepsItWithin() {
        File(data.apply { mkdirs() }, "plan.json").writeText(DataPlan().toJson())
        month("P", phoneDays(330_000_000)) // 9.57 GB by the 29th: the forecast goes over 10
        val r = tools().run("data_status", JSONObject())
        assertTrue(r.forModel, r.forModel.contains("use at most 143 MB a day for the 3 days left"))
        assertEquals(143_333_333L, (r.cards.single() as ResultCard.Data).cut)
    }

    @Test fun aTabletWithoutASimAnswersItsHotspotUseThisMonth() {
        month("T", listOf(DataDay(LocalDate.of(2026, 10, 3), 0, 0, 0, 0, 600_000_000, false), DataDay(LocalDate.of(2026, 10, 20), 0, 0, 0, 0, 400_000_000, false)))
        month("T", listOf(DataDay(LocalDate.of(2026, 9, 20), 0, 0, 0, 0, 900_000_000, false)), "2026-09")
        val r = tools("T").run("data_status", JSONObject())
        assertTrue(r.forModel, r.forModel.contains("1.0 GB over the phone's hotspot"))
        assertTrue((r.cards.single() as ResultCard.Data).hotspot)
        assertTrue(tools().run("data_status", JSONObject()).forModel.startsWith("No data plan"))
    }

    @Test fun theNoteJustMadeOrOneFoundIsPinnedAndColouredAsNotesWritesThem() {
        val t = tools()
        t.run("note_new", JSONObject().put("text", "Ο κωδικός της αποθήκης"))
        assertEquals("\"Ο κωδικός της αποθήκης\" is blue now.", t.run("note_color", JSONObject().put("color", "blue")).forModel)
        val store = NotesStore(TetraSystem.dir(tetra, "Notes", "Shared"), "test")
        val id = store.list().single().id
        assertEquals("#CBF0F8", store.get(id)!!.color)
        assertTrue(t.run("note_color", JSONObject().put("color", "μωβ")).forModel.startsWith("Notes has these colours"))
        t.run("note_color", JSONObject().put("color", "none"))
        assertNull(store.get(id)!!.color)
        t.run("notes_search", JSONObject().put("query", "αποθηκ"))
        assertEquals("Pinned \"Ο κωδικός της αποθήκης\".", t.run("note_pin", JSONObject().put("choice", 1)).forModel)
        assertTrue(store.get(id)!!.pinned)
        t.run("note_pin", JSONObject().put("choice", 1).put("pinned", false))
        assertFalse(store.get(id)!!.pinned)
        assertTrue(t.run("note_pin", JSONObject().put("choice", 4)).forModel.startsWith("No choice 4"))
        assertTrue(tools().run("note_pin", JSONObject()).forModel.startsWith("Which note?"))
    }

    @Test fun aWebAddressOpensInTetrasBrowserAndNothingElseDoes() {
        val go = tools().run("open_url", JSONObject().put("url", "example.org/a?b=1")).go!!
        assertTrue(go.startsWith("browser-play:"))
        JSONObject(go.removePrefix("browser-play:")).let { assertEquals("https://example.org/a?b=1", it.getString("url")); assertTrue(it.getBoolean("open")) }
        assertEquals("https://x.gr", JSONObject(tools().run("open_url", JSONObject().put("url", "https://x.gr")).go!!.removePrefix("browser-play:")).getString("url"))
        listOf("javascript:alert(1)", "file:///sdcard/a", "two words").forEach { assertNull(it, tools().run("open_url", JSONObject().put("url", it)).go) }
    }

    @Test fun theLatestBriefIsACardWithItsFirstLines() {
        assertEquals("No daily brief has come yet.", tools().run("brief_show", JSONObject()).forModel)
        val dir = File(TetraSystem.dir(tetra, "Assistant", "Shared"), "Briefs").apply { mkdirs() }
        File(dir, "latest.json").writeText(JSONObject().put("at", now.toInstant().toEpochMilli()).put("title", "Πέμπτη 29 Οκτ")
            .put("text", "Σήμερα δύο ραντεβού.\n\nΤρία mail.\nΒροχή.\nΖέστη.\nΤέλος.").put("file", "2026-10-29.html").toString())
        val r = tools().run("brief_show", JSONObject())
        val c = r.cards.single() as ResultCard.Brief
        assertEquals("Πέμπτη 29 Οκτ", c.title); assertEquals(listOf("Σήμερα δύο ραντεβού.", "Τρία mail.", "Βροχή.", "Ζέστη."), c.lines)
        assertEquals(File(dir, "2026-10-29.html"), c.file)
        assertTrue(r.forModel, r.forModel.contains("Τέλος."))
    }

    @Test fun eachNewToolIsTaggedOfferedAndInItsApp() {
        listOf("note_pin", "note_color", "open_url").forEach { assertTrue(it, it in AssistantGuard.ACTS) }
        assertTrue("brief_show" in AssistantGuard.EXTERNAL) // the brief retells mails and the web
        assertFalse("data_status" in AssistantGuard.ACTS || "data_status" in AssistantGuard.EXTERNAL)
        assertEquals("notes", AssistantTools.APP_OF["note_pin"]); assertEquals("notes", AssistantTools.APP_OF["note_color"]); assertEquals("browser", AssistantTools.APP_OF["open_url"])
        val names = (0 until AssistantTools.SCHEMA.length()).map { AssistantTools.SCHEMA.getJSONObject(it).getJSONObject("function").getString("name") }
        assertTrue(names.containsAll(listOf("data_status", "note_pin", "note_color", "open_url", "brief_show")))
    }
}
