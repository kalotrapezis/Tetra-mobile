package com.kalotrapezis.drive

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The AI's switches (AiGate): off, nothing leaves; an app switched off is neither read nor acted in. */
class AiGateTest {
    private val tetra = java.nio.file.Files.createTempDirectory("tetra").toFile()
    @After fun done() { AiGate.set(AiGate.State(true), tetra); tetra.deleteRecursively() } // the next tests as before

    @Test fun noFileIsOffUnlessAKeyIsAlreadySet() {
        assertEquals(AiGate.State(false), AiGate.parse(null, hasKey = false))
        assertEquals(AiGate.State(true), AiGate.parse(null, hasKey = true))
        val s = AiGate.parse("""{"enabled":true,"apps":{"mail":false,"calendar":true}}""", hasKey = false)
        assertFalse(s.allows("mail")); assertTrue(s.allows("calendar")); assertTrue(s.allows("notes")) // a missing app is allowed
        assertFalse(AiGate.parse("""{"enabled":false}""", hasKey = true).allows(null)) // off is off, key or not
    }

    @Test fun theFileIsTheWholeStateWithWhoAndWhen() {
        AiGate.set(AiGate.State(true, mapOf("photos" to false)), tetra)
        val f = File(tetra, ".System/Assistant/Shared/ai.json")
        val o = org.json.JSONObject(f.readText())
        assertTrue(o.getBoolean("enabled")); assertFalse(o.getJSONObject("apps").getBoolean("photos")); assertTrue(o.getJSONObject("apps").getBoolean("mail"))
        assertTrue(o.getLong("setAt") > 0); assertTrue(o.has("setBy"))
        assertEquals(AiGate.State(true, AiGate.APPS.associateWith { it != "photos" }), AiGate.parse(f.readText(), hasKey = false))
        AiGate.set(AiGate.parse("""{"enabled":true,"apps":{"later":false}}""", false), tetra)
        assertFalse(org.json.JSONObject(f.readText()).getJSONObject("apps").getBoolean("later")) // an app another device knows, kept
        assertEquals("AI Assist/ai.json", TetraSystem.fromDisk(".System/Assistant/Shared/ai.json")) // synced under its own name
        assertFalse(File(tetra, "Assistant").exists()) // never in the user's own Assistant folder
    }

    @Test fun offNoRequestLeaves() {
        AiGate.set(AiGate.State(false), tetra)
        val e = runCatching { AssistantNet.post("key", "/chat/completions", "{}") }.exceptionOrNull()
        assertEquals("AI Assist is off.", e?.message) // refused before any connection (offline would say "Offline: …")
    }

    @Test fun anAppSwitchedOffIsNeitherReadNorOffered() {
        AiGate.set(AiGate.State(true, mapOf("mail" to false)), tetra)
        File(tetra, ".System/Mail/Shared").mkdirs()
        val t = AssistantTools(tetra, "Phone")
        assertTrue(t.run("mail_new", org.json.JSONObject()).forModel.contains("does not let the assistant use their mail"))
        val offered = (0 until AssistantTools.allowed().length()).map { AssistantTools.allowed().getJSONObject(it).getJSONObject("function").getString("name") }
        assertFalse(offered.any { it.startsWith("mail_") }); assertTrue("calendar_add" in offered); assertTrue("web_search" in offered)
        AiGate.set(AiGate.State(true, mapOf("contacts" to false)), tetra) // Contacts off: no other tool looks anyone up
        assertTrue(AssistantTools(tetra, "Phone").run("mail_write", org.json.JSONObject().put("to", "Μαρία").put("subject", "x").put("body", "y")).forModel.contains("may not look in Contacts"))
        AiGate.set(AiGate.State(true, mapOf("web" to false)), tetra) // the PC's "web" switch: search and pictures
        assertTrue(AssistantTools(tetra, "Phone").run("web_search", org.json.JSONObject().put("query", "x")).forModel.contains("does not let"))
    }

    @Test fun theAssistantFindsKeptMailAndCopiesAnAttachmentOut() {
        AiGate.set(AiGate.State(true), tetra)
        val mail = File(tetra, ".System/Mail/Shared/a@b.gr/Inbox/2024/2024-05-06 1000 Nikos - Τιμολόγιο.eml").apply { parentFile!!.mkdirs()
            writeText("From: Nikos <n@x.gr>\r\nSubject: =?UTF-8?Q?=CE=A4=CE=B9=CE=BC=CE=BF=CE=BB=CF=8C=CE=B3=CE=B9=CE=BF?=\r\n\r\nHi") }
        File(mail.path.removeSuffix(".eml") + ".files").apply { mkdirs(); File(this, "invoice-2024.pdf").writeText("pdf") }
        val t = AssistantTools(tetra, "Phone")
        val found = t.run("mail_stored_find", org.json.JSONObject().put("query", "invoice"))
        assertTrue(found.forModel, found.forModel.contains("Nikos") && found.forModel.contains("local only") && found.forModel.contains("invoice-2024.pdf"))
        val copied = t.run("mail_export", org.json.JSONObject().put("choice", 1).put("attachment", "invoice"))
        assertTrue(copied.forModel, copied.forModel.contains("Tetra/Documents/invoice-2024.pdf"))
        assertEquals("pdf", File(tetra, "Documents/invoice-2024.pdf").readText())
        assertTrue(t.run("mail_export", org.json.JSONObject().put("choice", 1)).forModel.contains("Tetra/Documents/Τιμολόγιο.eml")) // the whole mail
        assertTrue(t.run("mail_export", org.json.JSONObject().put("choice", 1).put("attachment", "photo")).forModel.contains("it has: invoice-2024.pdf"))
    }
}

