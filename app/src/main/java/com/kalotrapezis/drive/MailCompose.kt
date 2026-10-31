package com.kalotrapezis.drive

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What is being written: new, a reply (to all), a forward, of [of]; what MailScreen hands the compose page. */
internal data class MailDraft(val kind: String, val of: MailEntry? = null)

/** An attachment chosen on this device ([uri]) or carried from the mail being forwarded ([file]). */
internal data class MailAttachment(val name: String, val uri: Uri? = null, val file: File? = null)

/**
 * A mail for an account the PC fetches (a shadow here): written into the outbox for the PC to send or keep as a draft
 * (step 1's format, MAIL_PLAN §12). The attachments chosen here go beside it in <name>.files/, the archive's by their path;
 * sync takes the .json last, which starts it.
 */
internal object MailOutbox {
    fun write(c: Context, root: File, m: MailOut, action: String, html: String, messageId: String, now: Long = System.currentTimeMillis()): File {
        val dir = File(root, "outbox").apply { mkdirs() }
        val device = MailEngine.device()
        var at = now
        while (File(dir, "$device-$at.json").exists()) at++
        val name = "$device-$at"
        val own = m.attachments.filter { it.file == null }
        val names = Mime.diskNames(own.map { it.name }).iterator()
        val files = m.attachments.map { a ->
            if (a.file != null) JSONObject().put("name", a.name).put("file", a.file.relativeTo(root).invariantSeparatorsPath)
            else names.next().let { n ->
                File(dir, "$name.files/$n").apply { parentFile?.mkdirs() }.outputStream().use { out -> c.contentResolver.openInputStream(a.uri!!)!!.use { it.copyTo(out) } }
                JSONObject().put("name", a.name).put("file", "outbox/$name.files/$n")
            }
        }
        fun people(s: String) = JSONArray(if (s.isBlank()) emptyList() else InternetAddress.parse(s.replace(';', ','), false).map { JSONObject().put("name", it.personal ?: "").put("address", it.address) })
        val draft = JSONObject().put("to", people(m.to)).put("cc", people(m.cc)).put("bcc", people(m.bcc)).put("subject", m.subject).put("html", html)
            .put("messageId", messageId).put("attachments", JSONArray(files))
        m.inReplyTo?.let { draft.put("inReplyTo", it) }
        m.references?.let { r -> draft.put("references", JSONArray(r.split(Regex("\\s+")).filter { it.isNotBlank() })) }
        val json = JSONObject().put("account", m.account).put("action", action).put("at", at).put("device", device).put("draft", draft)
        val part = File(dir, ".$name.json.part").apply { writeText(json.toString(2)) } // a dot file: sync never takes it half written
        return File(dir, "$name.json").also { check(part.renameTo(it)) }
    }

    /** What the PC could not send (its <name>.failed.json): (subject, why), and the files to delete once shown. */
    fun failed(root: File): List<Triple<String, String, File>> = File(root, "outbox").listFiles { f -> f.name.endsWith(".failed.json") }.orEmpty().mapNotNull { f ->
        runCatching { JSONObject(f.readText()).let { o -> Triple(o.optJSONObject("draft")?.optString("subject").orEmpty(), o.optString("detail").ifEmpty { o.optString("error") }, f) } }.getOrNull()
    }
}

/** A mail written here, ready to go: what [MailSend] sends or saves. */
internal data class MailOut(val account: String, val to: String, val cc: String, val bcc: String, val subject: String, val body: String,
    val attachments: List<MailAttachment>, val inReplyTo: String? = null, val references: String? = null, val html: String? = null)

/**
 * Sending and drafts for the accounts this device fetches (MAIL_PLAN §10 step 4): SMTP to send, then a copy in Sent
 * (Gmail files its own); a draft into Drafts. The PC's accounts go through its outbox (step 1's format).
 */
internal object MailSend {
    fun message(c: Context, root: File, m: MailOut): MimeMessage {
        val acc = MailEngine.ownAccounts(root).first { it.optString("id") == m.account }
        val server = MailServer.of(acc.optJSONObject("smtp"))!!
        return MimeMessage(MailEngine.session(server, smtp = true)).apply {
            setFrom(InternetAddress(acc.optString("address", m.account), acc.optString("sender").ifEmpty { null }, "UTF-8"))
            if (m.to.isNotBlank()) setRecipients(Message.RecipientType.TO, InternetAddress.parse(m.to.replace(';', ','), false))
            if (m.cc.isNotBlank()) setRecipients(Message.RecipientType.CC, InternetAddress.parse(m.cc.replace(';', ','), false))
            if (m.bcc.isNotBlank()) setRecipients(Message.RecipientType.BCC, InternetAddress.parse(m.bcc.replace(';', ','), false))
            setSubject(m.subject, "UTF-8")
            m.inReplyTo?.let { setHeader("In-Reply-To", it) }
            m.references?.let { setHeader("References", it) }
            setHeader("User-Agent", "Tetra")
            sentDate = java.util.Date()
            // the text and, beside it, the HTML (a designed signature keeps its look)
            val text = if (m.html == null) MimeBodyPart().apply { setText(m.body, "UTF-8") } else MimeBodyPart().apply { setContent(MimeMultipart("alternative").apply {
                addBodyPart(MimeBodyPart().apply { setText(m.body, "UTF-8") })
                addBodyPart(MimeBodyPart().apply { setText(m.html, "UTF-8", "html") })
            }) }
            if (m.attachments.isEmpty()) setContent(MimeMultipart().apply { addBodyPart(text) })
            else setContent(MimeMultipart().apply {
                addBodyPart(text)
                m.attachments.forEach { a ->
                    val bytes = a.file?.readBytes() ?: c.contentResolver.openInputStream(a.uri!!)!!.use { it.readBytes() }
                    addBodyPart(MimeBodyPart().apply {
                        dataHandler = jakarta.activation.DataHandler(jakarta.mail.util.ByteArrayDataSource(bytes, c.contentResolver.getType(a.uri ?: Uri.EMPTY) ?: "application/octet-stream"))
                        fileName = jakarta.mail.internet.MimeUtility.encodeText(a.name, "UTF-8", null)
                    })
                }
            })
            saveChanges()
            setHeader("Message-ID", "<${java.util.UUID.randomUUID()}@${acc.optString("address", m.account).substringAfter('@')}>") // not "@localhost"
        }
    }

    private fun appendTo(c: Context, root: File, account: String, role: String, msg: MimeMessage, flags: Flags) {
        val acc = MailEngine.ownAccounts(root).first { it.optString("id") == account }
        val server = MailServer.of(acc.optJSONObject("server")) ?: return
        val store = MailEngine.session(server).getStore("imap").apply { connect(server.host, server.port, server.user, MailSecret.get(c, account)) }
        try {
            val folder = store.getFolder(MailEngine.roles(store)[role] ?: return)
            folder.open(Folder.READ_WRITE)
            try { msg.setFlags(flags, true); folder.appendMessages(arrayOf(msg)) } finally { folder.close(false) }
        } finally { runCatching { store.close() } }
    }

    fun send(c: Context, root: File, m: MailOut) {
        val acc = MailEngine.ownAccounts(root).first { it.optString("id") == m.account }
        val smtp = MailServer.of(acc.optJSONObject("smtp"))!!
        val msg = message(c, root, m)
        MailEngine.session(smtp, smtp = true).getTransport("smtp").use { t ->
            t.connect(smtp.host, smtp.port, smtp.user, MailSecret.get(c, m.account))
            t.sendMessage(msg, msg.allRecipients)
        }
        if (smtp.host != "smtp.gmail.com") runCatching { appendTo(c, root, m.account, "Sent", msg, Flags(Flags.Flag.SEEN)) } // Gmail keeps its own copy
            .onFailure { WriterLog.w("Mail: the copy in Sent failed (${it.message})") }
        runCatching { MailEngine.sync(c, root, acc) }
    }

    fun draft(c: Context, root: File, m: MailOut) {
        appendTo(c, root, m.account, "Drafts", message(c, root, m), Flags(Flags.Flag.DRAFT).apply { add(Flags.Flag.SEEN) })
        runCatching { MailEngine.sync(c, root, MailEngine.ownAccounts(root).first { it.optString("id") == m.account }) }
    }

    /** A reply's, a forward's subject: one "Re: " / "Fwd: ", not "Re: Re: ". */
    fun subject(kind: String, s: String) = when (kind) {
        "forward" -> if (Regex("^\\s*(fwd?|προωθ)\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(s)) s else "Fwd: $s"
        "reply", "replyAll" -> if (Regex("^\\s*(re|απ|σχετ)\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(s)) s else "Re: $s"
        else -> s
    }

    /** The text quoted under a reply, each line with "> ". */
    fun quote(text: String) = text.trimEnd().lines().joinToString("\n") { "> $it" }
}

/**
 * Writing (MAIL_PLAN §9): Από, Προς, Θέμα and the text, the island at the bottom with the two big actions — «Αποστολή»
 * and «Αποθήκευση πρόχειρου» — and the rest pulled up: attachment, Cc/Bcc, signature, send later, discard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MailCompose(draft: MailDraft, accounts: List<MailAccount>, root: File, close: (String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val of = draft.of
    val original = remember(of) { of?.let { MailFiles.eml(root, it) }?.let { f -> runCatching { Mime.read(f.readBytes()) }.getOrNull() } }
    // from: the account the mail came to, or the first this device sends from
    var from by remember { mutableStateOf(accounts.firstOrNull { it.id == of?.account }?.id ?: accounts.firstOrNull { it.own }?.id ?: accounts.firstOrNull()?.id.orEmpty()) }
    val me = accounts.firstOrNull { it.id == from }
    var to by remember { mutableStateOf(when (draft.kind) {
        "reply" -> of?.fromAddress.orEmpty()
        "replyAll" -> (listOfNotNull(of?.fromAddress) + of?.to.orEmpty()).filter { !it.equals(me?.address, true) }.distinct().joinToString(", ")
        else -> ""
    }) }
    var cc by remember { mutableStateOf(if (draft.kind == "replyAll") original?.cc.orEmpty() else "") }
    var bcc by remember { mutableStateOf("") }
    var showCc by remember { mutableStateOf(cc.isNotEmpty()) }
    var subject by remember { mutableStateOf(of?.let { MailSend.subject(draft.kind, it.subject) }.orEmpty()) }
    var signed by remember { mutableStateOf(true) }
    val signatureHtml = remember(me) { MailSignatures.html(root, me?.address.orEmpty()) }
    val signature = remember(signatureHtml) { MailSignatures.plain(signatureHtml) }
    val dash = if (MailSignatures.dashed(signatureHtml)) "" else "-- \n" // one that has its own "-- " line gets no second
    val messageId = remember { "<${java.util.UUID.randomUUID()}@" } // one per mail written: the PC replaces a saved draft by it
    val at = of?.let { Instant.ofEpochMilli(it.date).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", java.util.Locale.getDefault())) }.orEmpty()
    // the line over the quoted mail: the signature goes above it
    val quoteHead = remember { when (draft.kind) {
        "reply", "replyAll" -> context.getString(R.string.mail_wrote, at, of?.from?.ifEmpty { of.fromAddress }.orEmpty())
        "forward" -> context.getString(R.string.mail_forwarded)
        else -> null
    } }
    var body by remember { mutableStateOf(run {
        val text = original?.text ?: original?.html?.replace(Regex("<br\\s*/?>|</p>", RegexOption.IGNORE_CASE), "\n")?.replace(Regex("<[^>]+>"), "")?.let { android.text.Html.fromHtml(it, 0).toString() } ?: of?.snippet.orEmpty()
        when (draft.kind) {
            "reply", "replyAll" -> "\n\n" + quoteHead + "\n" + MailSend.quote(text)
            "forward" -> "\n\n" + quoteHead + "\n" + listOf(context.getString(R.string.mail_from) + ": " + of?.from + " <" + of?.fromAddress + ">",
                context.getString(R.string.mail_sort_date) + ": " + at, context.getString(R.string.mail_subject) + ": " + of?.subject).joinToString("\n") + "\n\n" + text
            else -> ""
        }
    }) }
    var attachments by remember { mutableStateOf(if (draft.kind == "forward" && of != null) MailFiles.attachments(root, of).map { MailAttachment(it.name, file = it) } else emptyList()) }
    var panel by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        attachments = attachments + uris.map { u -> MailAttachment(context.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "attachment", uri = u) }
    }
    val touched = to.isNotBlank() || subject != (of?.let { MailSend.subject(draft.kind, it.subject) }.orEmpty()) || body.isNotBlank() && draft.kind == "new"
    BackHandler { if (panel) panel = false else if (touched) asking = true else close(null) }

    /** The text with the signature (Settings › Υπογραφές) above the quoted mail, as Thunderbird puts it. */
    fun signedBody(): String {
        if (!signed || signature.isBlank()) return body
        val i = quoteHead?.let { body.indexOf("\n\n$it") } ?: -1
        return if (i >= 0) body.substring(0, i).trimEnd() + "\n\n" + dash + signature + body.substring(i) else body.trimEnd() + "\n\n" + dash + signature
    }
    /** The same as HTML for the PC's outbox, the signature in its <div class="tetra-signature"> (MAIL_PLAN §12). */
    fun bodyHtml(): String {
        val esc = MailSignatures::escaped
        if (!signed || signature.isBlank()) return esc(body)
        val sig = "<div class=\"tetra-signature\">${if (dash.isEmpty()) "" else "-- <br>"}$signatureHtml</div>" // the stored HTML as it is
        val i = quoteHead?.let { body.indexOf("\n\n$it") } ?: -1
        return if (i >= 0) esc(body.substring(0, i).trimEnd()) + sig + esc(body.substring(i)) else esc(body.trimEnd()) + sig
    }
    fun out() = MailOut(from, to, cc, bcc, subject, signedBody(), attachments, of?.let { original?.headers?.get("message-id") },
        of?.let { listOfNotNull(original?.headers?.get("references"), original?.headers?.get("message-id")).joinToString(" ").ifEmpty { null } }, html = bodyHtml())
    fun go(save: Boolean) {
        if (!save && to.isBlank()) { problem = context.getString(R.string.mail_no_recipient); return }
        busy = true; problem = null
        if (me?.own != true) { // the PC's account: its outbox, sent at once by the scoped sync "mail"
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching {
                    MailOutbox.write(context.applicationContext, root, out(), if (save) "draft" else "send", bodyHtml(), messageId + (me?.address ?: from).substringAfter('@') + ">")
                    ScopedSync.soon(context.applicationContext, "mail", 0, orWhole = true)
                } }
                busy = false
                r.onSuccess { close(context.getString(if (save) R.string.mail_outbox_draft else R.string.mail_outbox_send)) }
                    .onFailure { WriterLog.e("Mail: the outbox", it); problem = context.getString(R.string.mail_not_sent, it.message.orEmpty()) }
            }
            return
        }
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { if (save) MailSend.draft(context.applicationContext, root, out()) else MailSend.send(context.applicationContext, root, out()) } }
            busy = false
            r.onSuccess { close(context.getString(if (save) R.string.mail_draft_saved else R.string.mail_sent_ok)) }
                .onFailure { WriterLog.w("Mail: ${if (save) "draft" else "send"} failed (${it.message})"); problem = context.getString(R.string.mail_not_sent, mailWhy(context, it)) }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(horizontal = 16.dp).padding(top = if (MailLook.top) 76.dp else 0.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
                    IconButton(onClick = { if (touched) asking = true else close(null) }) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.mail_discard)) }
                }
                Text(stringResource(when (draft.kind) { "reply", "replyAll" -> R.string.mail_reply; "forward" -> R.string.mail_forward; else -> R.string.mail_new_message }),
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            // Από: the accounts, chips; the PC's say they go through it
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.mail_from), color = MaterialTheme.colorScheme.onSurfaceVariant)
                accounts.sortedBy { it.shadow }.forEach { a -> // this device's own first: they send from here
                    Surface(color = if (a.id == from) MAIL_ACCENT else MaterialTheme.colorScheme.surfaceVariant, contentColor = if (a.id == from) Color.White else MaterialTheme.colorScheme.onSurface,
                        shape = CircleShape, modifier = Modifier.clickable { from = a.id }) {
                        Text(a.address + if (a.shadow) " ⇄ PC" else "", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            OutlinedTextField(to, { to = it; problem = null }, label = { Text(stringResource(R.string.mail_to_field)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
            if (showCc) {
                OutlinedTextField(cc, { cc = it }, label = { Text(stringResource(R.string.mail_cc_field)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(bcc, { bcc = it }, label = { Text(stringResource(R.string.mail_bcc_field)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
            }
            OutlinedTextField(subject, { subject = it }, singleLine = true, label = { Text(stringResource(R.string.mail_subject)) }, modifier = Modifier.fillMaxWidth())
            if (attachments.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                attachments.forEach { a ->
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.ic_attach), null, Modifier.size(16.dp)); Text(" " + a.name, style = MaterialTheme.typography.labelLarge)
                            IconButton(onClick = { attachments = attachments - a }) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.common_remove), Modifier.size(16.dp)) }
                        }
                    }
                }
            }
            OutlinedTextField(body, { body = it }, label = { Text(stringResource(R.string.mail_body)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp))
            if (signed && signature.isNotBlank()) Text(dash + signature, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Box(Modifier.heightIn(min = 120.dp))
        }
        // the writing island: two big buttons with words; the rest pulled up
        Box(if (MailLook.top) Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp) else Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(bottom = 12.dp)) {
            IslandBottomBar(expand = { panel = true }, visible = !panel) {
                Surface(color = MAIL_ACCENT, contentColor = Color.White, shape = CircleShape, modifier = Modifier.clickable(enabled = !busy) { go(false) }) {
                    Row(Modifier.padding(horizontal = 22.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_arrow_forward), null)
                        Text(stringResource(if (busy) R.string.mail_sending else R.string.mail_send), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
                Surface(color = Color.Transparent, contentColor = islandContentColor(), shape = CircleShape, modifier = Modifier.clickable(enabled = !busy) { go(true) }) {
                    Text(stringResource(R.string.mail_save_draft), Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
    if (panel) ModalBottomSheet(onDismissRequest = { panel = false }, containerColor = SheetColor, contentColor = islandContentColor()) {
        Column(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            ChoiceGroup {
                DrawerRow(R.drawable.ic_attach, stringResource(R.string.mail_attach), true) { panel = false; pick.launch(arrayOf("*/*")) }
                DrawerRow(R.drawable.ic_person, stringResource(R.string.mail_cc_bcc), true) { panel = false; showCc = !showCc }
                DrawerRow(R.drawable.ic_edit_note, stringResource(if (signed) R.string.mail_signature_off else R.string.mail_signature_on), signature.isNotBlank()) { if (signature.isNotBlank()) { panel = false; signed = !signed } }
                DrawerRow(R.drawable.ic_sort_time, stringResource(R.string.mail_send_later), false) {}
                DrawerRow(R.drawable.ic_delete, stringResource(R.string.mail_discard), true) { panel = false; if (touched) asking = true else close(null) }
            }
        }
    }
    if (asking) AlertDialog(onDismissRequest = { asking = false }, title = { Text(stringResource(R.string.mail_discard_ask)) },
        confirmButton = { TextButton(onClick = { asking = false; close(null) }) { Text(stringResource(R.string.mail_discard), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(R.string.common_cancel)) } })
}
