package com.kalotrapezis.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Bookmarks and tabs between devices (BrowserSync.kt): two or three devices' folders, files copied between them as sync does. */
class BrowserSyncTest {
    @get:Rule val tmp = TemporaryFolder()

    private val day = 86_400_000L
    private fun b(url: String, folder: String = "", title: String = url.substringAfter("//"), pinned: Boolean = false) = Bookmark(url, title, pinned, folder)

    /** A device: its own Browser folder and its library (its prefs); [sync] runs BrowserSync.bookmarks, [from] is sync carrying the others' files here (never an older copy of its own:
     * only this device writes that one). */
    private inner class Device(val id: String, var lib: BookmarkLibrary = BookmarkLibrary(emptyList())) {
        val root: File = tmp.newFolder(id)
        val base = File(tmp.newFolder("$id-app"), "bookmarks.json") // the app's own storage
        fun sync(now: Long) = BrowserSync.bookmarks(root, id, lib, base, now).also { lib = it }
        fun keep(l: BookmarkLibrary, now: Long) { lib = l; sync(now) }
        fun from(o: Device) = File(o.root, "Bookmarks").listFiles().orEmpty().filter { !it.name.startsWith(".") && it.name != "$id.json" }.forEach { it.copyTo(File(root, "Bookmarks/${it.name}"), overwrite = true) }
        fun urls() = lib.marks.map { it.url }.toSet()
        fun folderOf(url: String) = lib.marks.first { it.url == url }.folder
    }
    private fun both(a: Device, b: Device, now: Long) { a.from(b); b.from(a); a.sync(now); b.sync(now); a.from(b); b.from(a) }

    @Test fun firstSyncIsAUnionOfWhatEachHad() {
        val phone = Device("phone", BookmarkLibrary(listOf(b("https://a.gr", "Radios"), b("https://same.gr", "News", "Phone's")), listOf("Empty")))
        val tablet = Device("tablet", BookmarkLibrary(listOf(b("https://c.gr"), b("https://same.gr", "News", "Tablet's"), b("https://d.gr", "Radios"))))
        phone.sync(1_000); tablet.sync(2_000)
        both(phone, tablet, 3_000)
        assertEquals(setOf("https://a.gr", "https://same.gr", "https://c.gr", "https://d.gr"), phone.urls())
        assertEquals(phone.urls(), tablet.urls())
        assertEquals(1, phone.lib.marks.count { it.url == "https://same.gr" }) // no duplicate by address
        assertEquals("Tablet's", phone.lib.marks.first { it.url == "https://same.gr" }.title) // the later first sync's
        assertEquals(setOf("Radios", "News", "Empty"), phone.lib.folders.toSet()) // the empty folder too, and "Radios" once
        assertEquals(phone.lib.folders.toSet(), tablet.lib.folders.toSet())
        // the phone's own order kept, what came appended
        assertEquals(listOf("https://a.gr", "https://same.gr"), phone.lib.marks.take(2).map { it.url })
    }

    @Test fun oldLocalDataMigratesWhole() {
        // what the browser kept before sync (prefs "bookmarks", "bookmark_folders"): pinned, nested, an empty folder
        val old = BrowserRules.bookmarks("""[{"url":"https://x.gr","title":"X","pinned":true,"folder":"Imported/Toolbar/News"},{"url":"https://y.gr","title":"Y"}]""")
        val phone = Device("phone", BookmarkLibrary(old, BookmarkLibrary.folders("""["Imported/Toolbar/News","Kept"]""")))
        val shown = phone.sync(5_000)
        assertEquals(old, shown.marks)
        assertEquals(setOf("Imported", "Imported/Toolbar", "Imported/Toolbar/News", "Kept"), shown.folders.toSet())
        val written = BookmarkSet.parse(File(phone.root, "Bookmarks/phone.json").readText())!!
        assertEquals(2, written.marks.size)
        assertEquals(4, written.folders.size)
        assertEquals(shown.copy(moves = emptyList()), written.library(shown)) // the file alone gives the same library
        // nothing changed: nothing written again
        val f = File(phone.root, "Bookmarks/phone.json"); f.setLastModified(1_000)
        phone.sync(6_000)
        assertEquals(1_000, f.lastModified())
    }

    @Test fun addedOnOneShowsOnTheOther() {
        val phone = Device("phone"); val tablet = Device("tablet")
        both(phone, tablet, 1_000)
        phone.keep(BookmarkLibrary(phone.lib.marks + b("https://new.gr", pinned = true)), 2_000)
        tablet.from(phone); tablet.sync(3_000)
        assertEquals(setOf("https://new.gr"), tablet.urls())
        assertTrue(tablet.lib.marks.single().pinned)
    }

    @Test fun concurrentEditsTheNewerWins() {
        val phone = Device("phone", BookmarkLibrary(listOf(b("https://a.gr", title = "A"), b("https://b.gr", title = "B"))))
        val tablet = Device("tablet")
        phone.sync(1_000); both(phone, tablet, 1_500)
        phone.keep(phone.lib.edit("https://a.gr", "A by phone", "https://a.gr"), 2_000)
        tablet.keep(tablet.lib.edit("https://a.gr", "A by tablet", "https://a.gr").edit("https://b.gr", "B by tablet", "https://b.gr"), 3_000)
        phone.keep(phone.lib.pin(setOf("https://b.gr")), 4_000) // a later change to B on the phone: pin
        both(phone, tablet, 5_000)
        for (d in listOf(phone, tablet)) {
            assertEquals("A by tablet", d.lib.marks.first { it.url == "https://a.gr" }.title)
            val bb = d.lib.marks.first { it.url == "https://b.gr" }
            assertTrue(bb.pinned) // the page is one record: its newest change whole
            assertEquals("B", bb.title)
        }
    }

    @Test fun deleteAgainstEdit() {
        val phone = Device("phone", BookmarkLibrary(listOf(b("https://a.gr"), b("https://b.gr"))))
        val tablet = Device("tablet")
        phone.sync(1_000); both(phone, tablet, 1_500)
        // A: deleted on the phone, then edited on the tablet → kept, edited. B: edited on the tablet, then deleted on the phone → gone
        phone.keep(phone.lib.delete(setOf("https://a.gr"), emptySet()), 2_000)
        tablet.keep(tablet.lib.edit("https://a.gr", "A edited", "https://a.gr").edit("https://b.gr", "B edited", "https://b.gr"), 3_000)
        phone.keep(phone.lib.delete(setOf("https://b.gr"), emptySet()), 4_000)
        both(phone, tablet, 5_000)
        for (d in listOf(phone, tablet)) {
            assertEquals(setOf("https://a.gr"), d.urls())
            assertEquals("A edited", d.lib.marks.single().title)
        }
    }

    @Test fun deletePropagatesAndTombstonesAreCleanedAfter30Days() {
        val phone = Device("phone", BookmarkLibrary(listOf(b("https://a.gr"), b("https://b.gr", "F"))))
        val tablet = Device("tablet")
        phone.sync(1_000); both(phone, tablet, 1_500)
        tablet.keep(tablet.lib.delete(setOf("https://a.gr"), setOf("F")), 2_000)
        both(phone, tablet, 3_000)
        assertTrue(phone.urls().isEmpty())
        assertTrue("F" !in phone.lib.folders)
        val f = File(phone.root, "Bookmarks/phone.json")
        assertTrue(BookmarkSet.parse(f.readText())!!.marks.getValue("https://a.gr").gone) // the tombstone travels
        phone.sync(2_000 + 31 * day)
        assertTrue(BookmarkSet.parse(f.readText())!!.marks.isEmpty())
        assertTrue(BookmarkSet.parse(f.readText())!!.folders.isEmpty())
    }

    @Test fun folderRenamedOnOneWhileAPageIsMovedOnTheOther() {
        val phone = Device("phone", BookmarkLibrary(listOf(b("https://x.gr", "News/Greek"), b("https://y.gr", "News"), b("https://z.gr")), listOf("Later")))
        val tablet = Device("tablet")
        phone.sync(1_000); both(phone, tablet, 1_500)
        tablet.keep(tablet.lib.move(setOf("https://y.gr"), emptySet(), "Later"), 3_000) // moved later than the rename
        phone.keep(phone.lib.rename("News", "Daily"), 2_000)
        tablet.keep(tablet.lib.move(setOf("https://z.gr"), emptySet(), "News/Greek"), 2_500) // into the folder renamed elsewhere
        both(phone, tablet, 4_000)
        for (d in listOf(phone, tablet)) {
            assertEquals("Later", d.folderOf("https://y.gr"))
            assertEquals("Daily/Greek", d.folderOf("https://x.gr"))
            assertEquals("Daily/Greek", d.folderOf("https://z.gr"))
            assertFalse(d.lib.folders.any { it.startsWith("News") })
        }
        // and the other way round in time: the rename after the move
        phone.keep(phone.lib.move(setOf("https://y.gr"), emptySet(), "Daily"), 5_000)
        tablet.keep(tablet.lib.rename("Daily", "Today"), 6_000)
        both(phone, tablet, 7_000)
        for (d in listOf(phone, tablet)) {
            assertEquals("Today", d.folderOf("https://y.gr"))
            assertEquals("Today/Greek", d.folderOf("https://x.gr"))
        }
    }

    @Test fun aFolderMovedIntoAnotherKeepsItsPages() {
        val phone = Device("phone", BookmarkLibrary(listOf(b("https://x.gr", "A/Sub"), b("https://y.gr", "B"))))
        val tablet = Device("tablet")
        phone.sync(1_000); both(phone, tablet, 1_500)
        phone.keep(phone.lib.move(emptySet(), setOf("A"), "B"), 2_000)
        tablet.keep(tablet.lib.edit("https://x.gr", "X", "https://x.gr"), 3_000)
        both(phone, tablet, 4_000)
        assertEquals("B/A/Sub", tablet.folderOf("https://x.gr"))
        assertEquals("X", tablet.lib.marks.first { it.url == "https://x.gr" }.title)
    }

    @Test fun aPageAddedToAFolderDeletedElsewhereKeepsTheFolder() {
        val phone = Device("phone", BookmarkLibrary(listOf(b("https://a.gr", "F"))))
        val tablet = Device("tablet")
        phone.sync(1_000); both(phone, tablet, 1_500)
        phone.keep(phone.lib.delete(emptySet(), setOf("F")), 2_000)
        tablet.keep(BookmarkLibrary(tablet.lib.marks + b("https://new.gr", "F"), tablet.lib.extra), 3_000)
        both(phone, tablet, 4_000)
        both(phone, tablet, 5_000)
        for (d in listOf(phone, tablet)) {
            assertEquals(setOf("https://new.gr"), d.urls())
            assertEquals("F", d.folderOf("https://new.gr"))
            assertTrue("F" in d.lib.folders)
        }
    }

    @Test fun foldersMadeOnBothBeforeTheyMetAreOne() {
        assertEquals(BookmarkSet.folderId("Radios"), BookmarkSet().record(BookmarkLibrary(emptyList(), listOf("Radios")), 1).folders.keys.single())
        // a folder renamed away from "A", then a new "A": not the same folder
        val s = BookmarkSet().record(BookmarkLibrary(emptyList(), listOf("A")), 1)
        val renamed = BookmarkLibrary(emptyList(), listOf("A")).rename("A", "B")
        val t = s.record(renamed, 2).record(BookmarkLibrary(emptyList(), listOf("B", "A")), 3)
        assertEquals(2, t.folders.values.count { !it.gone })
        assertEquals(setOf("A", "B"), t.library().folders.toSet())
    }

    @Test fun aLoopOfFoldersIsCutNotEndless() {
        val s = BookmarkSet(folders = mapOf("a" to FolderRec("a", "A", "b", 1), "b" to FolderRec("b", "B", "a", 1)))
        assertEquals(2, s.paths().size) // either way round, but an answer
    }

    @Test fun mergeIsTheSameEitherWay() {
        val x = BookmarkSet(mapOf("u" to MarkRec("u", "X", "", false, 5)))
        val y = BookmarkSet(mapOf("u" to MarkRec("u", "Y", "", false, 5)))
        val gone = BookmarkSet(mapOf("u" to MarkRec("u", "Y", "", false, 5, gone = true)))
        assertEquals(x.merge(y), y.merge(x))
        assertFalse(x.merge(gone).marks.getValue("u").gone) // a tie: the living one
        assertNull(BookmarkSet.parse("{not json"))
    }

    @Test fun tabsFileRoundTripAndOthers() {
        val root = tmp.newFolder("t")
        val tabs = listOf(RemoteTab("https://a.gr", "A", pinned = true), RemoteTab("https://b.gr", "B", group = "Work"), RemoteTab("", "empty"))
        assertTrue(BrowserSync.writeTabs(root, "phone-1", "My phone", tabs))
        assertFalse(BrowserSync.writeTabs(root, "phone-1", "My phone", tabs)) // the same tabs: not written again
        val read = BrowserSync.parseTabs("phone-1", File(root, "Tabs/phone-1.json").readText())!!
        assertEquals("My phone", read.name)
        assertEquals(tabs.dropLast(1), read.tabs) // an empty tab is no page
        BrowserSync.writeTabs(root, "tablet-2", "Tablet", listOf(RemoteTab("https://c.gr", "C")))
        BrowserSync.writeTabs(root, "old-3", "Off", emptyList()) // tabs sync turned off there
        assertEquals(listOf("My phone"), BrowserSync.otherTabs(root, "tablet-2").map { it.name })
        assertEquals(listOf("Tablet"), BrowserSync.otherTabs(root, "phone-1").map { it.name })
    }

    @Test fun anOlderCopyOfItsOwnFileBroughtBackBySyncLosesNothing() {
        val phone = Device("phone", BookmarkLibrary(listOf(b("https://a.gr", "Radios"))))
        val tablet = Device("tablet", BookmarkLibrary(listOf(b("https://t.gr"))))
        phone.sync(1_000); tablet.sync(1_000)
        val old = File(tablet.root, "Bookmarks/tablet.json").readText()
        both(phone, tablet, 2_000)
        phone.keep(phone.lib.rename("Radios", "Stations"), 3_000)
        File(tablet.root, "Bookmarks/tablet.json").writeText(old) // sync brought the tablet's own file back as it was before
        tablet.from(phone); tablet.sync(4_000)
        assertEquals("Stations", tablet.folderOf("https://a.gr")) // the rename stands: nothing here was stamped again
        assertEquals(BookmarkSet.parse(File(tablet.base.path).readText()), BookmarkSet.parse(File(tablet.root, "Bookmarks/tablet.json").readText())) // and the file is whole again
        phone.from(tablet); phone.sync(5_000)
        assertEquals("Stations", phone.folderOf("https://a.gr"))
    }
}
