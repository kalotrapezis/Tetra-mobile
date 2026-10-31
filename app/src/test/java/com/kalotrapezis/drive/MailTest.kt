package com.kalotrapezis.drive

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mail's reading of the computer's archive (tools/mail-sample.py made the fixture in its format). */
class MailTest {
    private val root = File(javaClass.classLoader!!.getResource("mail/accounts.json")!!.toURI()).parentFile!!
    private val mails = MailFiles.index(root)
    private fun mail(subject: String) = mails.first { it.subject == subject }

    @Test fun theIndexKeepsTheLastLineOfEachMailAndDropsTheGoneAndTheCut() {
        assertEquals(10, mails.size) // 11 mails, one gone from the server; the line cut by a crash skipped
        assertTrue(mails.none { it.subject == "Ένα που έφυγε από τον server" })
        val deadline = mail("Προθεσμία εργασίας: 15 Νοεμβρίου")
        assertTrue(deadline.saved); assertTrue(deadline.flagged); assertFalse(deadline.seen); assertEquals(2, deadline.attachments)
        assertTrue(deadline.file!!.startsWith("t.kalotrapezis@nup.ac.cy/Inbox/2026/2026-10-31 0912 "))
        val orange = mail("Νέα ανακοίνωση: Εβδομάδα 7")
        assertFalse(orange.saved); assertNull(orange.file); assertNull(MailFiles.eml(root, orange))
    }

    @Test fun accountsAndTheUnifiedFoldersCounts() {
        val accounts = MailFiles.accounts(root)
        assertEquals(listOf("Gmail", "sch.gr", "nup.ac.cy"), accounts.map { it.name })
        assertEquals(0xFFE5534B, accounts[0].color)
        assertTrue(accounts.all { it.shadow }) // all of them the PC's
        val inbox = MailFiles.counts(mails, accounts).getValue("Inbox")
        assertEquals(MailCount(unread = 0, total = 4, orange = 0), inbox.getValue("kalotrapezis@gmail.com"))
        assertEquals(MailCount(unread = 2, total = 2, orange = 1), inbox.getValue("t.kalotrapezis@nup.ac.cy"))
        assertEquals(1, MailFiles.counts(mails, accounts).getValue("Spam").getValue("kalotrapezis@gmail.com").unread)
    }

    @Test fun theFilterSortsAndGroupsAsTheComputers() {
        val inbox = mails.filter { it.folder == "Inbox" }
        val byDay = MailView().arrange(inbox)
        assertEquals(listOf("2026-10-31", "2026-10-30", "2026-10-28"), byDay.map { it.first })
        assertEquals("Γραμματεία ΜΠΣ", byDay[0].second[0].from) // the newest first
        assertEquals(setOf("Γραμματεία ΜΠΣ", "Διεύθυνση Σχολείου", "Moodle NUP"), MailView(unread = true, group = "none").arrange(inbox).single().second.map { it.from }.toSet())
        assertEquals(listOf("Moodle NUP"), MailView(orange = true).arrange(inbox).flatMap { it.second }.map { it.from })
        assertEquals(listOf("GitHub", "Γραμματεία ΜΠΣ"), MailView(favorites = true, sort = "from", descending = false, group = "none").arrange(inbox).single().second.map { it.from })
        assertEquals(2, MailView(accounts = setOf("t.kalotrapezis@nup.ac.cy")).arrange(inbox).sumOf { it.second.size })
        assertEquals(listOf("Skroutz"), MailView().arrange(inbox, "παραγγελία").flatMap { it.second }.map { it.from })
        // unread first, then by date
        assertFalse(MailView(unreadFirst = true, group = "none").arrange(inbox).single().second.take(3).any { it.seen })
        val v = MailView(unread = true, accounts = setOf("a", "b"), sort = "size", group = "account", favoritesFirst = true)
        assertEquals(v, MailView.of(v.json()))
        assertEquals(MailView(), MailView.of("not json"))
    }

    @Test fun mimeHtmlTextQuotedPrintableAndEncodedWords() {
        val deadline = Mime.read(MailFiles.eml(root, mail("Προθεσμία εργασίας: 15 Νοεμβρίου"))!!.readBytes())
        assertTrue(deadline.html!!.contains("τελική εργασία"))
        assertTrue(deadline.text!!.startsWith("Αγαπητοί φοιτητές"))
        assertEquals("Καθηγητής <prof@nup.ac.cy>", deadline.cc)
        assertEquals(listOf("Rubric.docx", "Οδηγίες_εργασίας.pdf"), MailFiles.attachments(root, mail("Προθεσμία εργασίας: 15 Νοεμβρίου")).map { it.name })

        val qp = Mime.read(MailFiles.eml(root, mail("Σύλλογος διδασκόντων Δευτέρα"))!!.readBytes())
        assertNull(qp.html)
        assertEquals("Συνάδελφοι,\n\nη συνεδρίαση θα γίνει στις 13:30 στο γραφείο του Διευθυντή.", qp.text!!.trim())
        assertEquals("Σύλλογος διδασκόντων Δευτέρα", Mime.words(qp.headers.getValue("subject")))
        assertEquals("Διεύθυνση Σχολείου <dnsi@sch.gr>", Mime.words(qp.headers.getValue("from")))
        assertEquals("Grüße aus Köln", Mime.words("=?iso-8859-1?Q?Gr=FC=DFe_aus?= =?iso-8859-1?Q?_K=F6ln?="))

        val cid = Mime.read(MailFiles.eml(root, mail("Η σύνοψη του λογαριασμού σας"))!!.readBytes())
        assertTrue(cid.html!!.contains("src=\"data:image/png;base64,")); assertFalse(cid.html!!.contains("cid:"))
    }

    @Test fun remoteContentPixelsAndRiskyAttachments() {
        val shop = Mime.read(MailFiles.eml(root, mail("Η παραγγελία σου στάλθηκε"))!!.readBytes()).html!!
        assertTrue(Mime.remote(shop))
        assertFalse(Mime.remote(Mime.read(MailFiles.eml(root, mail("Η σύνοψη του λογαριασμού σας"))!!.readBytes()).html!!)) // its picture is inside it
        val clean = Mime.withoutPixels(shop)
        assertFalse(clean.contains("track.example.com")); assertTrue(clean.contains("images.example.com"))
        assertTrue(Mime.risky("invoice.pdf.exe")); assertTrue(Mime.risky("Report.DOCM")); assertFalse(Mime.risky("Rubric.docx"))
        assertNotNull(Mime.textAsHtml("a <b> & c").takeIf { it.contains("a &lt;b&gt; &amp; c") })
    }

    @Test fun aDeviceKeepsTheNewestMailThenAttachmentsUpToItsLimit() {
        val files = mapOf("Mail/index.jsonl" to 900L, "Mail/accounts.json" to 50L,
            "Mail/a@b.gr/Inbox/2026/2026-10-31 0912 X - new.eml" to 40L, "Mail/a@b.gr/Inbox/2026/2026-10-30 0800 X - older.eml" to 40L,
            "Mail/a@b.gr/Archive/2025/2025-01-01 0800 X - oldest.eml" to 40L, "Mail/a@b.gr/Inbox/2026/2026-10-31 0912 X - new.files/a.pdf" to 30L)
        assertEquals(setOf("Mail/index.jsonl", "Mail/accounts.json", "Mail/a@b.gr/Inbox/2026/2026-10-31 0912 X - new.eml", "Mail/a@b.gr/Inbox/2026/2026-10-30 0800 X - older.eml"),
            MailSync.plan(files, 100)) // the index never counts; two mails fit, the oldest and then the attachment do not
        assertEquals(files.keys - "Mail/a@b.gr/Inbox/2026/2026-10-31 0912 X - new.files/a.pdf", MailSync.plan(files, 125)) // mail before attachments
        assertEquals(files.keys, MailSync.plan(files, -1))
        val keep = MailSync.plan(files, 100)
        val copy = MailStorage("Pixel"); val move = MailStorage("Pixel", mode = MailStorage.MOVE)
        assertTrue(MailSync.take("Mail/a@b.gr/Inbox/2026/2026-10-31 0912 X - new.eml", copy, keep))
        assertFalse(MailSync.take("Mail/a@b.gr/Inbox/2026/2026-10-31 0912 X - new.eml", move, keep)) // move: nothing from the PC
        assertTrue(MailSync.take("Mail/index.jsonl", move, keep)); assertTrue(MailSync.take("Documents/x.pdf", move, keep))
        assertFalse(MailSync.take("Mail/requests/Pixel-1.json", copy, keep)) // never taken back from the computer
        val old = java.io.File.createTempFile("mail", ".eml").apply { setLastModified(0) }
        assertTrue(MailSync.drop("Mail/a@b.gr/Archive/2025/2025-01-01 0800 X - oldest.eml", old, keep, held = true))
        assertFalse(MailSync.drop("Mail/a@b.gr/Archive/2025/2025-01-01 0800 X - oldest.eml", old, keep, held = false)) // not before the PC has it
        assertFalse(MailSync.drop("Mail/a@b.gr/Inbox/2026/2026-10-31 0912 X - new.eml", old, keep, held = true))
        old.setLastModified(System.currentTimeMillis())
        assertFalse(MailSync.drop("Mail/a@b.gr/Archive/2025/2025-01-01 0800 X - oldest.eml", old, keep, held = true)) // opened this week
        old.delete()
    }

    @Test fun aDevicesStorageIsAFileEitherSideWrites() {
        val dir = java.nio.file.Files.createTempDirectory("mail").toFile()
        assertEquals(MailStorage("Pixel"), MailStorage.read(dir, "Pixel")) // nothing said: copy, 1 GB
        File(dir, "devices").mkdirs()
        File(dir, "devices/Pixel.json").writeText("""{"device":"Pixel","name":"Pixel 8","mode":"move","limit":5000000000,"setAt":7,"setBy":"desktop"}""") // as the PC writes it
        assertEquals(MailStorage("Pixel", "Pixel 8", MailStorage.MOVE, 5 * MailStorage.GB, 7, "desktop"), MailStorage.read(dir, "Pixel"))
        File(dir, "a@b.gr").mkdirs(); File(dir, "a@b.gr/x.eml").writeText("12345")
        MailStorage.report(dir, "Pixel")
        assertEquals(5L to 1, MailStorage.usage(dir, "Pixel")) // the devices' own files are not counted
        File(dir, "devices/Tab.usage.json").writeText("""{"used":3,"files":1,"at":1}""")
        assertEquals(listOf("Pixel", "Tab"), MailStorage.all(dir).map { it.device })
        dir.deleteRecursively()
    }

    @Test fun aRequestIsOneFileInStep1sFormat() {
        val dir = java.nio.file.Files.createTempDirectory("mail").toFile()
        val f = MailRequests.write(dir, "Pixel", "move", listOf("a@b/Inbox/1/2"), "Archive", now = 1000)
        assertEquals("requests/Pixel-1000.json", f.relativeTo(dir).path)
        val o = org.json.JSONObject(f.readText())
        assertEquals("move", o.getString("op")); assertEquals("Archive", o.getString("to")); assertEquals(1000, o.getLong("at")); assertEquals("Pixel", o.getString("device"))
        assertEquals("a@b/Inbox/1/2", o.getJSONArray("ids").getString(0))
        assertEquals("Pixel-1001.json", MailRequests.write(dir, "Pixel", "read", listOf("x"), now = 1000).name) // the same moment: the next
        assertFalse(org.json.JSONObject(File(dir, "requests/Pixel-1001.json").readText()).has("to"))
        assertEquals(listOf("Pixel-1000.json", "Pixel-1001.json"), File(dir, "requests").list()!!.sorted()) // no .part left
        assertTrue(runCatching { MailRequests.write(dir, "Pixel", "move", listOf("x"), "Nowhere") }.isFailure)
        val e = mail("Η παραγγελία σου στάλθηκε")
        assertTrue(MailRequests.applied(e, "favorite")!!.flagged); assertFalse(MailRequests.applied(e, "unread")!!.seen); assertNull(MailRequests.applied(e, "archive"))
        dir.deleteRecursively()
    }

    @Test fun attachmentNamesAsTheComputerWritesThem() {
        val deadline = Mime.read(MailFiles.eml(root, mail("Προθεσμία εργασίας: 15 Νοεμβρίου"))!!.readBytes())
        assertEquals(listOf("Οδηγίες_εργασίας.pdf", "Rubric.docx"), deadline.attachments)
        assertEquals(emptyList<String>(), Mime.read(MailFiles.eml(root, mail("Η σύνοψη του λογαριασμού σας"))!!.readBytes()).attachments) // its picture is in the text
        val raw = ("Content-Type: multipart/mixed; boundary=\"b\"\r\n\r\n--b\r\nContent-Type: text/plain\r\n\r\nhi\r\n" +
            "--b\r\nContent-Disposition: attachment; filename*=utf-8''%CE%B1%3A%CE%B2.pdf\r\n\r\nx\r\n" +
            "--b\r\nContent-Disposition: attachment; filename=\"α:β.pdf\"\r\n\r\nx\r\n--b\r\nContent-Type: application/octet-stream\r\nContent-Disposition: attachment\r\n\r\nx\r\n--b--\r\n").toByteArray()
        assertEquals(listOf("α β.pdf", "α β (2).pdf", "attachment"), Mime.read(raw).attachments)
        assertEquals("a b", Mime.clean("..a/ \t b", 150))
    }

    @Test fun everyDevicesIndexAndAccountsAreOneArchive() {
        val dir = java.nio.file.Files.createTempDirectory("mail").toFile()
        File(dir, "accounts.json").writeText("""[{"id":"a@pc.gr","address":"a@pc.gr","name":"PC","color":"#e5534b"},{"id":"b@both.gr","name":"Both on PC","color":"#4f8ff7"}]""")
        File(dir, "accounts.Pixel.json").writeText("""[{"id":"b@both.gr","name":"Both on phone","color":"#3fb27f","addedOn":"phone","server":{"host":"imap.both.gr","port":993,"user":"b","secure":true}},{"id":"c@phone.gr","name":"Phone","color":"#e0a43a","addedOn":"phone"}]""")
        val accounts = MailFiles.accounts(dir, own = setOf("b@both.gr", "c@phone.gr"))
        assertEquals(listOf("PC", "Both on phone", "Phone"), accounts.map { it.name }) // the device's own description of an account it fetches
        assertEquals(listOf(true, false, false), accounts.map { it.shadow })
        assertEquals("imap.both.gr", MailServer.of(accounts[1].json!!.optJSONObject("server"))!!.host)
        assertEquals("Both on PC", MailFiles.accounts(dir).first { it.id == "b@both.gr" }.name) // another device: the PC's

        fun line(id: String, file: String?, seen: Boolean, gone: Boolean = false) = """{"id":"$id","account":"b@both.gr","folder":"Inbox","date":1,"subject":"s","seen":$seen,"saved":${file != null},"file":${file?.let { "\"$it\"" } ?: "null"},"gone":$gone}"""
        File(dir, "index.jsonl").writeText(line("b@both.gr/Inbox/1/1", "b@both.gr/Inbox/2026/x.eml", false) + "\n" + line("b@both.gr/Inbox/1/2", null, false) + "\n")
        File(dir, "index.Pixel.jsonl").writeText(line("b@both.gr/Inbox/1/1", null, true) + "\n" + line("b@both.gr/Inbox/1/2", null, false, gone = true) + "\n")
        val mails = MailFiles.index(dir, own = "Pixel")
        assertEquals(listOf("b@both.gr/Inbox/1/1"), mails.map { it.id }) // the phone saw 2 leave the server
        assertTrue(mails[0].seen) // its flags, this device's last
        assertEquals("b@both.gr/Inbox/2026/x.eml", mails[0].file) // but the body the PC saved is not hidden by a line with only headers
        dir.deleteRecursively()
    }

    @Test fun theEngineNamesAndReadsAsTheComputer() {
        assertEquals("Καλημέρα", MailEngine.utf8(String("Καλημέρα".toByteArray(), Charsets.ISO_8859_1))) // raw UTF-8 headers read as Latin-1
        assertEquals("Grüße", MailEngine.utf8("Grüße")); assertEquals("plain", MailEngine.utf8("plain"))
        val at = java.time.LocalDateTime.of(2026, 10, 31, 9, 12).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals("2026/2026-10-31 0912 Γραμματεία ΜΠΣ - Προθεσμία εργασίας 15 Νοεμβρίου", MailEngine.mailName(at, "Γραμματεία ΜΠΣ", "Προθεσμία εργασίας: 15 Νοεμβρίου"))
        assertEquals("2026/2026-10-31 0912 - - -", MailEngine.mailName(at, "", ""))
        val xml = """<clientConfig><emailProvider><incomingServer type="imap"><hostname>imap.uni.cy</hostname><port>993</port><socketType>SSL</socketType><username>%EMAILLOCALPART%</username></incomingServer>
            <outgoingServer type="smtp"><hostname>smtp.uni.cy</hostname><port>587</port><socketType>STARTTLS</socketType><username>%EMAILADDRESS%</username></outgoingServer></emailProvider></clientConfig>"""
        val f = MailDetect.parse(xml, "t.k@uni.cy")!!
        assertEquals(MailServer("imap.uni.cy", 993, "t.k", secure = true), f.imap)
        assertEquals(MailServer("smtp.uni.cy", 587, "t.k@uni.cy", secure = false, starttls = true), f.smtp)
        assertTrue(MailDetect.known("x@hotmail.com")!!.microsoft); assertEquals("goodtable", MailDetect.known("goodtable@sch.gr")!!.imap.user)
        assertEquals(null, MailDetect.parse("<html>no</html>", "a@b.c"))
    }

    @Test fun aReplysSubjectAndQuote() {
        assertEquals("Re: Θέμα", MailSend.subject("reply", "Θέμα")); assertEquals("Re: Θέμα", MailSend.subject("replyAll", "Re: Θέμα"))
        assertEquals("ΑΠ: Θέμα", MailSend.subject("reply", "ΑΠ: Θέμα")); assertEquals("Fwd: Re: x", MailSend.subject("forward", "Re: x"))
        assertEquals("Fwd: x", MailSend.subject("forward", "Fwd: x")); assertEquals("x", MailSend.subject("new", "x"))
        assertEquals("> a\n> \n> b", MailSend.quote("a\n\nb\n"))
    }

    @Test fun theOutboxAndSignaturesAreStep1s() {
        assertFalse(MailSync.take("Mail/outbox/Pixel-1.json", MailStorage("P"), emptySet())); assertFalse(MailSync.take("Mail/outbox/Pixel-1.files/a.pdf", MailStorage("P"), emptySet()))
        assertTrue(MailSync.take("Mail/outbox/Pixel-1.failed.json", MailStorage("P"), emptySet())) // what the PC gave up on comes back to be said
        assertTrue(MailSync.outgoing("Mail/outbox/Pixel-1.json")); assertFalse(MailSync.outgoing("Mail/outbox/Pixel-1.failed.json"))
        val dir = java.nio.file.Files.createTempDirectory("mail").toFile()
        MailSignatures.set(dir, "A@B.gr", "Θεολόγος <K>\nNUP & co")
        val o = org.json.JSONObject(File(dir, "signatures.json").readText()).getJSONObject("a@b.gr")
        assertEquals("Θεολόγος &lt;K&gt;<br>NUP &amp; co", o.getString("html")); assertEquals("above", o.getString("position"))
        assertEquals("Θεολόγος <K>\nNUP & co", MailSignatures.text(dir, "a@b.gr"))
        File(dir, "outbox").mkdirs(); File(dir, "outbox/Pixel-5.failed.json").writeText("""{"draft":{"subject":"Θέμα"},"error":"noRecipients","detail":"No address"}""")
        assertEquals(listOf("Θέμα" to "No address"), MailOutbox.failed(dir).map { it.first to it.second })
        dir.deleteRecursively()
    }

    @Test fun aDesignedSignatureIsKeptAsItsHtml() {
        val page = """<!DOCTYPE html><html><head><title>Υπογραφή</title></head><body style="margin:0"><div class="moz-signature">-- <br>
            <div style="width:700px"><h2 style="margin:0">Θεολόγος Καλοτραπέζης</h2><p>Δάσκαλος Πληροφορικής</p>
            <a href="tel:+30">📞 Τηλέφωνο</a></div></div></body></html>"""
        val kept = MailSignatures.stored(page)
        assertTrue(kept.startsWith("<div class=\"moz-signature\">")); assertFalse(kept.contains("<title>")); assertFalse(kept.contains("<body"))
        assertTrue(MailSignatures.designed(kept)); assertTrue(MailSignatures.dashed(kept)) // it has its own "-- ": none is added
        assertEquals("--\nΘεολόγος Καλοτραπέζης\nΔάσκαλος Πληροφορικής\n📞 Τηλέφωνο", MailSignatures.plain(kept))
        assertEquals("Θεολόγος<br>NUP &amp; co", MailSignatures.stored("Θεολόγος\nNUP & co")) // text stays text
        assertFalse(MailSignatures.designed("Θεολόγος<br>NUP"))
    }

    @Test fun conversationsGroupBySubjectAndShowWhatEachSays() {
        fun e(id: String, folder: String, subject: String, date: Long, mid: String? = null) = MailEntry(id, "a@b.gr", folder, date, "x", "x@y", emptyList(), subject,
            true, false, 1, 0, "", true, null, false, mid)
        val t = MailThreads.of(listOf(e("1", "Inbox", "Σύλλογος", 1), e("2", "Sent", "Re: Σύλλογος", 2), e("3", "Inbox", "ΑΠ: σύλλογος", 3),
            e("4", "Trash", "Re: Σύλλογος", 4), e("5", "Archive", "Σύλλογος", 1, "<m@x>"), e("6", "Inbox", "Σύλλογος", 1, "<m@x>"), e("7", "Inbox", "Άλλο", 5)))
        assertEquals(listOf("1", "5", "2", "3"), t.getValue("a@b.gr\nσύλλογος").map { it.id }) // oldest first; no Trash; the same mail in two folders once
        assertEquals(2, t.size)
        assertEquals("Ευχαριστώ, το πήρα.", MailThreads.own("Ευχαριστώ, το πήρα.\n\nΣτις 31 Οκτ 2026, 12:13, ο/η Γραμματεία έγραψε:\n> Ήρθε μόλις τώρα."))
        assertEquals("Ok", MailThreads.own("Ok\n> quoted\n> more"))
        assertEquals("Hi\nthere", MailThreads.own("Hi\nthere\n-- \nTheo\nNUP"))
        assertEquals("> only a quote", MailThreads.own("> only a quote")) // nothing of its own: shown as it is
    }

    @Test fun theAssistantIsAskedForAProperMailFromTheConversation() {
        val m = MailAssist.messages(listOf("Γραμματεία <s@x>" to "Ήρθε;", "Εγώ <t@x>" to "Ναι."), "t@x", "πες ότι θα έρθω Δευτέρα")
        assertEquals("system", m.getJSONObject(0).getString("role"))
        assertTrue(m.getJSONObject(0).getString("content").contains("proper e-mail reply"))
        val user = m.getJSONObject(1).getString("content")
        assertTrue(user.indexOf("--- Γραμματεία <s@x>:\nΉρθε;") < user.indexOf("--- Εγώ <t@x>:\nΝαι.")) // oldest first
        assertTrue(user.endsWith("My notes for the reply:\nπες ότι θα έρθω Δευτέρα"))
        assertTrue(MailAssist.messages(emptyList(), "t@x", " ").getJSONObject(1).getString("content").endsWith("Draft my reply to the last message."))
    }

    @Test fun microsoftsSignInPieces() {
        assertTrue(MailMicrosoft.isClientId("1a2b3c4d-0000-4000-8000-123456789abc")); assertFalse(MailMicrosoft.isClientId("tetra"))
        val t = MailMicrosoft.tokens(org.json.JSONObject().put("access_token", "A").put("refresh_token", "R").put("expires_in", 3600), now = 1000)
        assertEquals(MailMicrosoft.Tokens("A", "R", 1000 + 3_600_000 - 60_000), t)
        fun reason(desc: String) = runCatching { MailMicrosoft.tokens(org.json.JSONObject().put("error", "x").put("error_description", desc)) }.exceptionOrNull() as MailMsError
        assertEquals("approval", reason("AADSTS65001: The user or administrator has not consented").reason)
        assertEquals("auth", reason("invalid_grant AADSTS70008 expired").reason)
        assertEquals("approval", mailReason(RuntimeException("wrapped", reason("AADSTS90094 admin"))))
        assertEquals(mapOf("code" to "a b", "state" to "s/1"), MailMicrosoft.query("GET /?code=a+b&state=s%2F1 HTTP/1.1"))
        val url = MailMicrosoft.authorizeUrl("1a2b3c4d-0000-4000-8000-123456789abc", "http://localhost:5000/", "CH", "ST", "me@hotmail.com")
        assertTrue(url.contains("redirect_uri=http%3A%2F%2Flocalhost%3A5000%2F")); assertTrue(url.contains("code_challenge_method=S256")); assertTrue(url.contains("IMAP.AccessAsUser.All"))
        assertTrue(MailDetect.known("x@outlook.com")!!.microsoft)
    }

    @Test fun aiAssistsMailTools() {
        val tetra = java.nio.file.Files.createTempDirectory("tetra").toFile()
        root.copyRecursively(File(tetra, ".System/Mail/Shared"))
        val zone = java.time.ZoneId.systemDefault()
        val t = AssistantTools(tetra, "Phone", zone, { java.time.ZonedDateTime.of(2026, 10, 31, 12, 0, 0, 0, zone) })
        val fresh = t.run("mail_new", org.json.JSONObject())
        assertTrue(fresh.forModel.contains("Γραμματεία ΜΠΣ")); assertFalse(fresh.forModel.contains("Ταχυδρομείο")) // three days old and read
        assertTrue(fresh.choices.first().go!!.startsWith("mail:"))
        val attention = t.run("mail_attention", org.json.JSONObject())
        assertTrue(attention.forModel.contains("unread")); assertTrue(attention.forModel.contains("♥")); assertFalse(attention.forModel.contains("Skroutz")) // read, no ♥
        assertEquals(listOf("GitHub"), t.run("mail_find", org.json.JSONObject().put("from", "github")).choices.map { it.label.substringBefore(':') })
        assertEquals("mail:" + mails.first { it.from == "GitHub" }.id, t.run("mail_open", org.json.JSONObject().put("choice", 1)).go)
        val w = t.run("mail_write", org.json.JSONObject().put("to", "a@b.gr").put("subject", "Θέμα").put("body", "Καλημέρα"))
        assertEquals("a@b.gr", org.json.JSONObject(w.go!!.removePrefix("mail-write:")).getString("to"))
        assertTrue(t.run("mail_write", org.json.JSONObject().put("to", "Κανείς").put("subject", "x").put("body", "y")).forModel.contains("no e-mail address"))
        tetra.deleteRecursively()
    }
}
