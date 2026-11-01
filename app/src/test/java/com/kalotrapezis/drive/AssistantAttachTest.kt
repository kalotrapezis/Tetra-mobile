package com.kalotrapezis.drive

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A file attached to AI Assist: its text read from each kind, what the model is given of it, that it never stays in the
 * history, and the MP3 asked of the computer (its estimate, its job file, the computer's answer read back).
 * (A PDF is read by MuPDF, which has no JVM build: its pages come through the same [Attach.extract] as a lambda, tested so.)
 */
class AssistantAttachTest {
    private val zone = ZoneId.of("Europe/Athens")
    private val now = ZonedDateTime.of(2026, 11, 1, 9, 0, 0, 0, zone)
    private val tetra = scratchDir("attach")

    private fun zip(name: String, vararg parts: Pair<String, String>) = File(tetra, name).apply {
        ZipOutputStream(outputStream()).use { z -> parts.forEach { (n, x) -> z.putNextEntry(ZipEntry(n)); z.write(x.toByteArray()); z.closeEntry() } }
    }
    private class Script(vararg replies: JSONObject) {
        val left = ArrayDeque(replies.toList())
        val sent = mutableListOf<JSONObject>()
        val api = Mistral({ _, body -> sent += JSONObject(body); JSONObject().put("choices", JSONArray().put(JSONObject().put("message", left.removeFirst()))).toString() })
    }
    private fun calls(name: String, args: String) = JSONObject().put("role", "assistant").put("content", "")
        .put("tool_calls", JSONArray().put(JSONObject().put("id", "c0").put("function", JSONObject().put("name", name).put("arguments", args))))
    private fun says(text: String) = JSONObject().put("role", "assistant").put("content", text)
    private fun tools() = AssistantTools(tetra, "Phone", zone, { now })
    private fun doc(text: String, name: String = "Σημειώσεις.txt") = Attach.extract(File(tetra, name).apply { writeText(text) })

    @Test fun eachKindIsRead() {
        val md = doc("# Πλάτων\nΗ Πολιτεία.\n## Δημοκρατία\nΚείμενο.", "notes.md")
        assertEquals(listOf("Πλάτων", "Δημοκρατία"), md.headings)
        assertTrue(md.text.contains("Η Πολιτεία."))

        val docx = Attach.extract(zip("a.docx", "[Content_Types].xml" to """<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
            "word/document.xml" to """<?xml version="1.0"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
                <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Εισαγωγή</w:t></w:r></w:p><w:p><w:r><w:t>Ο Πλάτων &amp; η πόλη.</w:t></w:r></w:p></w:body></w:document>"""))
        assertTrue(docx.text, docx.text.contains("Εισαγωγή") && docx.text.contains("Ο Πλάτων & η πόλη."))
        assertEquals(listOf("Εισαγωγή"), docx.headings)

        val pptx = Attach.extract(zip("b.pptx", "ppt/slides/slide2.xml" to "<p:sld><a:p><a:r><a:t>Δεύτερη</a:t></a:r></a:p></p:sld>",
            "ppt/slides/slide1.xml" to "<p:sld><a:p><a:r><a:t>Πρώτη</a:t></a:r></a:p><a:p><a:r><a:t>κουκκίδα</a:t></a:r></a:p></p:sld>"))
        assertEquals(listOf("Πρώτη\nκουκκίδα", "Δεύτερη"), pptx.pages)
        assertEquals(listOf("Πρώτη", "Δεύτερη"), pptx.headings)

        val odt = Attach.extract(zip("c.odt", "content.xml" to """<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"><office:body><office:text>
            <text:h text:outline-level="1">Τίτλος</text:h><text:p>Ένα<text:s/>κείμενο.</text:p></office:text></office:body></office:document-content>""",
            "styles.xml" to """<office:document-styles xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"/>"""))
        assertTrue(odt.text, odt.text.contains("Τίτλος") && odt.text.contains("κείμενο."))

        val odp = Attach.extract(zip("d.odp", "content.xml" to "<x><draw:page draw:name=\"1\"><text:p>Ένα</text:p></draw:page><draw:page draw:name=\"2\"><text:p>Δύο</text:p></draw:page></x>"))
        assertEquals(listOf("Ένα", "Δύο"), odp.pages)

        // a PDF's words by page; one with almost none is a scan, read by OCR
        val pdf = File(tetra, "e.pdf").apply { writeText("%PDF") }
        assertEquals(2, Attach.extract(pdf, pdf = { listOf("Σελίδα ένα με λόγια αρκετά για να μην είναι σάρωση.", "Δεύτερη σελίδα επίσης.") }).pages.size)
        val scan = Attach.extract(pdf, pdf = { listOf(" ", "") }, ocr = { _, _ -> listOf("Διαβάστηκε από εικόνα.") })
        assertTrue(scan.scanned); assertEquals("Διαβάστηκε από εικόνα.", scan.text)
    }

    @Test fun whatCannotBeReadSaysWhy() {
        fun res(f: () -> Unit) = runCatching(f).exceptionOrNull().let { (it as DriveProblem).res }
        assertEquals(R.string.assistant_attach_unknown, res { Attach.extract(File(tetra, "x.exe").apply { writeText("MZ") }) })
        assertEquals(R.string.assistant_attach_empty, res { doc("   \n ") })
        assertEquals(R.string.assistant_attach_scan, res { Attach.extract(File(tetra, "p.png").apply { writeText("x") }) })
    }

    @Test fun aSmallFileGoesWholeALongOneAsItsStartAndParts() {
        val small = doc("Λίγα λόγια.")
        assertTrue(Attach.forModel(small).contains("<<<\nΛίγα λόγια.\n>>>"))
        val paras = (1..60).joinToString("\n") { "Παράγραφος $it: " + "λέξη ".repeat(150) + if (it == 42) "φιλόσοφος βασιλιάς" else "" }
        val long = doc(paras)
        assertTrue(long.text.length > Attach.INLINE)
        val shown = Attach.forModel(long)
        assertTrue(shown, shown.contains("attachment_read") && shown.length < 4000)
        val parts = Attach.parts(long)
        assertTrue(parts.size > 5 && parts.all { it.text.length <= Attach.PART * 1.5 })
        assertTrue(parts[0].text.startsWith("Παράγραφος 1:")) // cut at paragraphs
        assertTrue(Attach.read(long, 2, null).startsWith("Part 2 of ${parts.size}"))
        // a query finds its paragraph by the words' starts, accents and endings aside
        assertTrue(Attach.read(long, null, "Φιλοσόφων βασιλέων").contains("Παράγραφος 42"))
        assertTrue(Attach.read(long, 999, null).startsWith("No part 999"))
        // past 600,000 letters only the start, and said
        val huge = doc("α".repeat(Attach.MAX_CHARS + 5000))
        assertTrue(huge.cut); assertEquals(Attach.MAX_CHARS, huge.text.length)
        assertTrue(Attach.forModel(huge).contains("only its first part was read"))
    }

    @Test fun theFilesTextIsNotKeptAfterTheTurn() {
        val d = doc("Η Πολιτεία του Πλάτωνα μιλά για τη δικαιοσύνη. ".repeat(1000)) // long: attachment_read is needed
        val s = Script(calls("attachment_read", """{"query":"δικαιοσύνη"}"""), says("Μιλά για τη δικαιοσύνη."), says("Ναι."))
        val chat = AssistantChat(s.api, tools(), now = { now })
        assertEquals("Μιλά για τη δικαιοσύνη.", chat.ask("Τι λέει;", file = d).text)
        assertTrue(s.sent[0].getJSONArray("messages").getJSONObject(1).getString("content").contains("[Attached file: Σημειώσεις.txt]"))
        assertTrue(s.sent[1].toString().contains("[part 1] Η Πολιτεία")) // the tool's answer went to the model once
        val kept = chat.saved().toString()
        assertFalse(kept, kept.contains("δικαιοσύνη. Η Πολιτεία"))
        assertTrue(kept.contains("its text is not kept here"))
        chat.ask("Και μετά;")
        assertFalse(s.sent[2].toString().contains("Η Πολιτεία του Πλάτωνα μιλά"))
    }

    @Test fun anMp3IsAnEstimateThatWaitsForYes() {
        val d = doc("Κείμενο για ανάγνωση. ".repeat(500))
        val s = Script(calls("audio_make", """{"mode":"narration"}"""), says("Περίπου 12 λεπτά, €0,03. Να ξεκινήσω;"))
        val reply = AssistantChat(s.api, tools(), now = { now }).ask("Διάβασέ μου το «Σημειώσεις» ολόκληρο σε MP3, με μία φωνή.", file = d)
        // the first round offers only audio_make, and a call is a must: the model cannot make up the figures
        assertEquals("any", s.sent[0].getString("tool_choice"))
        assertEquals(listOf("audio_make"), s.sent[0].getJSONArray("tools").let { t -> (0 until t.length()).map { t.getJSONObject(it).getJSONObject("function").getString("name") } })
        assertEquals("auto", s.sent[1].getString("tool_choice"))
        assertNotNull(reply.audio); val a = reply.audio!!
        assertEquals("narration", a.mode); assertEquals(AudioJobs.estimate(d.text.length, "narration"), a.estimate)
        assertTrue(s.sent[1].toString().contains("≈ ${a.estimate.minutes} minutes"))

        // the desktop's figures: 11,000 letters read is 12 minutes; a show of them at most chars/300, at least 3
        assertEquals(12, AudioJobs.estimate(11_000, "narration").minutes)
        assertEquals(3, AudioJobs.estimate(11_000, "narration").ready)
        assertEquals(15, AudioJobs.estimate(30_000, "show", 15).minutes)
        assertEquals(3, AudioJobs.estimate(600, "show", 15).minutes)
        assertEquals(20, AudioJobs.estimate(200_000, "show").minutes)
        val show = AudioJobs.estimate(30_000, "show", 15)
        assertEquals(30_000 / 3.5 * 0.4e-6 + 13_500 / 3.5 * 2e-6 + 43_500 / 3.5 * 0.1e-6 + 200 / 3.5 * 0.3e-6 + 13_500 * 0.016e-3, show.usd, 1e-9)
        assertEquals(5, show.ready)
    }

    @Test fun theJobIsAFolderItsCardFollows() {
        val d = Attach.Doc("Πλάτων.pdf", "/x/Πλάτων.pdf", "pdf", listOf("Σελίδα 1", "Σελίδα 2"), emptyList(), false)
        val id = AudioJobs.ask(tetra, AudioAsk(d, "show", 15, "δικαιοσύνη", "", false, AudioJobs.estimate(16, "show")), now = 1000L)
        val f = File(AudioJobs.dir(tetra), "$id/job.json")
        assertTrue(f.path.endsWith(".System/Assistant/Data/Audio/$id/job.json"))
        val o = JSONObject(f.readText())
        assertEquals("asked", o.getString("state")); assertEquals(1000L, o.getLong("asked")); assertEquals(15, o.getInt("minutes"))
        assertEquals("Σελίδα 1\u000cΣελίδα 2", File(f.parentFile, "source.txt").readText())

        f.writeText(o.put("state", "running").put("stage", "voice").put("done", 5).put("total", 10).toString())
        AudioJobs.read(tetra, id)!!.let { assertEquals("running", it.state); assertEquals(0.1f + 0.85f * 0.5f, AudioJobs.share(it), 1e-4f) }
        File(f.parentFile, "Πλάτων — Εκπομπή.m4a").writeBytes(byteArrayOf(1, 2, 3))
        f.writeText(o.put("state", "done").put("title", "Πλάτων — Εκπομπή").put("seconds", 905.5).put("file", "Πλάτων — Εκπομπή.m4a")
            .put("chapters", JSONArray().put(JSONObject().put("title", "Αρχή").put("start", 0)).put(JSONObject().put("title", "Δικαιοσύνη").put("start", 120.5))).toString())
        val j = AudioJobs.read(tetra, id)!!
        assertEquals("done", j.state); assertEquals(listOf(AudioJobs.Chapter("Αρχή", 0.0), AudioJobs.Chapter("Δικαιοσύνη", 120.5)), j.chapters)
        // saved into Files › Assistant, never over one there
        assertEquals("Πλάτων — Εκπομπή.m4a", AudioJobs.save(tetra, j).name)
        assertEquals("Πλάτων — Εκπομπή (2).m4a", AudioJobs.save(tetra, j).name)
    }

    @Test fun theScriptIsReadFromJsonLines() {
        val lines = AudioMaker.parseScript("```json\n{\"speaker\":\"host\",\"text\":\"Καλώς  ήρθατε.\",\"section\":\"Αρχή\"},\nκάτι άλλο\n{\"speaker\":\"expert\",\"text\":\"Η πόλη.\"}\n{\"speaker\":\"x\",\"text\":\"\"}\n```")
        assertEquals(listOf("host", "expert"), lines.map { it.speaker })
        assertEquals(listOf("Καλώς ήρθατε.", "Η πόλη."), lines.map { it.text })
        assertEquals(listOf("Αρχή", "Αρχή"), lines.map { it.section }) // a line without one is under the one before
        assertEquals(2, AudioMaker.parseScript("""[{"text":"Α","section":"Σ"},{"speaker":"expert","text":"Β"}]""").size)
        assertTrue(AudioMaker.pieces("Πρώτη πρόταση. ".repeat(100)).all { it.length <= 700 })
    }

    @Test fun theMixEvensTheLinesAndMarksTheSections() {
        val rate = AudioMaker.RATE
        fun line(section: String, amp: Float) = AudioMaker.Line("host", "x", section).apply { pcm = FloatArray(rate) { if (it % 2 == 0) amp else -amp } }
        val m = AudioMaker.mix(listOf(line("Α", 0.01f), line("Α", 0.5f), line("Β", 0.08f)))
        // 3 s of speech, 0.35 s between lines, 1.2 s before a new section
        assertEquals(3 + 0.35 + 1.2, m.seconds, 1e-9)
        assertEquals(listOf(AudioJobs.Chapter("Α", 0.0), AudioJobs.Chapter("Β", 2 + 0.35 + 1.2)), m.chapters)
        assertEquals(Math.round(m.seconds * rate).toInt(), m.pcm.size)
        assertEquals(0.04f, Math.abs(m.pcm[0]), 1e-5f) // too quiet: at most 4 times louder
        assertEquals(0.08f, Math.abs(m.pcm[(1.35 * rate).toInt()]), 1e-5f) // too loud: brought down to RMS 0.08
        assertEquals(0.08f, Math.abs(m.pcm.last()), 1e-5f)
        // the .m4a itself is MediaCodec's, which plain JVM tests do not have (no Robolectric here): checked on the phone
    }
}
