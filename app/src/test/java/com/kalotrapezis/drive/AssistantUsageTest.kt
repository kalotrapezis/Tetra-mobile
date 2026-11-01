package com.kalotrapezis.drive

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

/** AI Assist's usage record (the desktop's usage.test.js): each paid call a tagged line; read back across devices and older lines; the sums; the budget. */
class AssistantUsageTest {
    private val zone = ZoneId.of("Europe/Athens")
    private val at = ZonedDateTime.of(2026, 11, 1, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
    private fun usage(dir: File, device: String = "pc") = Usage(dir, device, zone) { at }

    @Test fun eachPaidCallIsATaggedLineAndPartsCountToTheirOwner() {
        val dir = scratchDir("usage"); val u = usage(dir, "my phone"); val metered = mutableListOf<Double>()
        val bill = Bill({ metered += it }, { u.add(it) })
        bill.tokens(Mistral.CHAT, JSONObject().put("prompt_tokens", 1000).put("completion_tokens", 500))
        val job = bill.part("audio", mapOf("file" to "Μάθημα.pdf", "job" to "j1"))
        job.part("tts").chars("α".repeat(1000))
        bill.part("live").minutes(Mistral.REALTIME, 60.0)
        Mistral({ _, _ -> """{"outputs":[],"usage":{"prompt_tokens":0,"completion_tokens":0}}""" }, bill).tagged("web").conversation("q", "web_search", "")
        val lines = File(dir, "my-phone-2026-11.jsonl").readLines().map(::JSONObject)
        assertEquals(listOf("chat", "tts", "live", "web"), lines.map { it.getString("f") })
        assertEquals(listOf(1, 1, 1, 1), lines.map { it.getInt("v") })
        assertEquals(listOf(Mistral.CHAT, Mistral.TTS, Mistral.REALTIME, "web_search"), lines.map { it.getString("m") })
        assertEquals(1000, lines[0].getInt("i")); assertEquals(500, lines[0].getInt("o"))
        assertEquals(1000, lines[1].getInt("c")); assertEquals("Μάθημα.pdf", lines[1].getString("file")); assertEquals("j1", lines[1].getString("job"))
        assertEquals(60.0, lines[2].getDouble("s"), 0.0)
        assertEquals(0.016, job.usd, 1e-9)
        assertEquals(0.00025 + 0.016 + 0.006 + 0.03, bill.usd, 1e-9) // the chat's bill holds its parts
        assertEquals(4, metered.size) // the meter counts each once
    }

    @Test fun olderAndBrokenLinesAndOtherDevicesAreSummed() {
        val dir = scratchDir("usage").apply { mkdirs() }; val u = usage(dir)
        File(dir, "phone-2026-10.jsonl").writeText("{\"at\":${at - 5 * 86_400_000},\"feature\":\"chat\",\"model\":\"mistral-small-latest\",\"usd\":0.5}\n{\"v\":1,\"t\":1,\"f\":\"ch")
        u.add(mapOf("f" to "chat", "m" to Mistral.CHAT, "usd" to 0.25))
        u.add(mapOf("f" to "tts", "m" to Mistral.TTS, "c" to 900, "usd" to 0.75, "file" to "Μάθημα.pdf", "job" to "j1"))
        u.add(mapOf("f" to "mp3", "file" to "Μάθημα.pdf", "title" to "Μάθημα — Εκπομπή", "s" to 60, "job" to "j1", "cost" to 0.75))
        val all = u.lines()
        assertEquals(4, all.size)
        all.single { it.device == "phone" }.let { assertEquals(0, it.v); assertEquals("chat", it.f); assertEquals(Mistral.CHAT, it.m); assertEquals(at - 5 * 86_400_000, it.t) }
        val s = u.summary()
        assertEquals(listOf(1.0, 1.0, 1.5), listOf(s.today, s.month, s.all))
        assertEquals(30, s.days.size); assertEquals(1.0, s.days.last().second, 1e-9); assertEquals(0.5, s.days[24].second, 1e-9)
        assertEquals(setOf(Triple("chat", 0.75, 2), Triple("tts", 0.75, 1)), s.byFeature.map { Triple(it.key, it.usd, it.n) }.toSet())
        assertEquals(listOf("Μάθημα.pdf" to 0.75), s.files.map { it.key to it.usd })
        assertEquals(listOf(Triple("Μάθημα — Εκπομπή", 60.0, 0.75)), s.mp3s.map { Triple(it.title, it.seconds, it.usd) }) // its whole cost, counted once
        assertEquals(setOf("pc", "phone"), s.byDevice.map { it.key }.toSet())
    }

    @Test fun theBudgetWarnsOnceAndCaps() {
        val dir = scratchDir("usage"); val u = usage(dir)
        assertFalse(u.capped()) // no budget, no cap
        u.setBudget(1.0)
        u.add(mapOf("f" to "chat", "usd" to 0.5)); assertFalse(u.warnNow())
        u.add(mapOf("f" to "chat", "usd" to 0.35)); assertTrue(u.warnNow()); assertFalse(u.warnNow()) // once a month
        assertFalse(u.capped())
        u.add(mapOf("f" to "chat", "usd" to 0.2)); assertTrue(u.capped())
        assertTrue(usage(dir, "phone").capped()) // the same budget on every device
        JSONObject(File(dir, "budget.json").readText()).let { assertEquals(1, it.getInt("v")); assertEquals(1.0, it.getDouble("monthly"), 0.0); assertEquals("2026-11", it.getString("warned")) }
        u.setBudget(0.0); assertFalse(u.capped()) // 0: no budget
    }
}
