package com.kalotrapezis.drive

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Private reminders, the reminder's line in AI Assist's open chat, and pills_taken reading back what it wrote (8 Nov). */
class PillsChatTest {
    private val zone = ZoneId.of("Europe/Athens")
    private val str = Str { id, a -> "$id:" + a.joinToString("|") }
    private val med = Medicine("pill-a", "Depon", "500 mg", 1.0, listOf(LocalTime.of(8, 0)), perBox = 20, stock = 10.0, refillDays = 7, counted = true)
    private val now = ZonedDateTime.of(2026, 11, 8, 8, 5, 0, 0, zone)
    private val dose = Dose(med, now.withMinute(0).toInstant(), Dose.State.Waiting)

    @Test fun privateNamesNoMedicine() {
        val spoken = PillsAlarms.words(str, "Θεολόγε", "08:00", "Depon", emptyList(), private = true)
        assertEquals("${R.string.pills_voice_line_private}:Θεολόγε|08:00", spoken)
        assertEquals("${R.string.pills_voice_line_private_plain}:08:00", PillsAlarms.words(str, " ", "08:00", "Depon", emptyList(), private = true))
        assertTrue(PillsAlarms.words(str, "Θεολόγε", "08:00", "Depon", emptyList(), private = false).contains("Depon"))
        assertFalse(PillsAlarms.refillLine(str, med, private = true).contains("Depon"))
        assertTrue(PillsAlarms.refillLine(str, med, private = false).contains("Depon"))
        val (model, shown) = PillsAlarms.chatLines(str, "08:00", listOf(dose), private = true)
        assertTrue(model.contains("Depon")) // the model must know which dose «το πήρα» means
        assertFalse(shown.contains("Depon"))
    }

    @Test fun aReplyAfterARingGoesOnWithTheSameChat() {
        val f = File(kotlin.io.path.createTempDirectory("chat").toFile(), "assistant_open.json")
        val t = 1_800_000_000_000L
        SharedChat.load(f, t)
        SharedChat.at = t - 9 * 60_000L; SharedChat.save(f) // a chat nine minutes old
        val id = SharedChat.id
        SharedChat.ring(f, "Reminder: time for Depon × 1 (dose 08:00)", "Reminder: Depon (dose 08:00)", t)
        // the process gone meanwhile: what was kept comes back from the file
        val again = File(f.parentFile, "copy.json").also { f.copyTo(it) }
        SharedChat.load(again, t + 2 * 60_000L) // two minutes after the ring: 11 minutes after the chat's last word
        assertEquals(id, SharedChat.id)
        assertEquals("Reminder: Depon (dose 08:00)", SharedChat.bubbles.last().text)
        val m = SharedChat.kept().getJSONObject(SharedChat.kept().length() - 1)
        assertEquals("assistant", m.getString("role")); assertTrue(m.getString("content").contains("Depon"))
        // past ten minutes after the ring: a new conversation
        SharedChat.load(again, t + 11 * 60_000L)
        assertNotEquals(id, SharedChat.id)
        assertTrue(SharedChat.bubbles.isEmpty()); assertEquals(0, SharedChat.kept().length())
    }

    @Test fun takenIsReadBackAndNothingWaitingIsSaid() {
        val tetra = kotlin.io.path.createTempDirectory("pillschat").toFile()
        Pills.save(Pills.dir(tetra), med)
        val tools = AssistantTools(tetra, "Phone", zone, { now })
        val first = tools.run("pills_taken", JSONObject()).forModel
        assertTrue(first, first.startsWith("Marked taken (read back from Pills): Depon 500 mg of 08:00"))
        assertFalse(first, first.contains("NOT MARKED"))
        val second = tools.run("pills_taken", JSONObject()).forModel
        assertTrue(second, second.startsWith("No dose is waiting now, so nothing was marked taken"))
        assertTrue(second, second.contains("Depon 500 mg of 08:00, taken at 08:05"))
    }
}
