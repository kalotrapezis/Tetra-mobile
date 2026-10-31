package com.kalotrapezis.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncRulesTest {
    private val fp = "a".repeat(64)

    @Test fun theCopiesColoursAreTheComputersOwn() {
        assertEquals(listOf(125f, 220f, 173f, 268f, 149f), (3..7).map { SyncRules.copiesColor(it).first })
        assertEquals(358f, SyncRules.copiesColor(1).first)
        assertEquals(30, (3..32).map { SyncRules.copiesColor(it).first }.toSet().size)
    }

    @Test fun aMoveWindowIsSaidInItsLargestUnit() {
        assertEquals(1 to R.plurals.sync_span_month, SyncRules.span(30))
        assertEquals(1 to R.plurals.sync_span_year, SyncRules.span(365))
        assertEquals(2 to R.plurals.sync_span_week, SyncRules.span(14))
        assertEquals(10 to R.plurals.sync_span_day, SyncRules.span(10))
        assertEquals(3 to R.plurals.sync_span_month, SyncRules.span(SyncConnection("photos", "send", "nothing", 90).keepDays))
    }

    @Test fun parsesTheDesktopPairingQr() {
        val qr = SyncRules.parseQr("""{"v":1,"name":"teo-pc","hosts":["192.168.1.146"],"port":43180,"fp":"${fp.uppercase()}","code":"abcdefghijklmnopqrstuv"}""")!!
        assertEquals(listOf("192.168.1.146"), qr.hosts)
        assertEquals(43180, qr.port)
        assertEquals(fp, qr.fingerprint)
    }

    @Test fun retriesALostNetworkThreeTimesAndNotAStop() {
        assertTrue(SyncRules.retriesAfterNetworkLoss(1, SyncException("Could not reach the computer")))
        assertTrue(SyncRules.retriesAfterNetworkLoss(2, SyncException("Could not reach the computer")))
        assertFalse("the fourth attempt gives up", SyncRules.retriesAfterNetworkLoss(3, SyncException("boom")))
        assertFalse("a finished sync is not retried", SyncRules.retriesAfterNetworkLoss(1, null))
        assertFalse("Stop means stop", SyncRules.retriesAfterNetworkLoss(1, kotlinx.coroutines.CancellationException()))
    }

    @Test fun rejectsAnythingElse() {
        assertNull(SyncRules.parseQr("https://example.com"))
        assertNull(SyncRules.parseQr("""{"v":2,"hosts":["h"],"port":1,"fp":"$fp","code":"abcdefghijklmnopqrstuv"}"""))
        assertNull(SyncRules.parseQr("""{"v":1,"hosts":["h"],"port":1,"fp":"short","code":"abcdefghijklmnopqrstuv"}"""))
        assertNull(SyncRules.parseQr("""{"v":1,"hosts":[],"port":1,"fp":"$fp","code":"abcdefghijklmnopqrstuv"}"""))
    }

    @Test fun blobPathKeepsPhoneFoldersAndEncodes() {
        assertEquals("/blob/$fp?path=DCIM%2FCamera&name=IMG+1+%CE%B1.jpg&modified=5", SyncRules.blobPath(fp, "DCIM/Camera/", "IMG 1 α.jpg", 5))
    }
}

class SyncDiscoveryTest {
    @Test
    fun `a probe asks for one computer and a reply only says where to knock`() {
        val fingerprint = "a".repeat(64)
        val probe = String(SyncDiscovery.probe(fingerprint))
        assertTrue("the probe names the computer it is looking for", probe.contains(fingerprint))
        assertFalse("and nothing else about this phone", probe.contains("token"))

        val reply = """{"v":1,"name":"desk","port":43180}""".toByteArray()
        assertEquals(43180, SyncDiscovery.replyPort(reply, reply.size))
    }

    @Test
    fun `anything that is not an answer is ignored`() {
        for (junk in listOf("", "not json", """{"v":2,"port":43180}""", """{"v":1}""", """{"v":1,"port":0}""", """{"v":1,"port":70000}""")) {
            val bytes = junk.toByteArray()
            assertNull("«$junk» is not a reply", SyncDiscovery.replyPort(bytes, bytes.size))
        }
    }
}

class SyncConnectionTest {
    @Test
    fun `direction is read from this device's side, and says what it may do`() {
        val send = SyncConnection("photos", "send", "everything")
        val receive = SyncConnection("photos", "receive", "everything")
        val both = SyncConnection("files", "both", "everything")
        assertTrue(send.sends); assertFalse(send.receives)
        assertFalse(receive.sends); assertTrue(receive.receives)
        assertTrue(both.sends && both.receives)
    }

    @Test
    fun `the sentence's arrow says the way it goes, and Move what happens to this one's copy`() {
        assertEquals("→", SyncConnection("photos", "send", "everything").arrow)
        assertEquals("←", SyncConnection("photos", "receive", "everything").arrow)
        assertEquals("⇄", SyncConnection("files", "both", "everything").arrow)
        assertFalse(SyncConnection("files", "both", "everything").moves)
        assertTrue(SyncConnection("files", "send", "nothing").moves)
        assertEquals(1 to R.plurals.sync_span_month, SyncRules.span(SyncConnection("files", "send", "nothing").keepDays))
    }

    @Test
    fun `a computer too old to answer is treated as two-way, which is what it always did`() {
        assertEquals(listOf("photos", "files", "mail", "other"), SyncConnection.defaults.map { it.content })
        assertTrue(SyncConnection.defaults.all { it.sends && it.receives && it.keep == "everything" })
    }
}

class DriveNewFileTest {
    @Test
    fun `a synced file never replaces one that is already there`() {
        val root = java.io.File.createTempFile("drive-root", "").let { it.delete(); it.mkdirs(); it }
        try {
            val first = DriveRules.newFile(root, "Notes/letter.txt")
            assertEquals(java.io.File(root, "Notes/letter.txt").canonicalFile, first)
            first.parentFile!!.mkdirs()
            first.writeText("the one already here")

            val second = DriveRules.newFile(root, "Notes/letter.txt")
            assertEquals("letter (2).txt", second.name)
            second.writeText("the one that arrived")
            assertEquals("letter (3).txt", DriveRules.newFile(root, "Notes/letter.txt").name)
            assertEquals("the one already here", java.io.File(root, "Notes/letter.txt").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a path that tries to leave Drive is refused`() {
        val root = java.io.File.createTempFile("drive-root", "").let { it.delete(); it.mkdirs(); it }
        try {
            for (bad in listOf("../escape.txt", "/etc/passwd", "Notes/../../escape.txt", "")) {
                assertThrows(IllegalArgumentException::class.java) { DriveRules.newFile(root, bad) }
            }
        } finally {
            root.deleteRecursively()
        }
    }
}

class IncomingPhotoFolderTest {
    @Test
    fun `the computer's own folder is kept when Android allows it there`() {
        assertEquals("DCIM/Camera", SyncRules.incomingFolder("DCIM/Camera", video = false))
        assertEquals("Pictures/Screenshots", SyncRules.incomingFolder("/Pictures/Screenshots/", video = false))
        assertEquals("Movies/Holiday", SyncRules.incomingFolder("Movies/Holiday", video = true))
    }

    @Test
    fun `anywhere Android would refuse becomes Tetra's own folder instead of a failure`() {
        assertEquals("Pictures/Tetra", SyncRules.incomingFolder("Photos/2024", video = false))
        assertEquals("Pictures/Tetra", SyncRules.incomingFolder("", video = false))
        assertEquals("Pictures/Tetra", SyncRules.incomingFolder("../escape", video = false))
        assertEquals("Movies/Tetra", SyncRules.incomingFolder("Home videos", video = true))
        assertEquals("Movies/Tetra", SyncRules.incomingFolder("Documents/clips", video = true))
    }
}

class SyncConnectionOffTest {
    @Test
    fun `off means neither way, and says so`() {
        val off = SyncConnection("photos", "off", "everything")
        assertFalse(off.sends)
        assertFalse(off.receives)
        assertNull(off.arrow) // said "Photos · not synced"
    }

    @Test fun eachPathFollowsItsOwnRow() {
        assertEquals("mail", SyncConnection.of("Mail/index.jsonl"))
        assertEquals("other", SyncConnection.of("Calendar/Personal/a.ics"))
        assertEquals("other", SyncConnection.of("AI Assist/ai.json"))
        assertEquals("files", SyncConnection.of("Trash/a.ics")) // the Trash is Files'
        assertEquals("files", SyncConnection.of("Documents/a.txt"))
        val mail = SyncConnection("mail", "send", "nothing")
        assertTrue(mail.sends && mail.receives) // mail goes both ways unless off
        assertFalse(SyncConnection("mail", "off", "everything").let { it.sends || it.receives })
        // a computer from before the four rows: mail and other as its files row
        val old = SyncConnection.complete(listOf(SyncConnection("photos", "send", "nothing"), SyncConnection("files", "receive", "everything")))
        assertEquals(listOf("photos", "files", "mail", "other"), old.map { it.content })
        assertEquals("receive", old.first { it.content == "other" }.direction)
    }

    @Test fun automaticKeepsEverything() {
        assertFalse(SyncConnection("photos", "send", "nothing", keepAuto = true).moves) // nothing goes by itself
        assertTrue(SyncConnection("photos", "send", "nothing", 730).moves) // the PC's suggested Move, once confirmed
    }

    @Test fun aTransfersLeftoverGoesAfterADay() {
        val root = java.nio.file.Files.createTempDirectory("tetra").toFile()
        val old = java.io.File(root, "Documents/From other apps/.WIHSF.pdf.0b1c.part").apply { parentFile!!.mkdirs(); writeText("x"); setLastModified(System.currentTimeMillis() - 2 * 86_400_000L) }
        val fresh = java.io.File(root, "From phone/.Screenshot.jpg.9f.part").apply { parentFile!!.mkdirs(); writeText("x") } // being written now
        val system = java.io.File(root, ".System/Mail/Shared/.index.jsonl.part").apply { parentFile!!.mkdirs(); writeText("x"); setLastModified(0) }
        java.io.File(root, "Documents/a.part").apply { writeText("x"); setLastModified(0) } // a file of the user's, not hidden
        assertEquals(setOf(old, system), SyncRules.staleParts(root).toSet())
        assertTrue(fresh.exists())
        root.deleteRecursively()
    }
}

