package com.kalotrapezis.drive

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Another service in Mistral's place: the same chat as it takes it, Mistral's untouched; 16-bit voices as float samples. */
class AiProvidersTest {
    private val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "hi"))
        .put(JSONObject().put("role", "tool").put("name", "timer_start").put("tool_call_id", "c1").put("content", "ok"))
    private fun sent(p: AiProvider, ask: Mistral.() -> Unit): JSONObject {
        var body = JSONObject()
        Mistral({ _, b -> body = JSONObject(b); """{"choices":[{"message":{"role":"assistant","content":"{}"}}]}""" }, provider = p).ask()
        return body
    }

    @Test fun mistralsRequestIsAsItWas() {
        val b = sent(AiProviders.MISTRAL) { chat(messages, JSONArray(), choice = "any") }
        assertEquals(Mistral.CHAT, b.getString("model")); assertEquals("any", b.getString("tool_choice")); assertEquals(0.2, b.getDouble("temperature"), 0.0)
        assertEquals("timer_start", b.getJSONArray("messages").getJSONObject(1).getString("name"))
        assertFalse(b.has("reasoning_effort"))
    }

    @Test fun openAiGetsItsOwnModelAndRules() {
        val b = sent(AiProviders.OPENAI) { chat(messages, JSONArray(), choice = "any") }
        assertEquals("gpt-6-luna", b.getString("model")); assertEquals("required", b.getString("tool_choice")); assertEquals("none", b.getString("reasoning_effort"))
        assertFalse(b.has("temperature"))
        assertFalse(b.getJSONArray("messages").getJSONObject(1).has("name"))
        assertTrue(messages.getJSONObject(1).has("name")) // the chat's own messages are not changed
        val j = sent(AiProviders.GROQ) { complete(messages, json = true, model = Mistral.BUILT_IN) }
        assertEquals("openai/gpt-oss-120b", j.getString("model")); assertTrue(j.has("temperature"))
        assertEquals("Answer in JSON.", j.getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    @Test fun eachPartOffersOnlyWhoCanDoIt() {
        assertEquals(listOf("mistral", "openai", "groq"), AiProviders.choices(AiPart.CHAT).map { it.id })
        assertEquals(listOf("mistral", "openai", "groq", "elevenlabs"), AiProviders.choices(AiPart.LISTEN).map { it.id })
        assertEquals(listOf("mistral", "openai", "elevenlabs"), AiProviders.choices(AiPart.VOICE).map { it.id })
    }

    @Test fun sixteenBitSamplesBecomeFloats() {
        val b = byteArrayOf(0x00, 0x80.toByte(), 0xff.toByte(), 0x7f, 0x00, 0x40, 0x01)
        assertArrayEquals(floatArrayOf(-1f, 32767 / 32768f, 0.5f), AssistantNet.pcm16(b, b.size), 0f) // the odd byte waits
    }
}
