package com.kalotrapezis.drive

import android.content.Context
import java.io.File
import java.nio.charset.Charset
import java.time.LocalDate
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
    val snippet: String, val saved: Boolean, val file: String?, val gone: Boolean, val messageId: String? = null,
)

/**
 * An account as accounts.json (the computer's) or accounts.<device>.json (a phone's or tablet's own) describes it.
 * [own]: this device fetches it itself (its password is in this device's keystore); otherwise it is a shadow account,
 * its mail through the PC (MAIL_PLAN §1). [json] is the account as written, kept for the engine (server, smtp).
 */
internal data class MailAccount(val id: String, val address: String, val name: String, val color: Long, val addedOn: String,
    val own: Boolean = false, val json: JSONObject? = null) {
    val shadow get() = !own
}

/** A folder's counts for one account: unread, all, and how many are only on the server (orange). */
internal data class MailCount(val unread: Int, val total: Int, val orange: Int)

internal object MailFiles {
    const val FOLDER = "Mail"
    val ROLES = listOf("Inbox", "Drafts", "Sent", "Archive", "Spam", "Trash")

    fun root(tetra: File) = File(tetra, TetraSystem.onDisk(FOLDER))

    /** The computer's accounts.json, then each device's accounts.<device>.json; one account once, [own] those this device fetches. */
    fun accounts(root: File, own: Set<String> = emptySet()): List<MailAccount> {
        val files = listOf(File(root, "accounts.json")) + (root.listFiles { f -> f.name.startsWith("accounts.") && f.name.endsWith(".json") && f.name != "accounts.json" }.orEmpty().sortedBy { it.name })
        val byId = LinkedHashMap<String, MailAccount>()
        for (f in files) runCatching {
            val a = JSONArray(f.readText())
            for (o in (0 until a.length()).map { a.getJSONObject(it) }) {
                val id = o.getString("id")
                if (id in byId && id !in own) continue // the computer's description first; this device's own when it fetches it
                byId[id] = MailAccount(id, o.optString("address", id), o.optString("name", id), color(o.optString("color")), o.optString("addedOn", "desktop"), id in own, o)
            }
        }
        return byId.values.toList()
    }

    private fun color(hex: String) = hex.removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)?.let { 0xFF000000 or it } ?: 0xFF9E9E9E

    private var cached: Pair<String, List<MailEntry>>? = null
    /**
     * Every mail not gone: the computer's index.jsonl, then each device's index.<device>.jsonl (the mail a phone fetched
     * itself), this device's last; the last line for an id wins, and an id is the same mail whoever fetched it. Read
     * again only when a file's size or time changed (they may be long).
     */
    fun index(root: File, own: String? = null): List<MailEntry> = synchronized(this) {
        val others = root.listFiles { f -> f.name.startsWith("index.") && f.name.endsWith(".jsonl") && f.name != "index.jsonl" }.orEmpty().sortedBy { it.name }
        val files = listOf(File(root, "index.jsonl")) + others.sortedBy { it.name == "index.$own.jsonl" }
        val stamp = files.joinToString { "${it.name}:${it.length()}:${it.lastModified()}" }
        cached?.takeIf { it.first == stamp }?.let { return it.second }
        val read = parse(files.asSequence().flatMap { f -> runCatching { f.readLines() }.getOrDefault(emptyList()) })
        cached = stamp to read
        read
    }

    fun parse(lines: Sequence<String>, keepGone: Boolean = false): List<MailEntry> {
        val byId = LinkedHashMap<String, MailEntry>()
        for (line in lines) {
            if (line.isBlank()) continue
            val e = runCatching { entry(JSONObject(line)) }.getOrNull() ?: continue // a line cut by a crash on the computer
            val before = byId[e.id]
            // a device that has only the headers does not hide the body another one saved
            byId[e.id] = if (e.file == null && !e.gone && before?.file != null) e.copy(file = before.file, saved = before.saved, attachments = before.attachments, snippet = before.snippet) else e
        }
        return byId.values.filter { keepGone || !it.gone }
    }

    fun json(e: MailEntry): JSONObject = JSONObject().put("id", e.id).put("account", e.account).put("folder", e.folder).put("date", e.date)
        .put("from", e.from).put("fromAddress", e.fromAddress).put("to", JSONArray(e.to)).put("subject", e.subject).put("seen", e.seen)
        .put("flagged", e.flagged).put("size", e.size).put("attachments", e.attachments).put("snippet", e.snippet).put("saved", e.saved)
        .put("file", e.file ?: JSONObject.NULL).put("gone", e.gone).apply { e.messageId?.let { put("messageId", it) } }

    private fun entry(o: JSONObject) = MailEntry(
        id = o.getString("id"), account = o.getString("account"), folder = o.getString("folder"), date = o.optLong("date"),
        from = o.optString("from"), fromAddress = o.optString("fromAddress"),
        to = o.optJSONArray("to")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
        subject = o.optString("subject"), seen = o.optBoolean("seen"), flagged = o.optBoolean("flagged"), size = o.optLong("size"),
        attachments = o.optInt("attachments"), snippet = o.optString("snippet"), saved = o.optBoolean("saved"),
        file = o.optString("file").takeIf { it.isNotEmpty() && !o.isNull("file") }, gone = o.optBoolean("gone"),
        messageId = o.optString("messageId").ifEmpty { null },
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

/**
 * What of the Mail part this device keeps (MAIL_PLAN §2: the index always, recent mail, older on demand): a body for
 * [bodies] months, an attachment for [attachments] (-1 all, 0 none), by the date in its path ("yyyy-mm-dd hhmm …"). The
 * computer offers every Mail file each sync; the rest comes by [SyncClient.mailFile] when opened. Requests are this
 * device's own to send, never taken back.
 */
internal object MailSync {
    const val PART = "Mail/"
    const val REQUESTS = "Mail/requests/"
    private val DATED = Regex("(?:^|/)(\\d{4})-(\\d{2})-(\\d{2}) \\d{4} ")

    fun keep(rel: String, today: LocalDate, bodies: Int, attachments: Int): Boolean {
        if (!rel.startsWith(PART)) return true
        if (rel.startsWith(REQUESTS)) return false
        val m = DATED.find(rel) ?: return true // index.jsonl, accounts.json, a device's own index and accounts
        val date = runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull() ?: return true
        return recent(date, today, if (".files/" in rel) attachments else bodies)
    }

    fun recent(date: LocalDate, today: LocalDate, months: Int) = months < 0 || (months > 0 && !date.isBefore(today.minusMonths(months.toLong())))

    fun keep(c: Context, rel: String) = keep(rel, LocalDate.now(), MailPrefs.bodies(c), MailPrefs.attachments(c))

    /** A Mail file this device may let go of: older than it keeps, and not opened here in the last week. */
    fun drop(c: Context, rel: String, file: File) = rel.startsWith(PART) && !rel.startsWith(REQUESTS) && !keep(c, rel) &&
        file.lastModified() < System.currentTimeMillis() - 7 * 86_400_000L
}

/**
 * The phone's changes, carried out by the computer on the server (step 1's format, MAIL_PLAN §11): one file per action,
 * Mail/requests/<device>-<ms>.json = { op, ids, to (a move's role), at, device }. Sync takes it there and drops it here.
 */
internal object MailRequests {
    val OPS = setOf("read", "unread", "favorite", "unfavorite", "archive", "delete", "move", "spam", "notSpam")

    fun write(root: File, device: String, op: String, ids: Collection<String>, to: String? = null, now: Long = System.currentTimeMillis()): File {
        require(op in OPS && ids.isNotEmpty() && (op != "move" || to in MailFiles.ROLES))
        val dir = File(root, "requests").apply { mkdirs() }
        var at = now
        while (File(dir, "$device-$at.json").exists()) at++
        val json = JSONObject().put("op", op).put("ids", JSONArray(ids.toList())).put("at", at).put("device", device).apply { if (op == "move") put("to", to) }
        val part = File(dir, ".$device-$at.json.part").apply { writeText(json.toString()) } // a dot file: sync never takes it half written
        return File(dir, "$device-$at.json").also { check(part.renameTo(it)) }
    }

    /** The mail as it will be once the computer has done [op]; null: it leaves this folder. */
    fun applied(e: MailEntry, op: String): MailEntry? = when (op) {
        "read" -> e.copy(seen = true); "unread" -> e.copy(seen = false)
        "favorite" -> e.copy(flagged = true); "unfavorite" -> e.copy(flagged = false)
        else -> null
    }
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
    /** The signature for [address] (Settings › Αλληλογραφία › Υπογραφές), plain text, added to new mail and replies. */
    fun signature(c: Context, address: String) = p(c).getString("signature.${address.lowercase()}", "")!!
    fun setSignature(c: Context, address: String, text: String) = p(c).edit().putString("signature.${address.lowercase()}", text).apply()
    /** An account's trouble on this device's last look (auth, appPassword, unreachable, other), or null. */
    fun state(c: Context, id: String): String? = p(c).getString("state.$id", null)
    fun setState(c: Context, id: String, reason: String?) = p(c).edit().apply { if (reason == null) remove("state.$id") else putString("state.$id", reason) }.apply()
    fun receipts(c: Context) = p(c).getInt("receipts", 1) // 0 never, 1 ask, 2 always
    /** Months of mail (-1 all) and of attachments (0 none, -1 all) kept on this device (MAIL_PLAN §2). */
    fun bodies(c: Context) = p(c).getInt("bodies", 6)
    fun attachments(c: Context) = p(c).getInt("attachments", 1)
    fun setBodies(c: Context, months: Int) = p(c).edit().putInt("bodies", months).apply()
    fun setAttachments(c: Context, months: Int) = p(c).edit().putInt("attachments", months).apply()
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
    /** [attachments]: the names the computer gave them on disk (its .files folder), in order. */
    class Message(val html: String?, val text: String?, val to: String, val cc: String, val headers: Map<String, String>, val attachments: List<String> = emptyList())

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
        val filename get() = extended(headers["content-disposition"].orEmpty(), "filename") ?: param(headers["content-disposition"].orEmpty(), "filename")?.let(::words)
            ?: extended(headers["content-type"].orEmpty(), "name") ?: param(headers["content-type"].orEmpty(), "name")?.let(::words)
    }

    private class Parts(val top: Part, val leaves: List<Part>) {
        val htmlPart = leaves.firstOrNull { it.type == "text/html" && !it.attachment }
        val textPart = leaves.firstOrNull { it.type == "text/plain" && !it.attachment }
        // what is not the text, nor a picture the HTML shows (mailparser's "related", which the computer leaves out)
        val files by lazy { val h = htmlPart?.text(); leaves.filter { p -> p !== htmlPart && p !== textPart && (p.attachment || p.filename != null) &&
            p.headers["content-id"]?.let { h?.contains("cid:" + it.trim().removePrefix("<").removeSuffix(">")) } != true } }
    }
    private fun parts(raw: ByteArray): Parts {
        val top = part(raw)
        val leaves = mutableListOf<Part>()
        fun walk(p: Part) { if (p.type.startsWith("multipart/")) p.param("boundary")?.let { b -> split(p.body, b).forEach { walk(part(it)) } } else leaves += p }
        walk(top)
        return Parts(top, leaves)
    }

    /** The attachments as the computer writes them into the .files folder: their names on disk and their bytes. */
    fun attachments(raw: ByteArray): List<Pair<String, ByteArray>> = parts(raw).files.let { f -> diskNames(f.map { it.filename }).zip(f.map { it.bytes() }) }

    fun read(raw: ByteArray): Message {
        val ps = parts(raw)
        val top = ps.top; val leaves = ps.leaves
        val htmlPart = ps.htmlPart
        val textPart = ps.textPart
        val html = htmlPart?.text()?.let { h ->
            // pictures inside the mail (cid:) as data: URLs, so nothing is fetched for them
            leaves.filter { it.type.startsWith("image/") && it.headers["content-id"] != null }.fold(h) { acc, img ->
                val cid = img.headers["content-id"]!!.trim().removePrefix("<").removeSuffix(">")
                acc.replace("cid:$cid", "data:${img.type};base64,${java.util.Base64.getEncoder().encodeToString(img.bytes())}")
            }
        }
        val text = textPart?.text()
        val files = ps.files
        return Message(html, text, words(top.headers["to"].orEmpty()), words(top.headers["cc"].orEmpty()), top.headers, diskNames(files.map { it.filename }))
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

    /** RFC 2231's name*=utf-8''%CE%91… (and its name*0*=, name*1*= pieces), as mail programs send non-English names. */
    private fun extended(header: String, name: String): String? {
        val pieces = Regex(";\\s*" + Regex.escape(name) + "\\*(\\d+)?\\*?\\s*=\\s*(\"([^\"]*)\"|[^;\\s]+)", RegexOption.IGNORE_CASE).findAll(header)
            .sortedBy { it.groupValues[1].toIntOrNull() ?: 0 }.map { it.groups[3]?.value ?: it.groupValues[2] }.toList()
        if (pieces.isEmpty()) return null
        val all = pieces.joinToString("")
        val m = Regex("^([^']*)'[^']*'(.*)$", RegexOption.DOT_MATCHES_ALL).find(all) ?: return all
        val out = java.io.ByteArrayOutputStream()
        val v = m.groupValues[2]; var i = 0
        while (i < v.length) {
            val hex = if (v[i] == '%' && i + 2 < v.length) v.substring(i + 1, i + 3).toIntOrNull(16) else null
            if (hex != null) { out.write(hex); i += 3 }
            else { out.write(v[i].code); i++ }
        }
        return String(out.toByteArray(), charset(m.groupValues[1].ifEmpty { null }))
    }

    /** The computer's clean(): a name every file system takes, at most [bytes] bytes of UTF-8 (its desktop/mail.js). */
    fun clean(s: String?, bytes: Int): String {
        var out = (s ?: "").replace(Regex("[/\\\\\u0000:*?\"<>|\r\n\t]"), " ").replace(Regex("\\s+"), " ").trim().replace(Regex("^\\.+"), "")
        while (out.toByteArray().size > bytes) out = out.dropLast(1)
        return out.trim()
    }

    /** The attachments' names on disk as the computer's store() makes them: cleaned, "attachment" when nameless, " (2)" for a repeat. */
    fun diskNames(names: List<String?>): List<String> {
        val taken = HashSet<String>()
        return names.map { name ->
            val raw = clean(name, 150).ifEmpty { "attachment" }
            val dot = raw.lastIndexOf('.')
            var n = raw; var i = 2
            while (n.lowercase() in taken) { n = if (dot > 0) "${raw.substring(0, dot)} ($i)${raw.substring(dot)}" else "$raw ($i)"; i++ }
            taken += n.lowercase(); n
        }
    }

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
