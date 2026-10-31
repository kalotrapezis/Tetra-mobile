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

    @Test fun theDeviceKeepsRecentMailAndTheIndexAlways() {
        val today = java.time.LocalDate.of(2026, 10, 31)
        val eml = "Mail/a@b.gr/Inbox/2026/2026-05-02 0912 X - Y.eml"
        val old = "Mail/a@b.gr/Archive/2025/2025-10-30 0800 X - Y.eml"
        val att = "Mail/a@b.gr/Inbox/2026/2026-10-15 0912 X - Y.files/a.pdf"
        assertTrue(MailSync.keep("Mail/index.jsonl", today, 1, 0)); assertTrue(MailSync.keep("Mail/accounts.json", today, 1, 0))
        assertTrue(MailSync.keep(eml, today, 6, 1)); assertFalse(MailSync.keep(eml, today, 1, 1))
        assertFalse(MailSync.keep(old, today, 12, 1)); assertTrue(MailSync.keep(old, today, -1, 1))
        assertTrue(MailSync.keep(att, today, 6, 1)); assertFalse(MailSync.keep(att, today, 6, 0)); assertTrue(MailSync.keep(att, today, 6, -1))
        assertFalse(MailSync.keep("Mail/requests/Pixel-1.json", today, -1, -1)) // never taken back from the computer
        assertTrue(MailSync.keep("Documents/2020-01-01 0000 x .pdf", today, 1, 0)) // not Mail
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
}
