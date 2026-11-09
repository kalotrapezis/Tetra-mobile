package com.kalotrapezis.drive

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserTest {
    @Test fun site() {
        assertEquals("example.com", BrowserRules.site("a.b.example.com"))
        assertEquals("kathimerini.com.gr", BrowserRules.site("www.kathimerini.com.gr"))
        assertEquals("bbc.co.uk", BrowserRules.site("news.bbc.co.uk"))
        assertEquals("sch.gr", BrowserRules.site("mail.sch.gr"))
    }

    @Test fun historyLasts24Hours() {
        val h = 3600_000L
        val now = 100 * h
        val old = listOf(Visit("https://a.gr/", "A", now - 25 * h), Visit("https://b.gr/", "B", now - 23 * h), Visit("https://c.gr/", "C", now - 1 * h))
        assertEquals(listOf("https://b.gr/", "https://c.gr/"), BrowserRules.fresh(old, now).map { it.url })
        val after = BrowserRules.visit(old, Visit("https://b.gr/", "B2", now))
        assertEquals(listOf("https://b.gr/", "https://c.gr/"), after.map { it.url }) // once, the newest first; A forgotten
        assertEquals("B2", after.first().title)
        assertEquals(after, BrowserRules.visits(BrowserRules.visitsJson(after)))
        assertTrue(BrowserRules.fresh(after, now + 24 * h).isEmpty())
    }

    @Test fun addressBar() {
        assertEquals("https://kathimerini.gr", BrowserRules.target("kathimerini.gr"))
        assertEquals("https://www.sch.gr/path?x=1", BrowserRules.target(" www.sch.gr/path?x=1 "))
        assertEquals("http://x.gr", BrowserRules.target("http://x.gr"))
        assertEquals("https://localhost:8080", BrowserRules.target("localhost:8080"))
        assertEquals(BrowserRules.SEARCH + "%CE%BA%CE%B1%CE%B9%CF%81%CF%8C%CF%82+x.gr", BrowserRules.target("καιρός x.gr"))
        assertTrue(BrowserRules.target("javascript:alert(1)").startsWith(BrowserRules.SEARCH)) // never run from the address bar
        assertTrue(BrowserRules.target("file:///sdcard/x").startsWith(BrowserRules.SEARCH))
        assertEquals("www.sch.gr", BrowserRules.host("https://user@WWW.sch.gr:443/a?b"))
    }

    @Test fun firefoxLogins() {
        val csv = "﻿\"url\",\"username\",\"password\",\"httpRealm\",\"formActionOrigin\",\"guid\",\"timeCreated\",\"timeLastUsed\",\"timePasswordChanged\"\r\n" +
            "\"https://accounts.example.com\",\"teo\",\"p\"\"a,ss\",,\"https://accounts.example.com\",\"{1}\",\"1\",\"1\",\"1\"\r\n" +
            "\"https://x.gr\",\"\",\"multi\nline\",,\"\",\"{2}\",\"1\",\"1\",\"1\"\r\n" +
            "\"chrome://FirefoxAccounts\",\"a\",\"\",,,\"{3}\",\"1\",\"1\",\"1\"\r\n"
        val r = FirefoxImport.read(csv)!!
        assertEquals(listOf(SavedLogin("accounts.example.com", "teo", "p\"a,ss"), SavedLogin("x.gr", "", "multi\nline")), r.logins)
        assertNull(FirefoxImport.read("just some text\nno header"))
    }

    @Test fun firefoxBookmarks() {
        val html = """<!DOCTYPE NETSCAPE-Bookmark-file-1>
            <DL><p><DT><H3>Toolbar</H3><DL><p>
            <DT><A HREF="https://www.sch.gr/" ADD_DATE="1" ICON="data:x">Πανελλήνιο &amp; Σχολικό</A>
            <DT><A HREF="place:sort=8">Recent</A>
            </DL><p></DL>"""
        assertEquals(listOf(Bookmark("https://www.sch.gr/", "Πανελλήνιο & Σχολικό", folder = "Toolbar")), FirefoxImport.read(html)!!.bookmarks)
        val json = """{"title":"","children":[{"title":"menu","children":[{"title":"Tetra","type":"text/x-moz-place","uri":"https://tetra.example/"},
            {"title":"sep","type":"text/x-moz-place-separator"}]},{"title":"toolbar","children":[{"title":"Kathimerini","uri":"https://kathimerini.gr"}]}]}"""
        assertEquals(listOf(Bookmark("https://tetra.example/", "Tetra"), Bookmark("https://kathimerini.gr", "Kathimerini", folder = "Bookmarks Toolbar")), FirefoxImport.read(json)!!.bookmarks)
    }

    /** Firefox's own export, its shape kept: the menu's pages at the top, the toolbar's folders nested, a "/" in a name not a level. */
    @Test fun importKeepsTheFolderTree() {
        val html = """<!DOCTYPE NETSCAPE-Bookmark-file-1>
            <META HTTP-EQUIV="Content-Type" CONTENT="text/html; charset=UTF-8">
            <TITLE>Bookmarks</TITLE>
            <H1>Bookmarks Menu</H1>
            <DL><p>
                <DT><A HREF="https://menu.gr/" ADD_DATE="1">Menu page</A>
                <DT><H3 ADD_DATE="1" PERSONAL_TOOLBAR_FOLDER="true">Bookmarks Toolbar</H3>
                <DL><p>
                    <DT><H3>News</H3>
                    <DL><p>
                        <DT><A HREF="https://kathimerini.gr/">Kathimerini</A>
                        <DT><H3>Sport / Local</H3>
                        <DL><p><DT><A HREF="https://sport.gr/">Sport</A></DL><p>
                    </DL><p>
                    <DT><A HREF="https://toolbar.gr/">On the bar</A>
                    <DT><H3>Empty</H3>
                    <DL><p></DL><p>
                </DL><p>
                <DT><A HREF="https://after.gr/">After the toolbar</A>
            </DL>"""
        val got = FirefoxImport.read(html)!!.bookmarks.map { it.url to it.folder }
        assertEquals(listOf("https://menu.gr/" to "", "https://kathimerini.gr/" to "Bookmarks Toolbar/News", "https://sport.gr/" to "Bookmarks Toolbar/News/Sport ∕ Local",
            "https://toolbar.gr/" to "Bookmarks Toolbar", "https://after.gr/" to ""), got)

        // into the library: all under Imported, the start page as it was
        val mine = BookmarkLibrary(listOf(Bookmark("https://radio.gr/", "Radio", folder = "Radios"), Bookmark("https://menu.gr/", "Mine")))
        val lib = mine.imported(FirefoxImport.read(html)!!.bookmarks)
        assertEquals(listOf("Radios", "", "Imported/Bookmarks Toolbar/News", "Imported/Bookmarks Toolbar/News/Sport ∕ Local", "Imported/Bookmarks Toolbar", "Imported"), lib.marks.map { it.folder })
        assertEquals("Mine", lib.marks[1].title) // a page already kept stays where it was
        assertEquals(mine.start(""), lib.start(""))
        assertEquals(listOf("Imported/Bookmarks Toolbar", "Imported/Bookmarks Toolbar/News", "Imported/Bookmarks Toolbar/News/Sport ∕ Local"),
            lib.tree().filter { it.startsWith("Imported/") })
        assertEquals(listOf("Radios", "Imported"), lib.subfolders(""))
        assertEquals(4, lib.count("Imported"))
    }

    @Test fun folderPaths() {
        assertEquals("Imported/Toolbar", BookmarkLibrary.parentOf("Imported/Toolbar/News"))
        assertEquals("", BookmarkLibrary.parentOf("Radios"))
        assertEquals("News", BookmarkLibrary.nameOf("Imported/Toolbar/News"))
        assertTrue(BookmarkLibrary.under("Radios/Greek", "Radios"))
        assertFalse(BookmarkLibrary.under("Radios2", "Radios"))
        assertEquals("Radios/Greek", BookmarkLibrary.path(" /Radios// Greek /"))
        val lib = BookmarkLibrary(listOf(Bookmark("https://a.gr", "A", folder = "X/Y/Z"), Bookmark("https://b.gr", "B", folder = "X"), Bookmark("https://c.gr", "C")), listOf("W"))
        assertEquals(listOf("X", "X/Y", "X/Y/Z", "W"), lib.folders)
        assertEquals(listOf("X", "X/Y", "X/Y/Z", "W"), lib.tree())

        val renamed = lib.rename("X/Y", "Q/R") // a "/" typed in a name is not a level
        assertEquals(listOf("X/Q∕R/Z", "X", ""), renamed.marks.map { it.folder })
        val moved = lib.move(setOf("https://c.gr"), setOf("X/Y"), "W")
        assertEquals(listOf("W/Y/Z", "X", "W"), moved.marks.map { it.folder })
        assertTrue("X/Y" !in moved.folders && "W/Y" in moved.folders)
        assertEquals(lib.marks, lib.move(emptySet(), setOf("X"), "X/Y").marks) // not into itself
        val emptied = lib.delete(setOf("https://b.gr"), setOf("X/Y"))
        assertEquals(listOf("https://c.gr"), emptied.marks.map { it.url })
        assertEquals(listOf("X", "W"), emptied.folders) // X kept, though empty now
        assertEquals(listOf(true, true), lib.pin(setOf("https://a.gr", "https://b.gr")).marks.take(2).map { it.pinned })
        assertEquals(emptied.extra, BookmarkLibrary.folders(BookmarkLibrary.json(emptied.extra)))
    }

    /** What 0.8.0 saved (flat folder names, some fields left out) reads as it was. */
    @Test fun oldBookmarksStillLoad() {
        val saved = """[{"url":"https://live24.gr/radio/derti.jsp","title":"Derti","folder":"Radios"},{"url":"https://bank.gr/","title":"Bank","pinned":true},{"url":"https://x.gr/"}]"""
        val b = BrowserRules.bookmarks(saved)
        assertEquals(listOf(Bookmark("https://live24.gr/radio/derti.jsp", "Derti", folder = "Radios"), Bookmark("https://bank.gr/", "Bank", pinned = true), Bookmark("https://x.gr/", "")), b)
        val lib = BookmarkLibrary(b, BookmarkLibrary.folders(null))
        assertEquals(listOf("Radios"), lib.folders)
        assertEquals(listOf("https://bank.gr/", "https://x.gr/") to listOf("Radios"), lib.start("").let { (m, f) -> m.map { it.url } to f })
    }

    @Test fun vault() {
        val form = JSONObject("""{"user":"teo","pass":"secret","fields":{"email":"t@x.gr","cc-number":"4111 1111 1111 1111","cc-exp":"7/29","cc-name":"T K"}}""")
        val got = BrowserVault.fromForm("shop.gr", form)
        assertEquals(listOf(SavedLogin("shop.gr", "teo", "secret")), got.logins)
        assertTrue(got.addresses.isEmpty()) // an email alone is no address
        assertEquals(SavedCard("T K", "4111111111111111", "07", "29"), got.cards.single())
        val v = BrowserVault().with(got)
        assertTrue(v.news(got).empty) // the same again asks nothing
        val changed = BrowserVault.fromForm("shop.gr", JSONObject("""{"user":"teo","pass":"new"}"""))
        assertEquals(listOf(SavedLogin("shop.gr", "teo", "new")), v.with(changed).logins) // replaced, not added
        assertEquals(v, BrowserVault.of(v.json()))
        val twice = v.with(BrowserVault(logins = listOf(SavedLogin("x.gr", "a", "1"), SavedLogin("x.gr", "a", "2")))) // http and https in one import
        assertEquals(listOf(SavedLogin("shop.gr", "teo", "secret"), SavedLogin("x.gr", "a", "2")), twice.logins)
        assertTrue(BrowserRules.sameHost("www.shop.gr", "shop.gr"))
        assertFalse(BrowserRules.sameHost("evil.shop.gr", "shop.gr"))
    }

    @Test fun mediaSites() {
        assertEquals("PLx1-_y", MediaSites.listId("https://m.youtube.com/watch?v=abc&list=PLx1-_y&index=2"))
        assertEquals("RDabc", MediaSites.listId("https://music.youtube.com/watch?list=RDabc"))
        assertNull(MediaSites.listId("https://m.youtube.com/watch?v=abc&playlist=no"))
        assertNull(MediaSites.listId(null))
        assertEquals("youtube", MediaSites.of("M.YouTube.com")?.id)
        assertEquals("youtube_music", MediaSites.of("music.youtube.com")?.id)
        assertNull(MediaSites.of("soundcloud.com")) // a site without a line: the page's own media keys
        assertEquals("youtube", MediaSites.byId(null).id) // plain YouTube by default
        assertEquals("https://m.youtube.com/results?search_query=%CF%83%CE%B1%CE%B2%CE%B2%CE%B1%CF%84%CE%BF%CE%B2%CF%81%CE%B1%CE%B4%CE%BF+live",
            MediaSites.searchUrl(MediaSites.byId("youtube"), " σαββατοβραδο live "))
        assertEquals("https://music.youtube.com/search?q=Haris+Alexiou", MediaSites.searchUrl(MediaSites.byId("youtube_music"), "Haris Alexiou"))
        assertEquals("", JSONObject(MediaSites.buttons(null)).getString("next"))
    }

    @Test fun spotifySearchesInGreekThenGreeklish() {
        val sp = MediaSites.byId("spotify")
        assertEquals("spotify", MediaSites.of("open.spotify.com")?.id)
        assertEquals("https://open.spotify.com/search/Haris%20Alexiou/tracks", MediaSites.searchUrl(sp, " Haris Alexiou "))
        assertEquals("https://open.spotify.com/search/chill/playlists", MediaSites.searchUrl(sp, "chill", list = true))
        assertTrue(MediaSites.isSearch(sp, MediaSites.searchUrl(sp, "adele")))
        assertFalse(MediaSites.isSearch(sp, "https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC"))
        assertEquals("antonis remos", MediaSites.greeklish("Αντώνης Ρέμος"))
        assertEquals("despina vandi", MediaSites.greeklish("Δέσποινα Βανδή"))
        assertEquals("notis sfakianakis", MediaSites.greeklish("Νότης Σφακιανάκης"))
        assertEquals("bes 2 - dimitris basis", MediaSites.greeklish("Μπες 2 - Δημήτρης Μπάσης"))
        assertEquals("giorgos dalaras", MediaSites.greeklish("Γιώργος Νταλάρας")) // as Spotify writes him
        assertEquals("eleftheria arvanitaki", MediaSites.greeklish("Ελευθερία Αρβανιτάκη"))
        // as Spotify writes them (9 Nov)
        assertEquals("giannis parios", MediaSites.greeklish("Γιάννης Πάριος"))
        assertEquals("pix lax", MediaSites.greeklish("Πυξ Λαξ"))
        assertEquals("haris & panos katsimihas", MediaSites.greeklish("Χάρης & Πάνος Κατσιμίχας"))
        assertEquals("na gini to nero fotia", MediaSites.greeklish("Να γίνει το νερό φωτιά"))
        assertEquals("an den iha ke sena", MediaSites.greeklish("Αν δεν είχα και σένα"))
        assertEquals("mia sinousia mistiki", MediaSites.greeklish("Μια συνουσία μυστική"))
        assertEquals(listOf("lavrentis maheritsas", "lavrentis machairitsas"), MediaSites.retries("Λαυρέντης Μαχαιρίτσας")) // Spotify: «Lavrentis Machairitsas»
        assertEquals(listOf("dimitris mitropanos"), MediaSites.retries("Δημήτρης Μητροπάνος")) // the same both ways: once
        assertEquals("pyx lax", MediaSites.greeklish("Πυξ Λαξ", sound = false))
        assertNull(MediaSites.greeklish("Imagine Dragons")) // nothing Greek: no second search
        assertEquals(emptyList<String>(), MediaSites.retries("Imagine Dragons"))
        assertEquals("[data-testid=now-playing-widget] button[aria-checked=false]", JSONObject(MediaSites.buttons(sp)).getString("like"))
    }

    @Test fun hopThroughOneSitesFavourites() {
        val b = listOf(Bookmark("https://live24.gr/radio/derti.jsp", "Derti"), Bookmark("https://www.wikipedia.org/", "W"),
            Bookmark("https://live24.gr/radio/sfera.jsp", "Sfera"), Bookmark("https://live24.gr/radio/melodia98.jsp", "Melodia"))
        assertEquals("https://live24.gr/radio/sfera.jsp", BrowserRules.hop(b, "https://live24.gr/radio/derti.jsp", true))
        assertEquals("https://live24.gr/radio/derti.jsp", BrowserRules.hop(b, "https://live24.gr/radio/melodia98.jsp", true)) // round
        assertEquals("https://live24.gr/radio/melodia98.jsp", BrowserRules.hop(b, "https://live24.gr/radio/derti.jsp", false))
        assertEquals("https://live24.gr/radio/derti.jsp", BrowserRules.hop(b, "https://live24.gr/radio/kissfm.jsp", true)) // not a favourite: the first
        assertEquals(null, BrowserRules.hop(b, "https://www.wikipedia.org/", true)) // no other on that site
    }

    @Test fun folders() {
        val b = listOf(Bookmark("https://live24.gr/radio/derti.jsp", "Derti", folder = "Radios"), Bookmark("https://www.ertecho.gr/radio/er1/", "ERT", folder = "Radios"),
            Bookmark("https://live24.gr/radio/sfera.jsp", "Sfera"), Bookmark("https://bank.gr/", "Bank", pinned = true, folder = "Mine"))
        assertEquals(listOf("Radios", "Mine"), BookmarkLibrary(b).folders)
        assertEquals(b, BrowserRules.bookmarks(BrowserRules.bookmarksJson(b)))
        // in a folder: that folder's, whatever the site; outside one: the site's as before
        assertEquals("https://www.ertecho.gr/radio/er1/", BrowserRules.hop(b, "https://live24.gr/radio/derti.jsp", true))
        assertEquals("https://live24.gr/radio/derti.jsp", BrowserRules.hop(b, "https://live24.gr/radio/sfera.jsp", true))
    }

    @Test fun htmlOnly() {
        val sites = setOf("kathimerini.gr")
        assertTrue(BrowserRules.htmlOnly(sites, "https://www.kathimerini.gr/politics/x"))
        assertFalse(BrowserRules.htmlOnly(sites, "https://news.gr/"))
        assertFalse(BrowserRules.htmlOnly(sites, ""))
    }

    @Test fun toolsOnTheBar() {
        assertEquals(BrowserTools.PHONE, BrowserTools.parse(null, wide = false))
        assertEquals(listOf("copy", "fill"), BrowserTools.parse("copy,nonsense,fill,copy", wide = false)) // unknown and twice dropped
        val bar = BrowserTools.PHONE
        assertNull(BrowserTools.toggle(bar, "player", wide = false)) // six on a phone: full
        assertEquals(listOf("fill", BrowserTools.UBLOCK, "private", "copy", "share"), BrowserTools.toggle(bar, "bookmarks", wide = false))
        // the old ad blocker's place goes to uBlock Origin's button; an extension's button is kept as "ext:" and its id
        assertEquals(listOf("fill", BrowserTools.UBLOCK, "ext:addon@darkreader.org"), BrowserTools.parse("fill,ads,ext:addon@darkreader.org", wide = false))
        assertNull(BrowserTools.toggle(BrowserTools.TABLET, "player", wide = true)) // a tablet's defaults fill its line
        assertEquals(BrowserTools.TABLET - "split" + "player", BrowserTools.toggle(BrowserTools.TABLET - "split", "player", wide = true))
    }

    @Test fun tabRow() {
        val tabs = listOf(TabInfo(1, false, null), TabInfo(2, false, 7), TabInfo(3, true, null), TabInfo(4, false, 7), TabInfo(5, false, null))
        assertEquals(listOf(TabRowItem.Tab(3, true, false), TabRowItem.Tab(1, false, false), TabRowItem.Group(7, 2, false), TabRowItem.Tab(5, false, false)),
            BrowserTabRow.items(tabs, emptySet())) // pinned first; the group once, where its first tab was
        assertEquals(listOf(TabRowItem.Tab(3, true, false), TabRowItem.Tab(1, false, false), TabRowItem.Group(7, 2, true),
            TabRowItem.Tab(2, false, true), TabRowItem.Tab(4, false, true), TabRowItem.Tab(5, false, false)), BrowserTabRow.items(tabs, setOf(7))) // unfolded
        assertEquals(BrowserTools.TABLET.size, BrowserTools.max(true)) // a tablet's line holds its defaults
    }

    @Test fun historyChoiceAndPinnedFavourites() {
        val h = 3600_000L
        val old = listOf(Visit("https://a.gr/", "A", 10 * h))
        assertEquals(old, BrowserRules.fresh(old, 100 * h, BrowserRules.NEVER)) // never forgotten
        assertTrue(BrowserRules.fresh(old, 100 * h, BrowserRules.DAY).isEmpty())
        assertEquals(2, BrowserRules.visit(old, Visit("https://b.gr/", "B", 100 * h), BrowserRules.NEVER).size)
        val b = listOf(Bookmark("https://1.gr", "1"), Bookmark("https://2.gr", "2", pinned = true), Bookmark("https://3.gr", "3"))
        assertEquals(listOf("2", "3", "1"), BrowserRules.front(b).map { it.title }) // pinned first, then the newest
        assertEquals(b, BrowserRules.bookmarks(BrowserRules.bookmarksJson(b)))
    }

    @Test fun keptTabs() {
        val tabs = listOf(SavedTab("https://a.gr", "A", true, null), SavedTab("https://b.gr", "B", false, 3), SavedTab("https://c.gr", "C", false, null), SavedTab("", "", true, null))
        val h = 3600_000L
        val saved = SavedTabs.json(tabs, mapOf(3 to "Σχολείο", 9 to "empty"), now = 100 * h)
        val (back, names) = SavedTabs.read(saved, BrowserRules.ON_EXIT, 100 * h)
        assertEquals(tabs.take(2).map { it.copy(at = 100 * h) }, back) // history to the end of the session: only pinned and grouped, with an address
        assertEquals(listOf("A", "B", "C"), SavedTabs.read(saved, BrowserRules.DAY, 110 * h).first.map { it.title }) // 24 h: all, within the day
        assertEquals(listOf("A", "B"), SavedTabs.read(saved, BrowserRules.DAY, 125 * h).first.map { it.title }) // ... and the others gone after it
        assertEquals(3, SavedTabs.read(saved, BrowserRules.NEVER, 900 * h).first.size) // never forgotten
        assertEquals(mapOf(3 to "Σχολείο"), names) // a group with no tab is gone
        assertEquals(emptyList<SavedTab>() to emptyMap<Int, String>(), SavedTabs.read("not json"))
    }

    @Test fun dateInputs() {
        val d = java.time.LocalDate.of(2026, 11, 3)
        val T = org.mozilla.geckoview.GeckoSession.PromptDelegate.DateTimePrompt.Type::class.java
        fun t(name: String) = T.getField(name).getInt(null)
        assertEquals("2026-11-03", BrowserDates.format(t("DATE"), d, 0, 0))
        assertEquals("2026-11", BrowserDates.format(t("MONTH"), d, 0, 0))
        assertEquals("2026-W45", BrowserDates.format(t("WEEK"), d, 0, 0))
        assertEquals("07:05", BrowserDates.format(t("TIME"), null, 7, 5))
        assertEquals("2026-11-03T21:30", BrowserDates.format(t("DATETIME_LOCAL"), d, 21, 30))
        assertEquals(java.time.LocalDate.of(2026, 11, 2), BrowserDates.date("2026-W45", t("WEEK"))) // its Monday
        assertEquals(java.time.LocalDate.of(2026, 11, 1), BrowserDates.date("2026-11", t("MONTH")))
        assertEquals(d, BrowserDates.date("2026-11-03T08:00", t("DATETIME_LOCAL")))
        assertNull(BrowserDates.date("", t("DATE")))
        assertEquals(8 to 0, BrowserDates.time("2026-11-03T08:00"))
        assertEquals(12 to 0, BrowserDates.time(""))
    }

    @Test fun aSitesResultsPageIsKnownByItsPathNotTheAddressSent() {
        val yt = MediaSites.byId("youtube")
        assertTrue(MediaSites.isSearch(yt, MediaSites.searchUrl(yt, "imagine dragons believer")))
        assertTrue(MediaSites.isSearch(yt, "https://www.youtube.com/results?app=desktop&search_query=imagine+dragons")) // a tablet's desktop site
        assertFalse(MediaSites.isSearch(yt, "https://m.youtube.com/watch?v=7wtfhZwyrcc"))
        assertFalse(MediaSites.isSearch(yt, "https://consent.youtube.com/m?continue=https://m.youtube.com/results?search_query=x"))
        val music = MediaSites.byId("youtube_music")
        assertTrue(MediaSites.isSearch(music, MediaSites.searchUrl(music, "adele")))
        assertFalse(MediaSites.isSearch(music, "https://m.youtube.com/results?search_query=adele"))
        // a kind of music: YouTube's playlists; YouTube Music has no such search here, so its plain one
        val lists = MediaSites.searchUrl(yt, "disco", list = true)
        assertEquals("https://m.youtube.com/results?sp=EgIQAw%3D%3D&search_query=disco", lists)
        assertTrue(MediaSites.isSearch(yt, lists))
        assertEquals("https://music.youtube.com/search?q=disco", MediaSites.searchUrl(music, "disco", list = true))
    }

    @Test fun aSavedStationIsFoundByTheWordsSaid() {
        val marks = listOf(Bookmark("https://www.ertecho.gr/radio/trito/", "Τρίτο Πρόγραμμα", folder = "Ραδιόφωνο"),
            Bookmark("https://kosmos.ertecho.gr/live", "ERT Kosmos 93.6"), Bookmark("https://news.example.com", "News"),
            Bookmark("https://www.melodiafm.gr/live", "Μελωδία 99.2", folder = "Ραδιόφωνο"))
        assertEquals("ERT Kosmos 93.6", MediaSites.station(marks, "βάλε Kosmos")?.title)
        assertEquals("Μελωδία 99.2", MediaSites.station(marks, "Μελωδία FM")?.title)
        assertEquals("Τρίτο Πρόγραμμα", MediaSites.station(marks, "το τρίτο πρόγραμμα")?.title)
        assertNull(MediaSites.station(marks, "radio"))
        assertNull(MediaSites.station(marks, "Sfera"))
    }

    @Test fun aSitesIconIsOneFileForAllItsAddresses() {
        val k = BrowserIcons.key("https://m.youtube.com/watch?v=x")
        assertEquals(k, BrowserIcons.key("https://www.youtube.com/"))
        assertEquals(k, BrowserIcons.key("https://youtube.com/results?search_query=disco"))
        assertEquals("youtube.com.png", BrowserIcons.fileName(k))
        assertEquals("a_b.c.png", BrowserIcons.fileName("a/b.c")) // never a path out of its folder
    }
}
