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
}
