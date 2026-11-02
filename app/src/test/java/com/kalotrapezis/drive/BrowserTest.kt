package com.kalotrapezis.drive

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BrowserTest {
    private val filter = AdFilter(sequenceOf(
        "! a comment", "example.com##.ad", "/banner/ads.",
        "||ads.example^", "||tracker.net^\$third-party", "||cdn.site.com/ads/*.js",
        "||pop.net^\$popup", "||only.io^\$domain=news.gr|~sport.news.gr", "@@||ads.example/ok^",
    ))

    @Test fun hostRules() {
        assertEquals(5, filter.size) // the popup rule, cosmetic and generic ones left out
        assertTrue(filter.blocks("https://ads.example/x.js", "news.gr"))
        assertTrue(filter.blocks("https://a.b.ads.example/", "news.gr")) // a subdomain too
        assertFalse(filter.blocks("https://ads.example.org/x", "news.gr")) // not another host that only begins so
        assertFalse(filter.blocks("https://badads.example/x", "news.gr"))
        assertFalse(filter.blocks("https://ads.example/ok/1.png", "news.gr")) // the exception
        assertFalse(filter.blocks("https://pop.net/", "news.gr"))
    }

    @Test fun options() {
        assertTrue(filter.blocks("https://px.tracker.net/p.gif", "news.gr"))
        assertFalse(filter.blocks("https://px.tracker.net/p.gif", "www.tracker.net")) // its own site: first party
        assertTrue(filter.blocks("https://only.io/a", "www.news.gr"))
        assertFalse(filter.blocks("https://only.io/a", "sport.news.gr"))
        assertFalse(filter.blocks("https://only.io/a", "other.gr"))
    }

    @Test fun paths() {
        assertTrue(filter.blocks("https://cdn.site.com/ads/top.js", "x.gr"))
        assertFalse(filter.blocks("https://cdn.site.com/img/top.js", "x.gr"))
        assertTrue(AdFilter.glob("^", 0, "", 0))
        assertTrue(AdFilter.glob("^", 0, "/a", 0))
        assertFalse(AdFilter.glob("^", 0, "x", 0))
        assertTrue(AdFilter.glob("/a.js|", 0, "/a.js", 0))
        assertFalse(AdFilter.glob("/a.js|", 0, "/a.js?v=1", 0))
    }

    @Test fun theBundledList() {
        val f = AdFilter(File("src/main/assets/adblock.txt").readLines().asSequence())
        assertTrue(f.size > 50_000)
        assertTrue(f.blocks("https://securepubads.g.doubleclick.net/tag/js/gpt.js", "www.kathimerini.gr"))
        assertFalse(f.blocks("https://www.kathimerini.gr/style.css", "www.kathimerini.gr"))
    }

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
        assertEquals(listOf(Bookmark("https://www.sch.gr/", "Πανελλήνιο & Σχολικό")), FirefoxImport.read(html)!!.bookmarks)
        val json = """{"title":"","children":[{"title":"menu","children":[{"title":"Tetra","type":"text/x-moz-place","uri":"https://tetra.example/"},
            {"title":"sep","type":"text/x-moz-place-separator"}]},{"title":"toolbar","children":[{"title":"Kathimerini","uri":"https://kathimerini.gr"}]}]}"""
        assertEquals(listOf(Bookmark("https://tetra.example/", "Tetra"), Bookmark("https://kathimerini.gr", "Kathimerini")), FirefoxImport.read(json)!!.bookmarks)
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
        assertEquals(listOf("Radios", "Mine"), BrowserRules.folders(b))
        assertEquals(b, BrowserRules.bookmarks(BrowserRules.bookmarksJson(b)))
        // in a folder: that folder's, whatever the site; outside one: the site's as before
        assertEquals("https://www.ertecho.gr/radio/er1/", BrowserRules.hop(b, "https://live24.gr/radio/derti.jsp", true))
        assertEquals("https://live24.gr/radio/derti.jsp", BrowserRules.hop(b, "https://live24.gr/radio/sfera.jsp", true))
    }

    @Test fun mobileAgent() {
        val wv = "Mozilla/5.0 (Linux; Android 15; 24091RPADG Build/AQ3A; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0.6099.230 Mobile Safari/537.36"
        assertEquals("Mozilla/5.0 (Linux; Android 15; 24091RPADG Build/AQ3A) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.230 Mobile Safari/537.36", BrowserRules.mobileAgent(wv))
    }

    @Test fun desktopAgent() {
        val phone = "Mozilla/5.0 (Linux; Android 14; sdk_gphone64_arm64 Build/UE1A.230829.036; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0.6099.230 Mobile Safari/537.36"
        assertEquals("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.230 Safari/537.36", BrowserRules.desktopAgent(phone))
    }

    @Test fun htmlOnly() {
        val sites = setOf("kathimerini.gr")
        assertTrue(BrowserRules.htmlOnly(sites, "https://www.kathimerini.gr/politics/x"))
        assertFalse(BrowserRules.htmlOnly(sites, "https://news.gr/"))
        assertFalse(BrowserRules.htmlOnly(sites, ""))
        assertTrue(BrowserRules.webFont("https://fonts.gstatic.com/s/roboto/v30/a.woff2"))
        assertTrue(BrowserRules.webFont("https://x.gr/f.TTF?v=2"))
        assertFalse(BrowserRules.webFont("https://x.gr/style.css"))
    }

    @Test fun toolsOnTheBar() {
        assertEquals(BrowserTools.PHONE, BrowserTools.parse(null, wide = false))
        assertEquals(listOf("copy", "fill"), BrowserTools.parse("copy,nonsense,fill,copy", wide = false)) // unknown and twice dropped
        val bar = BrowserTools.PHONE
        assertNull(BrowserTools.toggle(bar, "player", wide = false)) // six on a phone: full
        assertEquals(listOf("fill", "ads", "private", "copy", "share"), BrowserTools.toggle(bar, "bookmarks", wide = false))
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
}
