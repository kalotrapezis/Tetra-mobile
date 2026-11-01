package com.kalotrapezis.drive

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * AI Assist's History (the user, 1 Nov 2026: "where is the history, the chats"): each conversation kept on this device
 * only, in the app's own storage, never synced: what was said is private to the phone it was said to. One file a chat:
 *
 *   files/assistant_chats/<id>.json = { id, at, title, bubbles: [{ mine, text, error }], messages: [the model's turns] }
 */
internal object AssistantChats {
    data class Saved(val id: String, val at: Long, val title: String, val bubbles: List<Triple<Boolean, String, Boolean>>, val messages: JSONArray)
    private const val KEEP = 100

    private fun dir(c: Context) = File(c.filesDir, "assistant_chats").apply { mkdirs() }

    fun save(c: Context, id: String, bubbles: List<Triple<Boolean, String, Boolean>>, messages: JSONArray) {
        if (bubbles.none { it.first }) return
        val title = bubbles.first { it.first }.second.replace(Regex("\\s+"), " ").take(80)
        val o = JSONObject().put("id", id).put("at", System.currentTimeMillis()).put("title", title).put("messages", messages)
            .put("bubbles", JSONArray(bubbles.map { JSONObject().put("mine", it.first).put("text", it.second).put("error", it.third) }))
        File(dir(c), "$id.json").writeText(o.toString())
        list(c).drop(KEEP).forEach { delete(c, it.id) } // the oldest go past a hundred
    }

    fun list(c: Context): List<Saved> = dir(c).listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
        runCatching {
            val o = JSONObject(f.readText()); val b = o.getJSONArray("bubbles")
            Saved(o.getString("id"), o.getLong("at"), o.optString("title"), (0 until b.length()).map { b.getJSONObject(it).let { x -> Triple(x.optBoolean("mine"), x.optString("text"), x.optBoolean("error")) } }, o.optJSONArray("messages") ?: JSONArray())
        }.getOrNull()
    }.sortedByDescending { it.at }

    fun delete(c: Context, id: String) { File(dir(c), "${File(id).name}.json").delete() }
}
