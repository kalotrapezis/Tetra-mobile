package com.kalotrapezis.drive

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AssistantSchedulesTest {
    @Test fun aDeletedScheduleIsRememberedSoTheComputersMergeDoesNotBringItBack() {
        val tetra = Files.createTempDirectory("tetra").toFile()
        val s = AssistantSchedules.add(tetra, JSONObject().put("prompt", "p").put("time", "08:00"))
        AssistantSchedules.remove(tetra, s.getString("id"))
        val o = AssistantSchedules.read(tetra)
        assertEquals(1, o.getJSONArray("list").length()) // the brief
        assertTrue(o.getJSONObject("removed").getLong(s.getString("id")) >= s.getLong("created"))
        tetra.deleteRecursively()
    }

    // a scheduled action's permission (the user, 14 Nov): only the known groups are kept, and they name the tools
    @Test fun allowKeepsOnlyTheKnownGroups() {
        val tetra = Files.createTempDirectory("tetra").toFile()
        val s = AssistantSchedules.add(tetra, JSONObject().put("prompt", "p").put("time", "08:00").put("allow", JSONArray(listOf("calendar", "mail_write", "calendar", "files"))))
        assertEquals(listOf("calendar", "files"), (0 until s.getJSONArray("allow").length()).map { s.getJSONArray("allow").getString(it) })
        assertTrue("calendar_add" in AssistantGuard.allowed(listOf("calendar")) && "memory_add" !in AssistantGuard.allowed(AssistantGuard.ALLOW.keys.toList()))
        tetra.deleteRecursively()
    }

    // a chat from the computer: its tool calls go, what they found comes inside the answer, turns alternate
    @Test fun aForeignChatsToolTurnsAreToldAsContext() {
        val m = JSONArray()
            .put(JSONObject().put("role", "user").put("content", "Ψάξε προκηρύξεις"))
            .put(JSONObject().put("role", "assistant").put("content", "").put("tool_calls", JSONArray().put(JSONObject().put("id", "a"))))
            .put(JSONObject().put("role", "tool").put("name", "web_search").put("tool_call_id", "a").put("content", "ΑΣΕΠ 3Κ/2026"))
            .put(JSONObject().put("role", "assistant").put("content", "Βρήκα μία."))
        val out = AssistantChats.foreign(m)
        assertEquals(listOf("user", "assistant"), (0 until out.length()).map { out.getJSONObject(it).getString("role") })
        val said = out.getJSONObject(1).getString("content")
        assertTrue(said.contains("[web_search]\nΑΣΕΠ 3Κ/2026") && said.endsWith("Βρήκα μία.") && !out.getJSONObject(1).has("tool_calls"))
    }
}
