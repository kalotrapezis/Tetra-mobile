package com.kalotrapezis.drive

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
}
