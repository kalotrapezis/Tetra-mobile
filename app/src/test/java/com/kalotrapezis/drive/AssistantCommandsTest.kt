package com.kalotrapezis.drive

import android.provider.CallLog.Calls
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AssistantCommandsTest {
    private val zone = ZoneId.of("Europe/Athens")
    private val noon = ZonedDateTime.of(2026, 11, 11, 12, 0, 0, 0, zone)
    private fun at(h: Int, daysAgo: Long = 0) = noon.minusDays(daysAgo).withHour(h).toInstant().toEpochMilli()
    private fun cmd(line: String) = JSONObject().put("command", line)

    @Test fun missedCallsWithWhoWasCalledBackAndACallBack() {
        val log = listOf( // newest first, as PhoneLog.read
            LoggedCall(6, "6944000001", at(11), 0, Calls.MISSED_TYPE),
            LoggedCall(5, "+306944000001", at(10), 0, Calls.MISSED_TYPE), // the same number, written otherwise
            LoggedCall(4, "6977000002", at(9, 0) + 30 * 60_000, 75, Calls.OUTGOING_TYPE), // called back
            LoggedCall(3, "6977000002", at(9), 0, Calls.MISSED_TYPE),
            LoggedCall(2, "", at(8), 0, Calls.MISSED_TYPE), // a hidden number
            LoggedCall(1, "6900000003", at(9, 10), 0, Calls.MISSED_TYPE), // ten days ago
        )
        val tetra = java.nio.file.Files.createTempDirectory("tetra").toFile()
        val t = AssistantTools(tetra, "Phone", zone, { noon }, calls = { log })
        val missed = t.run("tetra", cmd("calls missed"))
        val lines = missed.forModel.lines()
        assertEquals(3, lines.size) // the one ten days ago left out
        assertTrue(lines[0], lines[0].startsWith("1. 6944000001 — 2 missed calls, the last") && lines[0].endsWith("not called back"))
        assertTrue(lines[1], lines[1].contains("called back"))
        assertTrue(lines[2], lines[2].startsWith("3. Hidden number"))
        assertEquals(listOf("6944000001"), missed.choices.map { it.call!!.number }) // a button for the one still waiting
        assertEquals("6944000001", t.run("tetra", cmd("calls back 1")).call!!.number)
        assertTrue(t.run("tetra", cmd("calls back 3")).forModel.contains("hidden number"))
        assertTrue(t.run("tetra", cmd("calls back 9")).forModel.contains("it has 3"))
        assertEquals("6944000001", t.picked("tetra", cmd("calls back 1"))) // the confirmation card's name
        assertEquals(4, t.run("tetra", cmd("calls missed 30")).forModel.lines().size)
        assertTrue(t.run("tetra", cmd("Calls Recent 2")).forModel.lines().let { it.size == 2 && it[0].contains("missed") })
        assertTrue(t.run("tetra", cmd("calls dance")).forModel.startsWith("No command"))
        tetra.deleteRecursively()
    }

    @Test fun aCallWaitsForTheUsersYesAfterOutsideTextAndATabletSaysWhy() {
        assertTrue(AssistantGuard.acts("tetra", cmd("calls back 1")))
        assertFalse(AssistantGuard.acts("tetra", cmd("calls missed")) || AssistantGuard.external("tetra", cmd("calls missed")))
        assertTrue(AssistantCommands.description().contains("- calls missed"))
        val tetra = java.nio.file.Files.createTempDirectory("tetra").toFile()
        val tablet = AssistantTools(tetra, "Tablet", zone, { noon }, hotspotDevice = "Tablet")
        assertTrue(tablet.run("tetra", cmd("calls missed")).forModel.contains("tablet has no phone"))
        tetra.deleteRecursively()
    }

    @Test fun everyAppsCommands() {
        val tetra = java.nio.file.Files.createTempDirectory("tetra").toFile()
        val mailRoot = java.io.File(tetra, ".System/Mail/Shared").apply { mkdirs() }
        java.io.File(javaClass.classLoader!!.getResource("mail/accounts.json")!!.toURI()).parentFile!!.copyRecursively(mailRoot, overwrite = true)
        val day = noon.toLocalDate()
        val pics = listOf(java.io.File(tetra, "a.jpg"))
        var asked: Pair<Long, Long>? = null
        val page = "<html><head><title>Η είδηση</title><script>alert('x')</script></head><body><nav>Μενού</nav><article><h1>Τίτλος</h1><p>" +
            "Πρώτη παράγραφος &amp; λόγια. ".repeat(20) + "</p><p>Δεύτερη &laquo;εδώ&raquo;.</p></article><footer>©</footer></body></html>"
        val t = AssistantTools(tetra, "Phone", zone, { noon }, photosBetween = { a, b -> asked = a to b; pics }, fetchPage = { page })

        // Mail: the accounts, one named (a PC account: a request to the computer)
        val accounts = t.run("tetra", cmd("mail accounts")).forModel
        assertTrue(accounts, accounts.contains("3. nup.ac.cy <t.kalotrapezis@nup.ac.cy> — through the computer"))
        assertTrue(t.run("tetra", cmd("mail name 3 Πανεπιστήμιο Λευκωσίας")).forModel.contains("Asked the computer"))
        val req = java.io.File(mailRoot, "requests").listFiles()!!.single().readText()
        assertTrue(req, req.contains("\"rename\"") && req.contains("Πανεπιστήμιο Λευκωσίας") && req.contains("t.kalotrapezis@nup.ac.cy"))
        assertTrue(t.run("tetra", cmd("mail name 9 X")).forModel.contains("it has 3"))

        // Calendar: found by its words
        t.run("calendar_add", JSONObject().put("title", "Τηλεδιάσκεψη Στατιστικής").put("start", "${day.plusDays(5)}T19:00").put("place", "Zoom"))
        val ev = t.run("tetra", cmd("calendar find στατιστικης")).forModel
        assertTrue(ev, ev.startsWith("1. ") && ev.contains("Τηλεδιάσκεψη Στατιστικής @ Zoom"))
        assertTrue(t.run("tetra", cmd("calendar find γάμος")).forModel.startsWith("No event"))

        // Notes: the latest, a line added, a checklist item, the Trash
        t.run("note_new", JSONObject().put("title", "Ψώνια σπίτι").put("text", "γάλα"))
        assertTrue(t.run("tetra", cmd("notes recent")).forModel.startsWith("1. Ψώνια σπίτι"))
        t.run("tetra", cmd("notes add 1 ψωμί και αυγά"))
        val store = NotesStore(java.io.File(tetra, ".System/Notes/Shared"), "test")
        assertEquals("γάλα\nψωμί και αυγά", store.list().single().let { store.get(it.id)!!.content })
        assertTrue(t.run("tetra", cmd("notes trash 1")).forModel.contains("Trash"))
        assertTrue(store.get(store.list().single().id)!!.trashedAt != null)
        assertTrue(t.run("tetra", cmd("notes recent")).forModel.contains("empty"))

        // Files of a kind
        java.io.File(tetra, "Documents").mkdirs(); java.io.File(tetra, "Documents/Άρθρο.pdf").writeText("x"); java.io.File(tetra, "Documents/Εργασία.docx").apply { writeText("x"); setLastModified(System.currentTimeMillis() + 60_000) } // the newest
        val docs = t.run("tetra", cmd("files recent documents"))
        assertTrue(docs.forModel, docs.forModel.contains("Εργασία.docx") && !docs.forModel.contains("Άρθρο.pdf"))
        assertTrue(t.run("tetra", cmd("files recent pdf")).forModel.let { it.contains("Άρθρο.pdf") && !it.contains("docx") })
        assertEquals("Άρθρο.pdf", t.run("file_open", JSONObject().put("choice", 1)).open!!.name) // file_open takes the last list's number

        // The audio Library
        assertTrue(t.run("tetra", cmd("audio list")).forModel.contains("Library is empty"))
        val job = java.io.File(AudioJobs.dir(tetra), "j1").apply { mkdirs() }
        java.io.File(job, "show.m4a").writeText("audio")
        java.io.File(job, "job.json").writeText(JSONObject().put("id", "j1").put("state", "done").put("name", "Κεφάλαιο 3.pdf").put("mode", "show").put("title", "Η μνήμη")
            .put("seconds", 610).put("file", "show.m4a").toString())
        val lib = t.run("tetra", cmd("audio list")).forModel
        assertTrue(lib, lib.startsWith("1. Η μνήμη — show, 10 min, ready"))
        assertEquals("show.m4a", t.run("tetra", cmd("audio play latest")).open!!.name)

        // Photos of a day
        assertEquals(1, t.run("tetra", cmd("photos day ${day.minusDays(1)}")).photos.size)
        assertEquals(day.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), asked!!.first)
        assertTrue(t.run("tetra", cmd("photos day χθες")).forModel.contains("YYYY-MM-DD"))

        // Maps: the user's places
        java.io.File(MapsFiles.root(tetra), MapsFiles.PLACES).apply { mkdirs() }.resolve("p1.json").writeText(Place("p1", "Σπίτι", 38.3, 23.1).json())
        assertEquals("1. Σπίτι", t.run("tetra", cmd("maps places")).forModel)

        // Contacts: a new one, once
        assertTrue(t.run("tetra", cmd("contacts add 6944123456")).forModel.contains("name"))
        assertTrue(t.run("tetra", cmd("contacts add Γιάννης Παπαδόπουλος 6944 123 456")).forModel.startsWith("Saved Γιάννης Παπαδόπουλος, 6944123456")) // said in pieces
        assertTrue(t.run("tetra", cmd("contacts add Γιάννης 6944123456")).forModel.contains("already in Contacts"))

        // The web: the article's text, without the menu and the script
        val read = t.run("tetra", cmd("web read https://example.com/a")).forModel
        assertTrue(read, read.startsWith("Η είδηση\n\nΤίτλος\nΠρώτη παράγραφος & λόγια.") && read.contains("Δεύτερη «εδώ».") && !read.contains("Μενού") && !read.contains("alert"))
        assertTrue(AssistantGuard.external("tetra", cmd("web read https://x")) && AssistantGuard.acts("tetra", cmd("contacts add A 6944000000")))
        assertTrue(listOf("data tips", "data days").all { t.run("tetra", cmd(it)).forModel.contains("no counts yet") }) // Data: nothing counted here
        assertTrue(t.run("tetra", cmd("data apps 7")).forModel.startsWith("No app's data"))
        tetra.deleteRecursively()
    }

    @Test fun theCommandsTheCarAndEveryDayNeed() {
        val tetra = java.nio.file.Files.createTempDirectory("tetra").toFile()
        val mailRoot = java.io.File(tetra, ".System/Mail/Shared").apply { mkdirs() }
        java.io.File(javaClass.classLoader!!.getResource("mail/accounts.json")!!.toURI()).parentFile!!.copyRecursively(mailRoot, overwrite = true)
        val keys = mutableListOf<Int>(); var quiet: Pair<Boolean, Long?>? = null; var where: Pair<Double, Double>? = null
        val t = AssistantTools(tetra, "Phone", zone, { noon }, here = { where }, mediaKey = { keys += it }, quiet = { on, until -> quiet = on to until; "done" })
        val day = noon.toLocalDate()

        // playing
        listOf("media pause", "media next", "media previous", "media play").forEach { t.run("tetra", cmd(it)) }
        assertEquals(listOf(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE, android.view.KeyEvent.KEYCODE_MEDIA_NEXT, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS, android.view.KeyEvent.KEYCODE_MEDIA_PLAY), keys)
        // Do Not Disturb until a time: today's 21:00 (it is noon), tomorrow's 09:00
        t.run("tetra", cmd("quiet on until 21:00")); assertEquals(true to day.atTime(21, 0).atZone(zone).toInstant().toEpochMilli(), quiet)
        t.run("tetra", cmd("quiet on 9:00")); assertEquals(day.plusDays(1).atTime(9, 0).atZone(zone).toInstant().toEpochMilli(), quiet!!.second)
        t.run("tetra", cmd("quiet off")); assertEquals(false to null, quiet)
        assertTrue(AssistantGuard.acts("tetra", cmd("quiet on")))

        // mail: archive two of a list (the PC's accounts: a request), a reply
        t.run("mail_find", JSONObject().put("account", "nup"))
        assertTrue(t.run("tetra", cmd("mail archive 1 2")).forModel.startsWith("Archived: 2 mails"))
        val req = JSONObject(java.io.File(mailRoot, "requests").listFiles()!!.single().readText())
        assertEquals("archive", req.getString("op")); assertEquals(2, req.getJSONArray("ids").length())
        val reply = t.run("tetra", cmd("mail reply 1 Ευχαριστώ, θα είμαι εκεί.")).cards.single() as ResultCard.Mail
        assertTrue(reply.subject.startsWith("Re: ")); assertEquals("Ευχαριστώ, θα είμαι εκεί.", reply.body); assertEquals("t.kalotrapezis@nup.ac.cy", reply.account)
        assertTrue(t.run("tetra", cmd("mail delete")).forModel.contains("by their numbers"))

        // calendar: a reminder, an event moved, one cancelled
        assertTrue(t.run("tetra", cmd("reminder add 17:00 να πάρω τον υδραυλικό")).forModel.contains("${day} 17:00"))
        t.run("calendar_add", JSONObject().put("title", "Οδοντίατρος").put("start", "${day.plusDays(2)}T10:00").put("end", "${day.plusDays(2)}T10:45"))
        t.run("tetra", cmd("calendar find οδοντιατρος"))
        assertTrue(t.run("tetra", cmd("calendar move 1 ${day.plusDays(3)} 12:30")).forModel.startsWith("Moved «Οδοντίατρος»"))
        assertTrue(t.run("calendar_list", JSONObject().put("from", day.plusDays(3).toString())).forModel.contains("12:30–13:15: Οδοντίατρος")) // its length kept
        t.run("tetra", cmd("calendar find υδραυλικο"))
        assertTrue(t.run("tetra", cmd("calendar cancel 1")).forModel.contains("deleted"))
        assertTrue(t.run("tetra", cmd("calendar find υδραυλικο")).forModel.startsWith("No event"))

        // a checklist item ticked
        t.run("shopping_list", JSONObject().put("items", org.json.JSONArray(listOf("γάλα", "ψωμί", "γάλα σοκολατούχο"))))
        t.run("tetra", cmd("notes recent"))
        assertTrue(t.run("tetra", cmd("notes tick 1 γάλα")).forModel.startsWith("More than one"))
        assertTrue(t.run("tetra", cmd("notes tick 1 ψωμί")).forModel.startsWith("Ticked «ψωμί». Still open: 2"))

        // the Library: renamed, deleted
        val job = java.io.File(AudioJobs.dir(tetra), "j1").apply { mkdirs() }
        java.io.File(job, "job.json").writeText(JSONObject().put("id", "j1").put("name", "a.pdf").put("title", "Παλιό").toString())
        t.run("tetra", cmd("audio list")); t.run("tetra", cmd("audio rename 1 Η μνήμη"))
        assertTrue(t.run("tetra", cmd("audio list")).forModel.startsWith("1. Η μνήμη"))
        t.run("tetra", cmd("audio delete 1")); assertFalse(job.exists())

        // maps: nothing without a location
        assertTrue(t.run("tetra", cmd("maps nearest fuel")).forModel.contains("does not know where"))
        where = 38.3 to 23.1
        assertTrue(t.run("tetra", cmd("maps nearest fuel")).forModel.startsWith("Nothing like"))

        // contacts: a birthday soon, a number and an address added
        ContactsFiles.root(tetra).mkdirs()
        ContactsFiles.save(ContactsFiles.root(tetra), null, ContactInfo(given = "Ελένη", family = "Μάρκου", formatted = "Ελένη Μάρκου", birthday = VDate(1990, day.plusDays(3).monthValue, day.plusDays(3).dayOfMonth)))
        assertTrue(t.run("tetra", cmd("contacts birthdays 7")).forModel.let { it.contains("Ελένη Μάρκου turns 36") })
        assertTrue(t.run("tetra", cmd("contacts phone 6977123456 Ελένη")).forModel.startsWith("Added 6977123456 to Ελένη Μάρκου"))
        assertTrue(t.run("tetra", cmd("contacts email eleni@example.gr Ελένη Μάρκου")).forModel.startsWith("Added"))
        assertTrue(t.run("tetra", cmd("contacts phone 6977123456 Ελένη")).forModel.contains("has it already"))
        tetra.deleteRecursively()
    }
}
