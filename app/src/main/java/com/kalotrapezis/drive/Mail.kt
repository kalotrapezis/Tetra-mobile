package com.kalotrapezis.drive

import android.content.Context
import java.io.File
import java.nio.charset.Charset
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tetra Mail on the phone and tablet, step 2 of MAIL_PLAN.md: read only. The computer fetches the mail (its desktop/mail.js,
 * step 1) and sync brings its archive here, so everything below only reads what it wrote:
 *
 *   Tetra/.System/Mail/Shared/accounts.json        names, addresses, colours; no secrets
 *   Tetra/.System/Mail/Shared/index.jsonl          one line per mail; a later line with the same id replaces it
 *   Tetra/.System/Mail/Shared/<address>/<Folder>/<yyyy>/<yyyy-mm-dd hhmm> <from> - <subject>.eml
 *   … /<same name>.files/                          its attachments, as ordinary files
 *
 * <Folder> is the role (Inbox, Drafts, Sent, Archive, Spam, Trash), the same for every account and language. A mail is
 * green (`saved`) once its .eml holds what the server has, orange until then; a `gone` one left the server and is not listed.
 */
internal data class MailEntry(
    val id: String, val account: String, val folder: String, val date: Long, val from: String, val fromAddress: String,
    val to: List<String>, val subject: String, val seen: Boolean, val flagged: Boolean, val size: Long, val attachments: Int,
    val snippet: String, val saved: Boolean, val file: String?, val gone: Boolean,
)

/** An account as the computer describes it; [shadow]: the phone does not fetch it itself, it comes through the PC (MAIL_PLAN §1). */
internal data class MailAccount(val id: String, val address: String, val name: String, val color: Long, val addedOn: String) {
    // shortcut: the phone fetches no account itself yet (step 2), so every account is the PC's; upgrade when it signs in on its own
    val shadow get() = addedOn != "phone"
}

/** A folder's counts for one account: unread, all, and how many are only on the server (orange). */
internal data class MailCount(val unread: Int, val total: Int, val orange: Int)

internal object MailFiles {
    const val FOLDER = "Mail"
    val ROLES = listOf("Inbox", "Drafts", "Sent", "Archive", "Spam", "Trash")

    fun root(tetra: File) = File(tetra, TetraSystem.onDisk(FOLDER))

    fun accounts(root: File): List<MailAccount> = runCatching {
        val a = JSONArray(File(root, "accounts.json").readText())
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            MailAccount(o.getString("id"), o.optString("address", o.getString("id")), o.optString("name", o.getString("id")),
                color(o.optString("color")), o.optString("addedOn", "desktop"))
        }
    }.getOrDefault(emptyList())

    private fun color(hex: String) = hex.removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)?.let { 0xFF000000 or it } ?: 0xFF9E9E9E

    private var cached: Triple<Long, Long, List<MailEntry>>? = null
    /** Every mail not gone; the last line for an id wins. Read again only when the file's size or time changed (it may be long). */
    fun index(root: File): List<MailEntry> = synchronized(this) {
        val f = File(root, "index.jsonl")
        cached?.takeIf { it.first == f.length() && it.second == f.lastModified() }?.let { return it.third }
        val read = runCatching { f.useLines { parse(it) } }.getOrDefault(emptyList())
        cached = Triple(f.length(), f.lastModified(), read)
        read
    }

    fun parse(lines: Sequence<String>): List<MailEntry> {
        val byId = LinkedHashMap<String, MailEntry>()
        for (line in lines) {
            if (line.isBlank()) continue
            val e = runCatching { entry(JSONObject(line)) }.getOrNull() ?: continue // a line cut by a crash on the computer
            byId[e.id] = e
        }
        return byId.values.filter { !it.gone }
    }

    private fun entry(o: JSONObject) = MailEntry(
        id = o.getString("id"), account = o.getString("account"), folder = o.getString("folder"), date = o.optLong("date"),
        from = o.optString("from"), fromAddress = o.optString("fromAddress"),
        to = o.optJSONArray("to")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
        subject = o.optString("subject"), seen = o.optBoolean("seen"), flagged = o.optBoolean("flagged"), size = o.optLong("size"),
        attachments = o.optInt("attachments"), snippet = o.optString("snippet"), saved = o.optBoolean("saved"),
        file = o.optString("file").ifEmpty { null }, gone = o.optBoolean("gone"),
    )

    /** Role → account → counts, for the unified folders (MAIL_PLAN §4); only the accounts in accounts.json. */
    fun counts(mails: List<MailEntry>, accounts: List<MailAccount>): Map<String, Map<String, MailCount>> {
        val ids = accounts.map { it.id }.toSet()
        return ROLES.associateWith { role ->
            val here = mails.filter { it.folder == role && it.account in ids }.groupBy { it.account }
            accounts.associate { a -> a.id to (here[a.id].orEmpty().let { m -> MailCount(m.count { !it.seen }, m.size, m.count { !it.saved }) }) }
        }
    }

    /** The mail's file and its attachments' folder, or null when it is not on this device yet. */
    fun eml(root: File, e: MailEntry) = e.file?.let { File(root, it) }?.takeIf { it.isFile && inside(root, it) }
    fun attachments(root: File, e: MailEntry): List<File> = eml(root, e)?.let { File(it.path.removeSuffix(".eml") + ".files") }
        ?.listFiles()?.filter { it.isFile }?.sortedBy { it.name.lowercase() }.orEmpty()
    private fun inside(root: File, f: File) = f.canonicalPath.startsWith(root.canonicalPath + File.separator) // never a path out of the archive
}

/** The Filter modal's choices (MAIL_PLAN §9), the computer's mailView.ts: quick filters on top, sorting and grouping below. */
internal data class MailView(
    val unread: Boolean = false, val favorites: Boolean = false, val attachments: Boolean = false, val orange: Boolean = false,
    val accounts: Set<String> = emptySet(), val sort: String = "date", val descending: Boolean = true, val group: String = "day",
    val unreadFirst: Boolean = false, val favoritesFirst: Boolean = false, val attachmentsFirst: Boolean = false,
) {
    fun json(): String = JSONObject().put("unread", unread).put("favorites", favorites).put("attachments", attachments).put("orange", orange)
        .put("accounts", JSONArray(accounts.toList())).put("sort", sort).put("descending", descending).put("group", group)
        .put("unreadFirst", unreadFirst).put("favoritesFirst", favoritesFirst).put("attachmentsFirst", attachmentsFirst).toString()

    companion object {
        val SORTS = listOf("date", "from", "subject", "size", "account")
        val GROUPS = listOf("day", "from", "account", "none")
        fun of(json: String?): MailView = json?.let { s -> runCatching { JSONObject(s).let { o -> MailView(o.optBoolean("unread"), o.optBoolean("favorites"),
            o.optBoolean("attachments"), o.optBoolean("orange"), o.optJSONArray("accounts")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty(),
            o.optString("sort", "date").takeIf { it in SORTS } ?: "date", o.optBoolean("descending", true), o.optString("group", "day").takeIf { it in GROUPS } ?: "day",
            o.optBoolean("unreadFirst"), o.optBoolean("favoritesFirst"), o.optBoolean("attachmentsFirst")) } }.getOrNull() } ?: MailView()

        /** "Re: Fwd: Θέμα" sorts as "Θέμα". */
        private val PREFIX = Regex("^\\s*((re|fwd?|aw|απ|σχετ|προωθ)\\s*:\\s*)+", RegexOption.IGNORE_CASE)
        private fun bare(s: String) = s.replace(PREFIX, "").lowercase()
        fun dayKey(ms: Long): String = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
    }

    /** The mails this view keeps, sorted, in groups (in the order their first mail comes). */
    fun arrange(mails: List<MailEntry>, query: String = ""): List<Pair<String, List<MailEntry>>> {
        val q = query.trim().lowercase()
        val kept = mails.filter { m -> (!unread || !m.seen) && (!favorites || m.flagged) && (!attachments || m.attachments > 0) && (!orange || !m.saved)
            && (accounts.isEmpty() || m.account in accounts) && (q.isEmpty() || "${m.from} ${m.fromAddress} ${m.subject} ${m.snippet}".lowercase().contains(q)) }
        val key: Comparator<MailEntry> = when (sort) {
            "from" -> compareBy { it.from.ifEmpty { it.fromAddress }.lowercase() }
            "subject" -> compareBy { bare(it.subject) }
            "size" -> compareBy { it.size }
            "account" -> compareBy { it.account }
            else -> compareBy { it.date }
        }
        var order = compareBy<MailEntry> { if (unreadFirst) it.seen else false }
            .thenBy { if (favoritesFirst) !it.flagged else false }
            .thenBy { if (attachmentsFirst) it.attachments == 0 else false }
            .then(if (descending) key.reversed() else key)
        order = order.thenByDescending { it.date }
        val group: (MailEntry) -> String = when (group) {
            "from" -> { m -> m.from.ifEmpty { m.fromAddress } }
            "account" -> { m -> m.account }
            "none" -> { _ -> "" }
            else -> { m -> dayKey(m.date) }
        }
        return kept.sortedWith(order).groupBy(group).toList()
    }
}

/** Settings › Αλληλογραφία's switches (MAIL_PLAN §9c) and the Filter modal's views kept per folder; this device's own. */
internal object MailPrefs {
    private fun p(c: Context) = c.getSharedPreferences("mail", Context.MODE_PRIVATE)
    fun remote(c: Context) = p(c).getBoolean("remote", false)
    fun setRemote(c: Context, on: Boolean) = p(c).edit().putBoolean("remote", on).apply()
    fun allowed(c: Context): Set<String> = p(c).getStringSet("allowed", emptySet())!!
    fun allow(c: Context, sender: String, on: Boolean = true) = p(c).edit().putStringSet("allowed", if (on) allowed(c) + sender.lowercase() else allowed(c) - sender).apply()
    fun switch(c: Context, name: String) = p(c).getBoolean(name, true) // tracking, links, risky, realAddress: on by default
    fun setSwitch(c: Context, name: String, on: Boolean) = p(c).edit().putBoolean(name, on).apply()
    fun receipts(c: Context) = p(c).getInt("receipts", 1) // 0 never, 1 ask, 2 always
    fun setReceipts(c: Context, v: Int) = p(c).edit().putInt("receipts", v).apply()
    /** A folder's kept view ("Κράτησε για αυτόν τον φάκελο"), or null. */
    fun view(c: Context, folder: String) = p(c).getString("view.$folder", null)?.let(MailView::of)
    fun setView(c: Context, folder: String, v: MailView?) = p(c).edit().apply { if (v == null) remove("view.$folder") else putString("view.$folder", v.json()) }.apply()
}

/**
 * Enough of MIME (RFC 2045–2047) to show a mail the computer saved: its HTML or text, the pictures inside it, To and Cc.
 * Its attachments are not decoded here: the computer wrote them beside it as ordinary files.
 */
internal object Mime {
    class Message(val html: String?, val text: String?, val to: String, val cc: String, val headers: Map<String, String>)

    private class Part(val headers: Map<String, String>, val body: ByteArray) {
        val type = headers["content-type"].orEmpty().substringBefore(';').trim().lowercase().ifEmpty { "text/plain" }
        fun param(name: String) = Mime.param(headers["content-type"].orEmpty(), name)
        val attachment get() = headers["content-disposition"].orEmpty().trim().lowercase().startsWith("attachment")
        fun bytes(): ByteArray = when (headers["content-transfer-encoding"].orEmpty().trim().lowercase()) {
            "base64" -> runCatching { java.util.Base64.getMimeDecoder().decode(body) }.getOrDefault(ByteArray(0))
            "quoted-printable" -> quoted(body, false)
            else -> body
        }
        fun text() = String(bytes(), charset(param("charset")))
    }

    fun read(raw: ByteArray): Message {
        val top = part(raw)
        val leaves = mutableListOf<Part>()
        fun walk(p: Part) { if (p.type.startsWith("multipart/")) p.param("boundary")?.let { b -> split(p.body, b).forEach { walk(part(it)) } } else leaves += p }
        walk(top)
        val html = leaves.firstOrNull { it.type == "text/html" && !it.attachment }?.text()?.let { h ->
            // pictures inside the mail (cid:) as data: URLs, so nothing is fetched for them
            leaves.filter { it.type.startsWith("image/") && it.headers["content-id"] != null }.fold(h) { acc, img ->
                val cid = img.headers["content-id"]!!.trim().removePrefix("<").removeSuffix(">")
                acc.replace("cid:$cid", "data:${img.type};base64,${java.util.Base64.getEncoder().encodeToString(img.bytes())}")
            }
        }
        val text = leaves.firstOrNull { it.type == "text/plain" && !it.attachment }?.text()
        return Message(html, text, words(top.headers["to"].orEmpty()), words(top.headers["cc"].orEmpty()), top.headers)
    }

    /** Headers (unfolded, names in lower case, the first of each) and the body after the empty line. */
    private fun part(raw: ByteArray): Part {
        val s = String(raw, Charsets.ISO_8859_1) // one char per byte: cut here, the body's bytes stay as they were
        val end = Regex("\r?\n\r?\n").find(s)
        val head = if (end == null) s else s.substring(0, end.range.first)
        val body = if (end == null) ByteArray(0) else s.substring(end.range.last + 1).toByteArray(Charsets.ISO_8859_1)
        val headers = LinkedHashMap<String, String>()
        String(head.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8).replace(Regex("\r?\n[ \t]+"), " ").lines().forEach { line ->
            val i = line.indexOf(':')
            if (i > 0) headers.putIfAbsent(line.substring(0, i).trim().lowercase(), line.substring(i + 1).trim())
        }
        return Part(headers, body)
    }

    private fun split(body: ByteArray, boundary: String): List<ByteArray> {
        val s = String(body, Charsets.ISO_8859_1)
        val parts = s.split(Regex("(^|\r?\n)--" + Regex.escape(boundary) + "(--)?[ \t]*(?=\r?\n|$)"))
        val closed = s.contains("--$boundary--") // then the last piece is the epilogue after it
        return parts.drop(1).let { if (closed) it.dropLast(1) else it }.map { it.removePrefix("\r\n").removePrefix("\n").toByteArray(Charsets.ISO_8859_1) }
    }

    fun param(header: String, name: String): String? = Regex(";\\s*" + Regex.escape(name) + "\\s*=\\s*(\"([^\"]*)\"|[^;\\s]+)", RegexOption.IGNORE_CASE)
        .find(header)?.let { m -> m.groups[2]?.value ?: m.groupValues[1] }

    private fun charset(name: String?): Charset = name?.let { runCatching { Charset.forName(it.trim()) }.getOrNull() } ?: Charsets.UTF_8

    /** Quoted-printable; [header]: the Q of encoded words, where _ is a space. */
    fun quoted(b: ByteArray, header: Boolean): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            val c = b[i].toInt().toChar()
            when {
                c == '=' && i + 1 < b.size && (b[i + 1] == '\n'.code.toByte()) -> i += 2
                c == '=' && i + 2 < b.size && b[i + 1] == '\r'.code.toByte() && b[i + 2] == '\n'.code.toByte() -> i += 3
                c == '=' && i + 2 < b.size && "${b[i + 1].toInt().toChar()}${b[i + 2].toInt().toChar()}".toIntOrNull(16) != null -> {
                    out.write("${b[i + 1].toInt().toChar()}${b[i + 2].toInt().toChar()}".toInt(16)); i += 3
                }
                header && c == '_' -> { out.write(' '.code); i++ }
                else -> { out.write(b[i].toInt()); i++ }
            }
        }
        return out.toByteArray()
    }

    private val WORD = Regex("=\\?([^?]+)\\?([bBqQ])\\?([^?]*)\\?=")
    /** RFC 2047's encoded words (=?utf-8?B?…?=) in a header, the space between two of them dropped. */
    fun words(s: String): String = WORD.replace(s.replace(Regex("(\\?=)\\s+(=\\?)"), "$1$2")) { m ->
        val bytes = if (m.groupValues[2].equals("b", true)) runCatching { java.util.Base64.getDecoder().decode(m.groupValues[3]) }.getOrDefault(ByteArray(0))
            else quoted(m.groupValues[3].toByteArray(Charsets.ISO_8859_1), true)
        String(bytes, charset(m.groupValues[1].substringBefore('*')))
    }

    /** Text as HTML for the mail's page: escaped, its lines kept. */
    fun textAsHtml(t: String) = "<pre style=\"white-space:pre-wrap;font-family:sans-serif\">" +
        t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</pre>"

    /** Whether the HTML would load anything from the web (pictures, fonts, styles). */
    fun remote(html: String) = Regex("(src|background)\\s*=\\s*[\"']?\\s*(https?:)?//|url\\(\\s*[\"']?\\s*(https?:)?//|<link\\b", RegexOption.IGNORE_CASE).containsMatchIn(html)

    /** Tracking pixels (a picture 0 or 1 pixel wide or high) taken out, for when pictures load. */
    fun withoutPixels(html: String) = html.replace(Regex("<img\\b[^>]*\\b(width|height)\\s*=\\s*[\"']?[01](px)?[\"'\\s/>][^>]*>", RegexOption.IGNORE_CASE), "")

    /** Attachments Tetra warns about before opening (MAIL_PLAN §9c): programs, scripts, Office files with macros. */
    fun risky(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("exe", "msi", "bat", "cmd", "com", "scr", "js", "jse", "vbs", "vbe",
        "ps1", "jar", "apk", "lnk", "hta", "docm", "xlsm", "pptm", "dotm", "xltm")
}
