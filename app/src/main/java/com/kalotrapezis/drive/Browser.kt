package com.kalotrapezis.drive

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

// Browser (the user, 2 Nov): logins, forms and cards kept encrypted; the history forgotten; Private keeps nothing;
// an ad blocker like Firefox's with a switch; Import from Firefox. The rules here are pure (BrowserTest); BrowserScreen.kt draws.

/**
 * EasyList's and EasyPrivacy's host rules, `||host…` and their `@@||host…` exceptions: almost all of both lists, and
 * what Firefox's own blocking is (whole hosts). A rule with an option it does not know (popup, redirect, csp…) is left out.
 */
internal class AdFilter(lines: Sequence<String>) {
    private class Rule(val path: String, val third: Boolean?, val on: List<String>, val notOn: List<String>)
    private val block = HashMap<String, MutableList<Rule>>()
    private val allow = HashMap<String, MutableList<Rule>>()
    val size: Int

    init { var n = 0; lines.forEach { if (add(it.trim())) n++ }; size = n }

    private fun add(line: String): Boolean {
        val exception = line.startsWith("@@||")
        if (!exception && !line.startsWith("||")) return false
        val body = line.removePrefix("@@").removePrefix("||").lowercase()
        val dollar = body.lastIndexOf('$')
        val pattern = if (dollar >= 0) body.substring(0, dollar) else body
        var third: Boolean? = null
        val on = mutableListOf<String>(); val notOn = mutableListOf<String>()
        if (dollar >= 0) for (o in body.substring(dollar + 1).split(',')) when {
            o == "third-party" || o == "3p" -> third = true
            o == "~third-party" || o == "1p" || o == "first-party" -> third = false
            o.startsWith("domain=") -> o.removePrefix("domain=").split('|').forEach { d -> if (d.startsWith("~")) notOn += d.drop(1) else on += d }
            o == "important" -> Unit
            // shortcut: a request's type is not known to shouldInterceptRequest, so a typed rule applies to every type; guess it from the Accept header if over-blocking shows
            o.removePrefix("~") in TYPES -> Unit
            else -> return false
        }
        val end = pattern.indexOfFirst { it in "^/*:|?" }
        val host = if (end < 0) pattern else pattern.substring(0, end)
        if (host.isEmpty() || '.' !in host) return false
        (if (exception) allow else block).getOrPut(host) { mutableListOf() } += Rule(if (end < 0) "" else pattern.substring(end), third, on, notOn)
        return true
    }

    /** Whether a request for [url], made by a page at [page] (its host), is an ad or a tracker. */
    fun blocks(url: String, page: String?): Boolean {
        val after = url.substringAfter("://", "").takeIf { it.isNotEmpty() } ?: return false
        val cut = after.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) after.length else it }
        val host = after.substring(0, cut).substringAfterLast('@').substringBefore(':').lowercase()
        val rest = after.substring(cut).lowercase()
        val third = page == null || BrowserRules.site(host) != BrowserRules.site(page)
        fun on(d: String) = page != null && (page == d || page.endsWith(".$d"))
        fun hit(rules: HashMap<String, MutableList<Rule>>): Boolean {
            var h = host
            while (true) {
                if (rules[h]?.any { r -> (r.third == null || r.third == third) && (r.on.isEmpty() || r.on.any(::on)) && r.notOn.none(::on) && glob(r.path, 0, rest, 0) } == true) return true
                h = h.substringAfter('.', "").takeIf { '.' in it } ?: return false
            }
        }
        return hit(block) && !hit(allow)
    }

    companion object {
        private val TYPES = setOf("script", "image", "stylesheet", "xmlhttprequest", "subdocument", "media", "font", "object", "ping", "websocket", "other", "document")
        private fun separator(c: Char) = !(c.isLetterOrDigit() || c in "_-.%")

        /** ABP's pattern after the host: `*` anything, `^` a separator or the end, a final `|` the end; otherwise a prefix is enough. */
        internal fun glob(p: String, i: Int, s: String, j: Int): Boolean {
            if (i == p.length) return true
            return when (val c = p[i]) {
                '*' -> (j..s.length).any { glob(p, i + 1, s, it) }
                '^' -> if (j == s.length) glob(p, i + 1, s, j) else separator(s[j]) && glob(p, i + 1, s, j + 1)
                '|' -> i == p.length - 1 && j == s.length
                else -> j < s.length && s[j] == c && glob(p, i + 1, s, j + 1)
            }
        }
    }
}

/** The lists: the copy that came with the app, then easylist.to's own each week (both GPLv3 / CC BY-SA 3.0, THIRD_PARTY_NOTICES.md). */
internal object AdBlock {
    @Volatile var filter: AdFilter? = null
    private val SOURCES = listOf("https://easylist.to/easylist/easylist.txt", "https://easylist.to/easylist/easyprivacy.txt")
    private const val WEEK = 7 * 24 * 3600_000L
    private fun prefs(c: Context) = c.getSharedPreferences("browser", Context.MODE_PRIVATE)
    fun on(c: Context) = prefs(c).getBoolean("adblock", true)
    fun set(c: Context, on: Boolean) = prefs(c).edit().putBoolean("adblock", on).apply()

    /** On a background thread: the newest lists this phone has, fetched again when a week old. */
    fun load(c: Context) {
        val files = SOURCES.indices.map { File(c.filesDir, "adblock$it.txt") }
        if (filter == null) runCatching {
            filter = AdFilter(if (files.all { it.exists() }) files.asSequence().flatMap { it.readLines() } else c.assets.open("adblock.txt").bufferedReader().lineSequence())
            WriterLog.i("Browser: ${filter?.size} ad-blocking rules")
        }.onFailure { WriterLog.e("Browser: the ad-blocking rules were not read", it) }
        if (files.all { System.currentTimeMillis() - it.lastModified() < WEEK }) return
        runCatching {
            val got = SOURCES.map { java.net.URL(it).openStream().use { s -> s.readBytes() } }
            require(got.all { it.size > 100_000 }) { "a list too short" }
            got.forEachIndexed { i, b -> File(c.filesDir, "adblock$i.part").apply { writeBytes(b); renameTo(files[i]) } }
            filter = AdFilter(files.asSequence().flatMap { it.readLines() })
            WriterLog.i("Browser: the ad-blocking lists updated, ${filter?.size} rules")
        }.onFailure { WriterLog.w("Browser: the ad-blocking lists not updated", it) }
    }
}

internal data class Visit(val url: String, val title: String, val at: Long)
// pinned: at the start page's front; folder: the user's own ("Radios", 2 Nov), "" for none
internal data class Bookmark(val url: String, val title: String, val pinned: Boolean = false, val folder: String = "")

internal object BrowserRules {
    /** The history lasts 24 hours from each visit (the user: "maybe keep it for 24 hours"), so yesterday's tabs are still found today. */
    const val KEEP = 24 * 3600_000L
    const val SEARCH = "https://duckduckgo.com/?q="

    /** Settings › Browser (the user, 2 Nov: "clear history on exit 24h or never"): "exit" lives only while the browser is open, "day" 24 hours, "never" until cleared. */
    const val ON_EXIT = "exit"; const val DAY = "day"; const val NEVER = "never"

    fun fresh(visits: List<Visit>, now: Long, keep: String = DAY) = if (keep == NEVER) visits else visits.filter { now - it.at in 0 until KEEP }

    /** The newest first, a page once (its latest visit), the old ones gone. */
    fun visit(visits: List<Visit>, v: Visit, keep: String = DAY) = fresh(listOf(v) + visits.filter { it.url != v.url }, v.at, keep).take(if (keep == NEVER) 5000 else 500)

    /** What the address bar goes to: a web address as it is, a bare name with a dot on https, anything else searched. */
    fun target(input: String): String {
        val t = input.trim()
        return when {
            Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(t) -> t
            Regex("^(localhost|[^\\s/:]+\\.[a-z]{2,}|\\d{1,3}(\\.\\d{1,3}){3})(:\\d+)?(/\\S*)?$", RegexOption.IGNORE_CASE).matches(t) -> "https://$t"
            else -> SEARCH + URLEncoder.encode(t, "UTF-8")
        }
    }

    /** HTML only (the user, 2 Nov: "no javascript … just html Css"): kept per site, so a site's subdomains and its pages follow. */
    fun htmlOnly(sites: Set<String>, url: String?) = host(url).takeIf { it.isNotEmpty() }?.let { site(it) in sites } == true

    /** A web font, which HTML only does without (the system's fonts instead). */
    fun webFont(url: String) = Regex("\\.(woff2?|ttf|otf|eot)([?#]|$)", RegexOption.IGNORE_CASE).containsMatchIn(url)

    /** Desktop mode (the user, 2 Nov): the WebView's own agent as desktop Chrome on Linux — no Android, no Mobile, no WebView marks. */
    /**
     * Mobile Chrome as it is, without WebView's marks ("; wv", "Version/4.0"): Google refuses to sign in inside an app's WebView
     * that says so (the user, 2 Nov: "it does not let me connect to Google").
     */
    fun mobileAgent(webView: String) = webView.replace("; wv)", ")").replace(Regex("Version/[\\d.]+ "), "")

    fun desktopAgent(mobile: String) = mobile.replace(Regex("\\([^)]*Android[^)]*\\)"), "(X11; Linux x86_64)").replace(" Mobile", "").replace(Regex("Version/[\\d.]+ "), "")

    /**
     * Player mode's ‹ › on a page with none of its own (a radio station's, live24.gr's): the next or the previous favourite of
     * the same site, round the list (the user, 2 Nov: "a favorite radios list with a heart so I am not pressing next on
     * thousands of stations"); in a page's folder, that folder's ("Radios"), whatever their sites; null when there is none but this page.
     */
    fun hop(bookmarks: List<Bookmark>, url: String, forward: Boolean): String? {
        val here = site(host(url))
        val folder = bookmarks.firstOrNull { it.url == url }?.folder.orEmpty()
        val list = if (folder.isNotEmpty()) bookmarks.filter { it.folder == folder }.map { it.url } else bookmarks.map { it.url }.filter { site(host(it)) == here }
        val others = list.filter { it != url }.ifEmpty { return null }
        val i = list.indexOf(url)
        if (i < 0) return if (forward) others.first() else others.last()
        return list[(i + (if (forward) 1 else -1) + list.size) % list.size]
    }

    fun host(url: String?) = url?.substringAfter("://", "")?.substringBefore('/')?.substringBefore('?')?.substringAfterLast('@')?.substringBefore(':')?.lowercase().orEmpty()

    /** shortcut: a site without the Public Suffix List — the last two labels, or three under com.gr, co.uk and their like; use the PSL if a site is split wrongly. */
    fun site(host: String): String {
        val p = host.split('.')
        val n = if (p.size >= 3 && p.last().length == 2 && p[p.size - 2] in SECOND) 3 else 2
        return p.takeLast(n).joinToString(".")
    }
    private val SECOND = setOf("com", "co", "org", "net", "gov", "edu", "ac", "or", "ne", "go", "gob")

    /** A saved login fits a page of the same host (www. or not); only the user's tap fills it. */
    fun sameHost(a: String, b: String) = a.removePrefix("www.") == b.removePrefix("www.")

    fun visitsJson(v: List<Visit>) = JSONArray(v.map { JSONObject().put("url", it.url).put("title", it.title).put("at", it.at) }).toString()
    fun visits(s: String?) = runCatching { JSONArray(s).let { a -> (0 until a.length()).map { a.getJSONObject(it).run { Visit(getString("url"), optString("title"), getLong("at")) } } } }.getOrDefault(emptyList())
    fun bookmarksJson(b: List<Bookmark>) = JSONArray(b.map { JSONObject().put("url", it.url).put("title", it.title).apply { if (it.pinned) put("pinned", true); if (it.folder.isNotEmpty()) put("folder", it.folder) } }).toString()
    fun bookmarks(s: String?) = runCatching { JSONArray(s).let { a -> (0 until a.length()).map { a.getJSONObject(it).run { Bookmark(getString("url"), optString("title"), optBoolean("pinned"), optString("folder")) } } } }.getOrDefault(emptyList())

    /** The folders' names, as first used. */
    fun folders(b: List<Bookmark>) = b.map { it.folder }.filter { it.isNotEmpty() }.distinct()

    /** The start page's order: the pinned at the front, then the newest first. */
    fun front(b: List<Bookmark>) = b.reversed().sortedByDescending { it.pinned }
}

internal data class SavedLogin(val host: String, val user: String, val password: String)
internal data class SavedCard(val name: String, val number: String, val month: String, val year: String) {
    val last4 get() = number.takeLast(4)
}

/** What the browser keeps encrypted (BrowserSecrets): passwords, addresses (autocomplete names → values) and cards; never the CVC. */
internal data class BrowserVault(val logins: List<SavedLogin> = emptyList(), val addresses: List<Map<String, String>> = emptyList(), val cards: List<SavedCard> = emptyList()) {
    /** Only what is new or changed: the same login, address or card again asks nothing. */
    fun news(o: BrowserVault) = BrowserVault(
        o.logins.filter { it !in logins }, o.addresses.filter { it !in addresses }, o.cards.filter { c -> cards.none { it.number == c.number && it.month == c.month && it.year == c.year } })

    /** A login replaces the same host and user's (an import may hold one twice, for http and https); a card the same number's. */
    fun with(o: BrowserVault) = BrowserVault(
        (logins + o.logins).associateBy { it.host to it.user }.values.toList(),
        (addresses + o.addresses).distinct(),
        (cards + o.cards).associateBy { it.number }.values.toList())

    val empty get() = logins.isEmpty() && addresses.isEmpty() && cards.isEmpty()

    fun json(): String = JSONObject()
        .put("logins", JSONArray(logins.map { JSONObject().put("host", it.host).put("user", it.user).put("password", it.password) }))
        .put("addresses", JSONArray(addresses.map { JSONObject(it) }))
        .put("cards", JSONArray(cards.map { JSONObject().put("name", it.name).put("number", it.number).put("month", it.month).put("year", it.year) }))
        .toString()

    companion object {
        val ADDRESS = setOf("name", "given-name", "additional-name", "family-name", "email", "tel", "organization", "street-address",
            "address-line1", "address-line2", "address-level1", "address-level2", "postal-code", "country", "country-name")

        fun of(s: String?): BrowserVault = runCatching {
            val o = JSONObject(s!!)
            fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map(a::getJSONObject) }.orEmpty()
            BrowserVault(
                list("logins").map { SavedLogin(it.getString("host"), it.optString("user"), it.getString("password")) },
                list("addresses").map { a -> a.keys().asSequence().associateWith { a.getString(it) } },
                list("cards").map { SavedCard(it.optString("name"), it.getString("number"), it.optString("month"), it.optString("year")) })
        }.getOrDefault(BrowserVault())

        /** A form the page sent (the injected script's {user, pass, fields}) as what could be kept for [host]. */
        fun fromForm(host: String, form: JSONObject): BrowserVault {
            val f = form.optJSONObject("fields")?.let { o -> o.keys().asSequence().associateWith { o.getString(it).trim() }.filterValues { it.isNotEmpty() } }.orEmpty()
            val pass = form.optString("pass")
            val login = if (pass.isNotEmpty() && host.isNotEmpty()) listOf(SavedLogin(host, form.optString("user").trim(), pass)) else emptyList()
            val address = f.filterKeys { it in ADDRESS }.takeIf { it.size >= 2 } // one field alone is a search or a newsletter, not an address
            val number = f["cc-number"]?.filter(Char::isDigit).orEmpty()
            val exp = f["cc-exp"]?.split('/', '-', ' ')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
            val card = if (number.length in 12..19) SavedCard(f["cc-name"].orEmpty(), number,
                (f["cc-exp-month"] ?: exp.getOrNull(0)).orEmpty().padStart(2, '0').takeLast(2), (f["cc-exp-year"] ?: exp.getOrNull(1)).orEmpty()) else null
            return BrowserVault(login, listOfNotNull(address), listOfNotNull(card))
        }
    }
}

/** The vault in the keystore's encryption (Mail's way); never synced. */
internal object BrowserSecrets : KeystoreSecrets("tetra_browser_key", "browser_secrets") {
    fun vault(c: Context) = BrowserVault.of(get(c, "vault"))
    fun save(c: Context, v: BrowserVault) = set(c, "vault", if (v.empty) null else v.json())
}

/**
 * Import from Firefox: what desktop Firefox exports (Firefox on Android exports nothing) — Passwords › Export (logins.csv),
 * and Bookmarks › Export to HTML or Backup (.json).
 */
internal object FirefoxImport {
    data class Result(val logins: List<SavedLogin>, val bookmarks: List<Bookmark>)

    fun read(text: String): Result? {
        val t = text.trimStart('﻿', ' ', '\n', '\r', '\t')
        return when {
            t.startsWith("<!DOCTYPE NETSCAPE-Bookmark", true) -> Result(emptyList(), bookmarksHtml(t))
            t.startsWith("{") -> runCatching { Result(emptyList(), bookmarksJson(t)) }.getOrNull()
            else -> logins(t)
        }
    }

    /** Firefox's logins.csv: "url","username","password",… with a header; a line break may be inside quotes. */
    fun logins(text: String): Result? {
        val rows = csv(text)
        val head = rows.firstOrNull()?.map { it.trim().lowercase() } ?: return null
        val u = head.indexOf("url"); val n = head.indexOf("username"); val p = head.indexOf("password")
        if (u < 0 || p < 0) return null
        val logins = rows.drop(1).mapNotNull { r ->
            val host = BrowserRules.host(r.getOrNull(u))
            val pass = r.getOrNull(p).orEmpty()
            if (host.isEmpty() || pass.isEmpty()) null else SavedLogin(host, r.getOrNull(n).orEmpty(), pass)
        }
        return Result(logins, emptyList())
    }

    fun csv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>(); val cell = StringBuilder(); var quoted = false; var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> { row += cell.toString(); cell.clear() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                    row += cell.toString(); cell.clear()
                    if (row.any { it.isNotEmpty() }) rows += row
                    row = mutableListOf()
                }
                else -> cell.append(c)
            }
            i++
        }
        row += cell.toString()
        if (row.any { it.isNotEmpty() }) rows += row
        return rows
    }

    fun bookmarksHtml(html: String) = Regex("<A\\s[^>]*HREF=\"([^\"]+)\"[^>]*>(.*?)</A>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(html)
        .map { Bookmark(entities(it.groupValues[1]), entities(it.groupValues[2]).trim()) }.filter { it.url.startsWith("http", true) }.toList()

    /** Firefox's backup: folders of children, each bookmark a "text/x-moz-place" with its uri. */
    fun bookmarksJson(json: String): List<Bookmark> {
        val out = mutableListOf<Bookmark>()
        fun walk(o: JSONObject) {
            o.optString("uri").takeIf { it.startsWith("http", true) }?.let { out += Bookmark(it, o.optString("title")) }
            o.optJSONArray("children")?.let { a -> for (i in 0 until a.length()) walk(a.getJSONObject(i)) }
        }
        walk(JSONObject(json))
        return out
    }

    private fun entities(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
}

/**
 * Player mode and background play (the user, 2 Nov: "play youtube videos on the background", "like Youtube music"): any page
 * that plays media works through its video/audio and its own navigator.mediaSession keys; a site here only when it needs its
 * own buttons, or for AI Assist's search-and-play. A new site is one line.
 */
internal data class MediaSite(val id: String, val hosts: Set<String>, val next: String = "", val prev: String = "", val search: String = "", val first: String = "")

internal object MediaSites {
    // shortcut: these selectors are YouTube's and YouTube Music's own markup as of Nov 2026, untried on a phone; mend them when a click misses
    val ALL = listOf(
        MediaSite("youtube", setOf("youtube.com", "m.youtube.com", "www.youtube.com"), ".ytp-next-button, .player-controls-next-button, ytm-playlist-panel-renderer [aria-selected=true] + * a",
            ".ytp-prev-button, .player-controls-previous-button", "https://m.youtube.com/results?search_query=", "a[href*=\"/watch?v=\"]"),
        MediaSite("youtube_music", setOf("music.youtube.com"), ".next-button", ".previous-button", "https://music.youtube.com/search?q=", "a[href*=\"watch?v=\"]"),
    )

    fun of(host: String) = ALL.firstOrNull { host.lowercase() in it.hosts }
    fun byId(id: String?) = ALL.firstOrNull { it.id == id } ?: ALL.first() // plain YouTube is the default

    /** AI Assist's search on [site]: its results page, the first result played from there. */
    fun searchUrl(site: MediaSite, query: String) = site.search + URLEncoder.encode(query.trim(), "UTF-8")

    /** A playlist or a queue in the address (YouTube's `list=`; a radio mix too): Player mode is offered, once per list. */
    fun listId(url: String?) = url?.let { Regex("[?&]list=([^&#]+)").find(it)?.groupValues?.get(1) }

    /** The site's own buttons for [site], for the page script (empty: the page's media keys alone). */
    fun buttons(site: MediaSite?) = JSONObject().put("next", site?.next.orEmpty()).put("prev", site?.prev.orEmpty()).put("first", site?.first.orEmpty()).toString()
}

/**
 * Which tools are on the bar and which in ☰'s sheet (the user, 2 Nov: "by long press you can edit what button is in the menu
 * or on the bar"); one list for a phone, one for a tablet. Order is the order chosen.
 */
internal object BrowserTools {
    val ALL = listOf("fill", "ads", "private", "desktop", "html", "bookmarks", "history", "saved", "copy", "share", "split", "forward", "player", "import", "autohide", "background", "tabs_run", "close")
    val PHONE = listOf("fill", "ads", "private", "bookmarks", "copy", "share") // two columns of three small (the user, 2 Nov: "fit 2 more tools")
    val TABLET = listOf("fill", "ads", "private", "desktop", "html", "bookmarks", "history", "saved", "copy", "share", "split")
    fun max(wide: Boolean) = if (wide) 11 else 6

    fun parse(saved: String?, wide: Boolean) = saved?.split(',')?.filter { it in ALL }?.distinct()?.take(max(wide)) ?: if (wide) TABLET else PHONE

    /** A tool tapped in edit mode: off the bar, or onto its end; null when the bar is full. */
    fun toggle(bar: List<String>, id: String, wide: Boolean): List<String>? = when {
        id in bar -> bar - id
        bar.size >= max(wide) -> null
        else -> bar + id
    }
}

/** A tab as the tab row needs it: pinned ones are circles at the start; a group is one chip, its tabs after it while unfolded. */
internal data class TabInfo(val id: Int, val pinned: Boolean, val group: Int?)

internal sealed class TabRowItem {
    data class Tab(val id: Int, val pinned: Boolean, val grouped: Boolean) : TabRowItem()
    data class Group(val id: Int, val count: Int, val open: Boolean) : TabRowItem()
}

internal object BrowserTabRow {
    /** The row in order (the user, 2 Nov: "pinned tabs, just a circle"; "tab groups … you click the group tab and it unfolds"). */
    fun items(tabs: List<TabInfo>, open: Set<Int>): List<TabRowItem> {
        val out = tabs.filter { it.pinned }.map<TabInfo, TabRowItem> { TabRowItem.Tab(it.id, true, false) }.toMutableList()
        val seen = mutableSetOf<Int>()
        for (t in tabs.filter { !it.pinned }) {
            val g = t.group
            if (g == null) { out += TabRowItem.Tab(t.id, false, false); continue }
            if (!seen.add(g)) continue
            val members = tabs.filter { !it.pinned && it.group == g }
            out += TabRowItem.Group(g, members.size, g in open)
            if (g in open) members.forEach { out += TabRowItem.Tab(it.id, false, true) }
        }
        return out
    }
}

/**
 * A tab kept from one time to the next: pinned and grouped ones always (the user, 2 Nov: "groups and pinned tabs keep them");
 * the others as long as the history is ("gone according to the setting: end of session, 24 h, never"), [at] when last kept.
 * Never Private's.
 */
internal data class SavedTab(val url: String, val title: String, val pinned: Boolean, val group: Int?, val at: Long = 0)

internal object SavedTabs {
    fun json(tabs: List<SavedTab>, groups: Map<Int, String>, now: Long = System.currentTimeMillis()): String = JSONObject()
        .put("tabs", JSONArray(tabs.filter { it.url.isNotEmpty() }.map {
            JSONObject().put("url", it.url).put("title", it.title).put("pinned", it.pinned).put("at", now).apply { it.group?.let { g -> put("group", g) } }
        }))
        .put("groups", JSONObject(groups.mapKeys { it.key.toString() }))
        .toString()

    /** The kept tabs [keep] lets back (BrowserRules' ON_EXIT, DAY, NEVER) and the names of the groups they are in (a group left with no tab is dropped). */
    fun read(s: String?, keep: String = BrowserRules.ON_EXIT, now: Long = System.currentTimeMillis()): Pair<List<SavedTab>, Map<Int, String>> = runCatching {
        val o = JSONObject(s!!)
        val a = o.getJSONArray("tabs")
        val tabs = (0 until a.length()).map { a.getJSONObject(it).run { SavedTab(getString("url"), optString("title"), optBoolean("pinned"), if (has("group")) getInt("group") else null, optLong("at")) } }
            .filter { it.pinned || it.group != null || keep == BrowserRules.NEVER || (keep == BrowserRules.DAY && now - it.at in 0 until BrowserRules.KEEP) }
        val g = o.optJSONObject("groups")
        val names = g?.keys()?.asSequence()?.associate { it.toInt() to g.getString(it) }.orEmpty().filterKeys { k -> tabs.any { it.group == k } }
        tabs to names
    }.getOrDefault(emptyList<SavedTab>() to emptyMap())
}
