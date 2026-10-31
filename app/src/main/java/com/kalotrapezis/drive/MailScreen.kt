package com.kalotrapezis.drive

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.FileObserver
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Mail's colour: the icon's own pink (tools/applet-icons.py: applet_mail). */
internal val MAIL_COLOR = Color(0xFFFD0DA4)
private val GREEN = Color(0xFF3FB27F)
private val ORANGE = Color(0xFFE0A43A)

internal fun mailRoot() = MailFiles.root(TetraFolder.root(Environment.getExternalStorageDirectory()))

private val ROLE_NAMES = mapOf("Inbox" to R.string.mail_inbox, "Drafts" to R.string.mail_drafts, "Sent" to R.string.mail_sent,
    "Archive" to R.string.mail_archive, "Spam" to R.string.mail_spam, "Trash" to R.string.mail_trash)
private val ROLE_ICONS = mapOf("Inbox" to R.drawable.ic_mail, "Drafts" to R.drawable.ic_edit_note, "Sent" to R.drawable.ic_arrow_forward,
    "Archive" to R.drawable.ic_archive, "Spam" to R.drawable.ic_report, "Trash" to R.drawable.ic_delete)

/**
 * Mail (MAIL_PLAN §9), read only: the computer's archive as sync brought it. Folders at the side (the unified ones
 * expanding into the accounts), the list, the open mail; a tablet has all three side by side with two islands (the
 * list's actions, the mail's one big Απάντηση), a phone goes Mailboxes → list → mail. What would change the server is
 * shown but waits for step 3 (actions through the PC) and 4 (writing).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MailScreen(back: () -> Unit, hasAccess: Boolean, grant: () -> Unit, openFile: (File) -> Unit, openSettings: () -> Unit) {
    val context = LocalContext.current
    val width = LocalConfiguration.current.screenWidthDp
    val three = width >= 1000 // folders · list · mail
    val wide = width >= 700   // folders beside the list (or the mail)
    if (!hasAccess) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.common_back)) }
            Text(stringResource(R.string.mail_access_why))
            TextButton(onClick = grant) { Text(stringResource(R.string.contacts_allow_access)) }
        }
        return
    }
    val root = remember { mailRoot() }
    var version by remember { mutableIntStateOf(0) }
    // the computer's index and accounts arriving by sync: read again
    DisposableEffect(root) {
        root.mkdirs()
        val watch = object : FileObserver(root, CLOSE_WRITE or MOVED_TO or DELETE) {
            override fun onEvent(event: Int, path: String?) { if (path == "index.jsonl" || path == "accounts.json") android.os.Handler(android.os.Looper.getMainLooper()).post { version++ } }
        }
        watch.startWatching()
        onDispose { watch.stopWatching() }
    }
    val data by produceState<Pair<List<MailAccount>, List<MailEntry>>?>(null, version) {
        value = withContext(Dispatchers.IO) { runCatching { MailFiles.accounts(root) to MailFiles.index(root) }.onFailure { WriterLog.e("Mail: reading the archive", it) }.getOrDefault(emptyList<MailAccount>() to emptyList()) }
    }
    val accounts = data?.first.orEmpty()
    // what was asked here and the computer has not done yet, shown as done (step 1: show it, then trust index.jsonl)
    var changed by remember { mutableStateOf(mapOf<String, MailEntry?>()) }
    val mails = remember(data, changed) { data?.second.orEmpty().mapNotNull { e -> if (e.id in changed) changed[e.id] else e } }
    OpenTiming("Mail", data != null)
    val counts = remember(mails, accounts) { MailFiles.counts(mails, accounts) }
    val accountOf = remember(accounts) { accounts.associateBy { it.id } }

    var role by remember { mutableStateOf("Inbox") }
    var account by remember { mutableStateOf<String?>(null) } // null: the unified folder
    val folderKey = role + (account?.let { "/$it" } ?: "")
    var expanded by remember { mutableStateOf(setOf("Inbox")) }
    var mailboxes by remember { mutableStateOf(false) } // a phone's first level
    var openId by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var query by remember { mutableStateOf("") }
    var view by remember(folderKey) { mutableStateOf(MailPrefs.view(context, folderKey) ?: MailView()) }
    var keep by remember(folderKey) { mutableStateOf(MailPrefs.view(context, folderKey) != null) }
    var filterOpen by remember { mutableStateOf(false) }
    var listDrawer by remember { mutableStateOf(false) }
    var mailDrawer by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(message) { if (message != null) { delay(3500); message = null } }
    val later = { message = context.getString(R.string.mail_later) }
    val writeLater = { message = context.getString(R.string.mail_write_later) }
    var moving by remember { mutableStateOf<Collection<String>?>(null) }
    var autoOpened by remember { mutableStateOf(false) }
    /** [op] on these mails: a request for the computer, sent at once; shown done here meanwhile. */
    fun act(op: String, ids: Collection<String>, to: String? = null) {
        if (ids.isEmpty()) return
        val now = mails.filter { it.id in ids }
        changed = changed + now.associate { it.id to MailRequests.applied(it, op) }
        if (MailRequests.applied(now.first(), op) == null) { selected = emptySet(); if (openId in ids) openId = null }
        Thread {
            runCatching { MailRequests.write(root, DataFiles.device(), op, ids, to) }.onFailure { WriterLog.e("Mail: a request", it) }
            ScopedSync.soon(context.applicationContext, "mail", 0, orWhole = true)
        }.start()
        if (op in setOf("archive", "delete", "move", "spam", "notSpam")) message = context.getString(R.string.mail_done_on_pc)
    }
    val spamOp = if (role == "Spam") "notSpam" else "spam"

    val inFolder = remember(mails, role, account) { mails.filter { it.folder == role && it.account in accountOf && (account == null || it.account == account) } }
    val groups = remember(inFolder, view, query) { view.arrange(inFolder, query) }
    val shown = remember(groups) { groups.flatMap { it.second } }
    val open = mails.firstOrNull { it.id == openId }
    // a wide screen always shows a mail: the newest, until one is chosen
    LaunchedEffect(three, shown) { if (three && (open == null || open !in shown) && shown.isNotEmpty()) { openId = shown.first().id; autoOpened = true } }
    // a mail the person opened is read (Thunderbird's way); not the one a wide screen showed by itself
    LaunchedEffect(openId) { open?.takeIf { !it.seen && !autoOpened }?.let { act("read", listOf(it.id)) } }
    fun choose(r: String, a: String?) { role = r; account = a; openId = null; selected = emptySet(); mailboxes = false }

    BackHandler { when {
        selected.isNotEmpty() -> selected = emptySet()
        !three && open != null -> openId = null
        query.isNotEmpty() -> query = ""
        !wide && !mailboxes -> mailboxes = true
        else -> back()
    } }

    // ---- the folders (MAIL_PLAN §4): unified, each expanding into the accounts; counts green or orange ----
    val folders: @Composable (Modifier) -> Unit = { m ->
        Column(m.statusBarsPadding()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
                    IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.home_back_to_home)) }
                }
                Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.app_mail), Modifier.padding(horizontal = 18.dp, vertical = 13.dp), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (data != null && accounts.isEmpty()) item { MailHint(stringResource(R.string.mail_no_accounts)) }
                if (accounts.isNotEmpty()) MailFiles.ROLES.forEach { r ->
                    val per = counts[r].orEmpty()
                    item(key = r) {
                        val on = role == r && account == null
                        Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(if (on) MAIL_COLOR.copy(alpha = 0.22f) else Color.Transparent)
                            .clickable { choose(r, null) }.padding(end = 10.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { expanded = if (r in expanded) expanded - r else expanded + r }) {
                                Icon(painterResource(R.drawable.ic_chevron_right), stringResource(R.string.mail_show_accounts), Modifier.size(20.dp).rotate(if (r in expanded) 90f else 0f))
                            }
                            Icon(painterResource(ROLE_ICONS.getValue(r)), null, Modifier.size(20.dp))
                            Text(stringResource(ROLE_NAMES.getValue(r)), Modifier.weight(1f).padding(start = 10.dp), fontWeight = if (on) FontWeight.Bold else null, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            CountBadge(per.values.sumOf { it.unread }, per.values.sumOf { it.orange })
                        }
                    }
                    if (r in expanded) accounts.forEach { a ->
                        val c = per[a.id] ?: MailCount(0, 0, 0)
                        item(key = "$r/${a.id}") {
                            val on = role == r && account == a.id
                            Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(if (on) MAIL_COLOR.copy(alpha = 0.22f) else Color.Transparent)
                                .clickable { choose(r, a.id) }.padding(start = 52.dp, end = 10.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.size(9.dp).clip(CircleShape).background(Color(a.color)))
                                // a shadow account (the PC's) dimmed, with ⇄ PC (MAIL_PLAN §1)
                                Text(a.name, Modifier.weight(1f).alpha(if (a.shadow) 0.6f else 1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (a.shadow) PcBadge()
                                CountBadge(c.unread, c.orange)
                            }
                        }
                    }
                }
                if (accounts.isNotEmpty()) item {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(Modifier.padding(start = 16.dp, top = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Legend(GREEN, stringResource(R.string.mail_local)); Legend(GREEN, stringResource(R.string.mail_on_pc), ring = true); Legend(ORANGE, stringResource(R.string.mail_server_only))
                    }
                }
            }
        }
    }

    // ---- the list ----
    val list: @Composable (Modifier) -> Unit = { m ->
        Box(m) {
            LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(top = 72.dp, bottom = 120.dp, start = 8.dp, end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (data == null) item { MailHint(stringResource(R.string.mail_reading)) }
                else if (shown.isEmpty()) item { MailHint(stringResource(when {
                    accounts.isEmpty() -> R.string.mail_no_accounts
                    query.isNotEmpty() -> R.string.mail_no_match
                    view != MailView() -> R.string.mail_none_filtered
                    else -> R.string.mail_none_here
                })) }
                groups.forEach { (key, ms) ->
                    if (key.isNotEmpty()) item(key = "g:$key") {
                        Text(groupName(view.group, key, accountOf), Modifier.padding(start = 12.dp, top = 8.dp), color = MAIL_COLOR, style = MaterialTheme.typography.labelLarge)
                    }
                    items(ms, key = { it.id }) { e ->
                        MailRow(e, accountOf[e.account], picked = e.id in selected, choosing = selected.isNotEmpty(), chosen = wide && selected.isEmpty() && e.id == openId,
                            click = { if (selected.isNotEmpty()) selected = if (e.id in selected) selected - e.id else selected + e.id else { autoOpened = false; openId = e.id } },
                            long = { selected = if (e.id in selected) selected - e.id else selected + e.id },
                            here = remember(e.file, version) { e.file != null && File(root, e.file).isFile },
                            heart = { act(if (e.flagged) "unfavorite" else "favorite", listOf(e.id)) })
                    }
                }
            }
            // the top: search, or while choosing ✕ · N επιλεγμένα · Όλα
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selected.isNotEmpty()) {
                    Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
                        IconButton(onClick = { selected = emptySet() }) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.contacts_done_choosing)) }
                    }
                    Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape, modifier = Modifier.weight(1f)) {
                        Text(pluralStringResource(R.plurals.mail_n_selected, selected.size, selected.size), Modifier.padding(horizontal = 18.dp, vertical = 13.dp), fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                    Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
                        TextButton(onClick = { selected = if (selected.size == shown.size) emptySet() else shown.map { it.id }.toSet() }) {
                            Text(stringResource(if (selected.size == shown.size) R.string.common_none else R.string.common_all), color = islandContentColor())
                        }
                    }
                } else {
                    if (!wide) Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
                        IconButton(onClick = { mailboxes = true }) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.mail_mailboxes)) }
                    }
                    Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape, modifier = Modifier.weight(1f)) {
                        Row(Modifier.padding(start = 16.dp, end = 4.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(painterResource(R.drawable.ic_search), contentDescription = null, Modifier.size(22.dp))
                            Box(Modifier.weight(1f)) {
                                if (query.isEmpty()) Text(if (wide) stringResource(R.string.mail_search) else folderName(role, account, accountOf), color = islandContentColor().copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(color = islandContentColor(), fontSize = 16.sp),
                                    cursorBrush = SolidColor(islandContentColor()), modifier = Modifier.fillMaxWidth())
                            }
                            if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.common_clear)) }
                        }
                    }
                }
            }
            // the list's island: New · Sync · Filter · Archive, the rest pulled up; while choosing, the selection island
            Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp)) {
                IslandBottomBar(expand = { listDrawer = true }, visible = !listDrawer && !filterOpen) {
                    if (selected.isNotEmpty()) {
                        MailIslandItem(R.drawable.ic_archive, stringResource(R.string.mail_do_archive)) { act("archive", selected) }
                        MailIslandItem(R.drawable.ic_delete, stringResource(R.string.common_delete)) { act("delete", selected) }
                        val allRead = mails.filter { it.id in selected }.all { it.seen }
                        MailIslandItem(R.drawable.ic_done_all, stringResource(if (allRead) R.string.mail_mark_unread else R.string.mail_mark_read)) { act(if (allRead) "unread" else "read", selected); selected = emptySet() }
                    } else {
                        MailIslandItem(R.drawable.ic_add, stringResource(R.string.common_new), enabled = false, click = writeLater)
                        MailIslandItem(R.drawable.ic_sync, stringResource(R.string.mail_sync)) {
                            runCatching { androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, SyncService::class.java).setAction(SyncService.ACTION_START)) }
                                .onFailure { WriterLog.e("Mail: sync", it) }
                            message = context.getString(R.string.mail_syncing)
                        }
                        MailIslandItem(R.drawable.ic_filter, stringResource(R.string.mail_filter), on = view != MailView()) { filterOpen = true }
                        MailIslandItem(R.drawable.ic_archive, stringResource(R.string.mail_do_archive), enabled = open != null) { open?.let { act("archive", listOf(it.id)) } }
                    }
                }
            }
        }
    }

    // ---- the open mail, or on a tablet what is chosen ----
    val page: @Composable (Modifier) -> Unit = { m ->
        Box(m) {
            val many = mails.filter { it.id in selected }
            if (wide && many.size > 1) SelectionPane(many, accountOf, Modifier.statusBarsPadding().padding(16.dp), later)
            else if (open != null) MailPage(open, accountOf[open.account], root, showBack = !three, back = { openId = null }, openFile = openFile, say = { message = it }, fetched = { version++ })
            else if (wide) MailHint(stringResource(R.string.mail_choose), Modifier.align(Alignment.Center))
            // the mail's island: one big Απάντηση; the rest pulled up from it
            if (open != null && !(wide && many.size > 1)) Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp)) {
                IslandBottomBar(expand = { mailDrawer = true }, visible = !mailDrawer) {
                    Surface(color = MAIL_COLOR, contentColor = Color.White, shape = CircleShape, modifier = Modifier.alpha(0.5f).clickable(onClick = writeLater)) {
                        Row(Modifier.padding(horizontal = 26.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.ic_reply), null); Text(stringResource(R.string.mail_reply), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            when {
                three -> Row(Modifier.fillMaxSize()) { folders(Modifier.width(270.dp).fillMaxHeight()); list(Modifier.width(400.dp).fillMaxHeight()); page(Modifier.weight(1f).fillMaxHeight()) }
                wide -> Row(Modifier.fillMaxSize()) { folders(Modifier.width(260.dp).fillMaxHeight()); if (open != null || selected.size > 1) page(Modifier.weight(1f).fillMaxHeight()) else list(Modifier.weight(1f).fillMaxHeight()) }
                open != null -> page(Modifier.fillMaxSize())
                mailboxes -> folders(Modifier.fillMaxSize())
                else -> list(Modifier.fillMaxSize())
            }
            message?.let { t ->
                Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 100.dp, start = 24.dp, end = 24.dp)) { Text(t, Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) }
            }
        }
    }

    if (filterOpen) FilterSheet(view, accounts, keep, set = { v -> view = v; if (keep) MailPrefs.setView(context, folderKey, v) },
        setKeep = { k -> keep = k; MailPrefs.setView(context, folderKey, if (k) view else null) }) { filterOpen = false }
    if (listDrawer) ModalBottomSheet(onDismissRequest = { listDrawer = false }, containerColor = SheetColor, contentColor = islandContentColor()) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (selected.isNotEmpty()) {
                val allFav = mails.filter { it.id in selected }.all { it.flagged }
                DrawerRow(R.drawable.ic_favorite, stringResource(if (allFav) R.string.mail_from_favourites else R.string.mail_to_favourites), true) { listDrawer = false; act(if (allFav) "unfavorite" else "favorite", selected) }
                DrawerRow(R.drawable.ic_move, stringResource(R.string.mail_move), true) { listDrawer = false; moving = selected }
                DrawerRow(R.drawable.ic_report, stringResource(if (role == "Spam") R.string.mail_not_spam else R.string.mail_spam), true) { listDrawer = false; act(spamOp, selected) }
                DrawerRow(R.drawable.ic_label, stringResource(R.string.mail_tag), false, later)
                DrawerRow(R.drawable.ic_arrow_forward, stringResource(R.string.mail_forward_attached), false, writeLater)
                DrawerRow(R.drawable.ic_file_download, stringResource(R.string.mail_fetch_local), false, later)
                DrawerRow(R.drawable.ic_auto_fix, stringResource(R.string.mail_summarise), false, later)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // on the open mail
                    DrawerButton(R.drawable.ic_report, stringResource(if (role == "Spam") R.string.mail_not_spam else R.string.mail_spam_one), false, Modifier.weight(1f).alpha(if (open != null) 1f else 0.45f)) { open?.let { listDrawer = false; act(spamOp, listOf(it.id)) } }
                    DrawerButton(R.drawable.ic_move, stringResource(R.string.mail_move), false, Modifier.weight(1f).alpha(if (open != null) 1f else 0.45f)) { open?.let { listDrawer = false; moving = listOf(it.id) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DrawerButton(R.drawable.ic_label, stringResource(R.string.mail_tag), false, Modifier.weight(1f).alpha(0.45f)) { later() }
                    DrawerButton(R.drawable.ic_delete, stringResource(R.string.mail_clean_now), false, Modifier.weight(1f).alpha(0.45f)) { later() }
                }
                DrawerButton(R.drawable.ic_filter, stringResource(R.string.mail_rules), false, Modifier.fillMaxWidth().alpha(0.45f)) { later() }
                HorizontalDivider(color = islandContentColor().copy(alpha = 0.15f))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DrawerButton(R.drawable.ic_person, stringResource(R.string.mail_accounts), false, Modifier.weight(1f)) { listDrawer = false; openSettings() }
                    DrawerButton(R.drawable.ic_tune, stringResource(R.string.common_settings), false, Modifier.weight(1f)) { listDrawer = false; openSettings() }
                }
            }
            Text(stringResource(R.string.mail_read_only_note), style = MaterialTheme.typography.bodySmall, color = islandContentColor().copy(alpha = 0.7f))
        }
    }
    if (mailDrawer && open != null) ModalBottomSheet(onDismissRequest = { mailDrawer = false }, containerColor = SheetColor, contentColor = islandContentColor()) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DrawerRow(R.drawable.ic_reply_all, stringResource(R.string.mail_reply_all), false, writeLater)
            DrawerRow(R.drawable.ic_arrow_forward, stringResource(R.string.mail_forward), false, writeLater)
            DrawerRow(R.drawable.ic_archive, stringResource(R.string.mail_do_archive), true) { mailDrawer = false; act("archive", listOf(open.id)) }
            DrawerRow(R.drawable.ic_delete, stringResource(R.string.common_delete), true) { mailDrawer = false; act("delete", listOf(open.id)) }
            DrawerRow(R.drawable.ic_report, stringResource(if (role == "Spam") R.string.mail_not_spam else R.string.mail_spam_one), true) { mailDrawer = false; act(spamOp, listOf(open.id)) }
            DrawerRow(R.drawable.ic_move, stringResource(R.string.mail_move), true) { mailDrawer = false; moving = listOf(open.id) }
            DrawerRow(R.drawable.ic_done_all, stringResource(R.string.mail_mark_unread), true) { mailDrawer = false; act("unread", listOf(open.id)); openId = null }
            DrawerRow(R.drawable.ic_label, stringResource(R.string.mail_tag), false, later)
            DrawerRow(R.drawable.ic_print, stringResource(R.string.mail_print), false, later)
            DrawerRow(R.drawable.ic_code, stringResource(R.string.mail_source), MailFiles.eml(root, open) != null) {
                mailDrawer = false
                source = runCatching { MailFiles.eml(root, open)!!.readText(Charsets.ISO_8859_1).take(200_000) }.getOrNull()
            }
            Text(stringResource(R.string.mail_read_only_note), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = islandContentColor().copy(alpha = 0.7f))
        }
    }
    moving?.let { ids ->
        AlertDialog(onDismissRequest = { moving = null }, title = { Text(stringResource(R.string.mail_move_to)) },
            text = { Column { MailFiles.ROLES.filter { it != role }.forEach { r ->
                Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { moving = null; act("move", ids, r) }.padding(vertical = 12.dp, horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(ROLE_ICONS.getValue(r)), null); Text(stringResource(ROLE_NAMES.getValue(r)))
                }
            } } },
            confirmButton = {}, dismissButton = { TextButton(onClick = { moving = null }) { Text(stringResource(R.string.common_cancel)) } })
    }
    source?.let { s ->
        AlertDialog(onDismissRequest = { source = null }, title = { Text(stringResource(R.string.mail_source)) },
            text = { SelectionContainer { Text(s, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)) } },
            confirmButton = { TextButton(onClick = { source = null }) { Text(stringResource(R.string.common_ok)) } })
    }
}

@Composable private fun MailHint(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)

/** A folder's unread count: green when every mail in it is saved here, orange while any is only on the server. */
@Composable private fun CountBadge(unread: Int, orange: Int) {
    val color = if (orange > 0) ORANGE else GREEN
    if (unread > 0) Surface(color = color, contentColor = Color.Black, shape = CircleShape) {
        Text("$unread", Modifier.padding(horizontal = 8.dp, vertical = 1.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    } else if (orange > 0) Box(Modifier.size(9.dp).clip(CircleShape).background(ORANGE))
}

@Composable private fun PcBadge() = Surface(color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurfaceVariant, shape = CircleShape,
    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))) {
    Text("⇄ PC", Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.labelSmall)
}

@Composable private fun Legend(color: Color, text: String, ring: Boolean = false) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
    StateDot(color, ring); Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun folderName(role: String, account: String?, accounts: Map<String, MailAccount>) =
    stringResource(ROLE_NAMES.getValue(role)) + (account?.let { " · " + (accounts[it]?.name ?: it) } ?: "")

@Composable private fun groupName(group: String, key: String, accounts: Map<String, MailAccount>): String = when (group) {
    "day" -> LocalDate.parse(key).let { d -> when (d) {
        LocalDate.now() -> stringResource(R.string.mail_today)
        LocalDate.now().minusDays(1) -> stringResource(R.string.mail_yesterday)
        else -> d.format(DateTimeFormatter.ofPattern("EEEE d MMMM", java.util.Locale.getDefault()))
    } }
    "account" -> accounts[key]?.name ?: key
    else -> key
}

/** A row's time: today the hour, this week the day, before that the date. */
private fun shortTime(ms: Long): String {
    val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    val days = java.time.temporal.ChronoUnit.DAYS.between(t.toLocalDate(), LocalDate.now())
    return t.format(DateTimeFormatter.ofPattern(if (days == 0L) "HH:mm" else if (days < 7) "EEE" else "d MMM", java.util.Locale.getDefault()))
}

/** A mail's state: green saved here, a green ring saved on the PC (this device keeps it there), orange only on the server. */
@Composable private fun StateDot(color: Color, ring: Boolean = false) =
    Box(Modifier.size(8.dp).clip(CircleShape).then(if (ring) Modifier.border(1.5.dp, color, CircleShape) else Modifier.background(color)))

/** One mail in the list (MAIL_PLAN §9): the account's colour as a bar, ♥ at the right, its state's dot. */
@Composable private fun MailRow(e: MailEntry, account: MailAccount?, picked: Boolean, choosing: Boolean, chosen: Boolean, click: () -> Unit, long: () -> Unit, here: Boolean, heart: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
        .background(if (picked) MAIL_COLOR.copy(alpha = 0.32f) else if (chosen) MAIL_COLOR.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
        .combinedClickable(onLongClick = long, onClick = click).heightIn(min = 72.dp)) {
        Box(Modifier.width(4.dp).fillMaxHeight().heightIn(min = 72.dp).background(Color(account?.color ?: 0xFF9E9E9E)))
        Column(Modifier.weight(1f).padding(start = 12.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (choosing) Box(Modifier.size(20.dp).clip(CircleShape).background(if (picked) MAIL_COLOR else Color.Transparent)
                    .then(if (picked) Modifier else Modifier.background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f), CircleShape)), contentAlignment = Alignment.Center) {
                    if (picked) Icon(painterResource(R.drawable.ic_check), stringResource(R.string.contacts_selected), Modifier.size(14.dp), tint = Color.White)
                }
                Text(e.from.ifEmpty { e.fromAddress }, Modifier.weight(1f), fontWeight = if (e.seen) null else FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(shortTime(e.date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (e.attachments > 0) Icon(painterResource(R.drawable.ic_attach), stringResource(R.string.mail_has_attachments), Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                StateDot(if (e.saved) GREEN else ORANGE, ring = e.saved && !here)
            }
            Text(e.subject.ifEmpty { stringResource(R.string.mail_no_subject) }, fontWeight = if (e.seen) null else FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (e.snippet.isNotEmpty()) Text(e.snippet, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = heart, modifier = Modifier.align(Alignment.CenterVertically)) {
            Icon(painterResource(if (e.flagged) R.drawable.ic_favorite else R.drawable.ic_favorite_border), stringResource(R.string.mail_favourite),
                tint = if (e.flagged) Color(0xFFE5534B) else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

/** An island's action with its word under it (the user, 31 Oct: "the buttons are small, add words"); four fit a phone and the tablet's list. */
@Composable private fun MailIslandItem(icon: Int, label: String, enabled: Boolean = true, on: Boolean = false, click: () -> Unit) = Surface(
    color = if (on) driveNavigationSelectedColor() else Color.Transparent, contentColor = if (on) driveNavigationSelectedContentColor() else islandContentColor(),
    shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.heightIn(min = 48.dp).alpha(if (enabled) 1f else 0.45f).clickable(onClick = click),
) {
    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painterResource(icon), null, Modifier.size(22.dp)); Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable private fun DrawerRow(icon: Int, label: String, enabled: Boolean, click: () -> Unit) =
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).alpha(if (enabled) 1f else 0.45f).clickable(onClick = click).padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, Modifier.size(22.dp)); Text(label, style = MaterialTheme.typography.bodyLarge)
    }

/** A tablet's right pane while several are chosen: what they are, and what the assistant could do with them (MAIL_PLAN §9). */
@Composable private fun SelectionPane(many: List<MailEntry>, accounts: Map<String, MailAccount>, modifier: Modifier, later: () -> Unit) {
    val context = LocalContext.current
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(pluralStringResource(R.plurals.mail_n_messages, many.size, many.size), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        many.forEach { e ->
            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = MaterialTheme.shapes.medium) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Color(accounts[e.account]?.color ?: 0xFF9E9E9E)))
                    Text(e.subject, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(android.text.format.Formatter.formatShortFileSize(context, e.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (e.saved) GREEN else ORANGE))
                }
            }
        }
        Text(stringResource(R.string.mail_selection_summary, pluralStringResource(R.plurals.mail_n_accounts, many.map { it.account }.distinct().size, many.map { it.account }.distinct().size),
            android.text.format.Formatter.formatShortFileSize(context, many.sumOf { it.size }), many.count { !it.saved }), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(color = MAIL_COLOR.copy(alpha = 0.2f), contentColor = MAIL_COLOR, shape = CircleShape, modifier = Modifier.alpha(0.6f).clickable(onClick = later)) {
            Text(stringResource(R.string.mail_summarise_n, many.size), Modifier.padding(horizontal = 16.dp, vertical = 10.dp), fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * The open mail: sender and subject on top, its attachments as cards (opened in Files, Office or the PDF viewer), and its
 * text. Pictures and anything else from the web load only when allowed (MAIL_PLAN §9c): once, always from this sender,
 * or always (Settings).
 */
@Composable private fun MailPage(e: MailEntry, account: MailAccount?, root: File, showBack: Boolean, back: () -> Unit, openFile: (File) -> Unit, say: (String) -> Unit, fetched: () -> Unit) {
    val context = LocalContext.current
    var failed by remember(e.id) { mutableStateOf<String?>(null) }
    var got by remember(e.id) { mutableIntStateOf(0) } // an attachment fetched: the cards again
    /** A Mail file this device does not keep, from the computer (MAIL_PLAN §2: older mail on demand). */
    fun fetch(rel: String): File = SyncClient(context.applicationContext, SyncStore(context.applicationContext)).mailFile(rel)
    val message by produceState<Mime.Message?>(null, e.id, e.file) {
        value = withContext(Dispatchers.IO) {
            val f = MailFiles.eml(root, e) ?: e.file?.takeIf { e.saved }?.let { rel ->
                runCatching { fetch(MailSync.PART + rel) }.onSuccess { fetched() }.onFailure { failed = it.shownMessage(context) ?: it.message; WriterLog.w("Mail: fetching a mail (${it.message})") }.getOrNull()
            }
            f?.let { runCatching { Mime.read(it.readBytes()) }.onFailure { WriterLog.e("Mail: reading a mail", it) }.getOrNull() }
        }
    }
    // the attachments here, and those the mail names that this device left on the PC (fetched when tapped)
    val files = remember(e.id, e.file, message, got) {
        val here = MailFiles.attachments(root, e)
        here.map { it.name to it } + message?.attachments.orEmpty().filter { n -> here.none { it.name == n } }.map { it to null }
    }
    fun open(name: String, f: File?) {
        if (f != null) return openFile(f)
        say(context.getString(R.string.mail_fetching))
        Thread {
            val r = runCatching { fetch(MailSync.PART + e.file!!.removeSuffix(".eml") + ".files/" + name) }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                r.onSuccess { got++; openFile(it) }.onFailure { say(context.getString(R.string.mail_fetch_failed, it.shownMessage(context) ?: it.message.orEmpty())) }
            }
        }.start()
    }
    var once by remember(e.id) { mutableStateOf(false) }
    var allowedVersion by remember { mutableIntStateOf(0) }
    val sender = e.fromAddress.lowercase()
    val allowed = remember(allowedVersion, sender) { MailPrefs.remote(context) || sender in MailPrefs.allowed(context) || sender.substringAfter('@') in MailPrefs.allowed(context) }
    var risky by remember { mutableStateOf<Pair<String, File?>?>(null) }
    val html = message?.let { it.html ?: it.text?.let(Mime::textAsHtml) }
    val blocked = html != null && !(once || allowed) && Mime.remote(html)
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (showBack) Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
                IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.common_back)) }
            }
            Text(e.subject.ifEmpty { stringResource(R.string.mail_no_subject) }, Modifier.weight(1f).padding(top = 6.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Color(account?.color ?: 0xFF9E9E9E)), contentAlignment = Alignment.Center) {
                Text(e.from.ifEmpty { e.fromAddress }.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Text(e.from.ifEmpty { e.fromAddress }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val parts = listOfNotNull(e.fromAddress.takeIf { MailPrefs.switch(context, "realAddress") && it.isNotEmpty() },
                    message?.to?.ifBlank { null }?.let { stringResource(R.string.mail_to, it) },
                    Instant.ofEpochMilli(e.date).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", java.util.Locale.getDefault())))
                Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                message?.cc?.takeIf { it.isNotBlank() }?.let { Text(stringResource(R.string.mail_cc, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (!e.saved) Text("● " + stringResource(R.string.mail_server_only), style = MaterialTheme.typography.bodySmall, color = ORANGE)
            }
        }
        if (files.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            files.forEach { (name, f) ->
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.clickable { if (Mime.risky(name) && MailPrefs.switch(context, "risky")) risky = name to f else open(name, f) }) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_attach), null, Modifier.size(16.dp))
                        Text(if (f != null) "$name · ${android.text.format.Formatter.formatShortFileSize(context, f.length())}" else "$name · ⇄ PC", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                }
            }
        }
        if (blocked) Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium, modifier = Modifier.padding(bottom = 10.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🛡 " + stringResource(R.string.mail_images_blocked), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { once = true }) { Text(stringResource(R.string.mail_show_images), color = MAIL_COLOR) }
                TextButton(onClick = { MailPrefs.allow(context, sender); allowedVersion++ }) { Text(stringResource(R.string.mail_always_sender), color = MAIL_COLOR) }
            }
        }
        when {
            e.file == null || !e.saved || failed != null -> {
                MailHint(failed?.let { stringResource(R.string.mail_fetch_failed, it) } ?: stringResource(R.string.mail_not_here))
                if (e.snippet.isNotEmpty()) Text(e.snippet, Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            message == null -> MailHint(stringResource(if (MailFiles.eml(root, e) == null) R.string.mail_fetching else R.string.mail_reading))
            message!!.html == null -> SelectionContainer(Modifier.weight(1f)) {
                Text(message!!.text.orEmpty(), Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 110.dp), style = MaterialTheme.typography.bodyLarge)
            }
            else -> MailHtml(html!!.let { if (!blocked && MailPrefs.switch(context, "tracking")) Mime.withoutPixels(it) else it }, load = !blocked,
                Modifier.weight(1f).fillMaxWidth().padding(bottom = 96.dp).clip(MaterialTheme.shapes.large))
        }
    }
    risky?.let { (name, f) ->
        AlertDialog(onDismissRequest = { risky = null }, title = { Text(stringResource(R.string.mail_risky_title)) }, text = { Text(stringResource(R.string.mail_risky_text, name)) },
            confirmButton = { TextButton(onClick = { risky = null; open(name, f) }) { Text(stringResource(R.string.mail_open_anyway)) } },
            dismissButton = { TextButton(onClick = { risky = null }) { Text(stringResource(R.string.common_cancel)) } })
    }
}

/** A mail's HTML on its own white page, no scripts, nothing from the web unless [load]; a link opens in the browser. */
@Composable private fun MailHtml(html: String, load: Boolean, modifier: Modifier) {
    AndroidView(factory = { c -> android.webkit.WebView(c).apply {
        settings.javaScriptEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        setBackgroundColor(android.graphics.Color.WHITE)
        webViewClient = object : android.webkit.WebViewClient() {
            // shortcut: no check that a link's text and its address agree (Settings' warning) yet; step 3 with the rest of the safety bar
            override fun shouldOverrideUrlLoading(view: android.webkit.WebView, request: android.webkit.WebResourceRequest): Boolean {
                runCatching { view.context.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                return true
            }
        }
    } }, update = { w ->
        w.settings.blockNetworkLoads = !load
        w.settings.blockNetworkImage = !load
        val page = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><style>body{margin:12px;word-wrap:break-word}img{max-width:100%;height:auto}</style>$html"
        if (w.tag != page) { w.tag = page; w.loadDataWithBaseURL(null, page, "text/html", "utf-8", null) }
    }, onRelease = { it.destroy() }, modifier = modifier)
}

/** The Filter modal (MAIL_PLAN §9): quick filters on top, sorting and grouping below, kept for this folder when asked. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable private fun FilterSheet(v: MailView, accounts: List<MailAccount>, keep: Boolean, set: (MailView) -> Unit, setKeep: (Boolean) -> Unit, dismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = SheetColor, contentColor = islandContentColor()) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_filter), null); Text("  " + stringResource(R.string.mail_filter), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                if (v != MailView()) TextButton(onClick = { set(MailView()) }) { Text(stringResource(R.string.common_clear), color = MAIL_COLOR) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(stringResource(R.string.mail_unread), v.unread) { set(v.copy(unread = !v.unread)) }
                FilterChip("♥ " + stringResource(R.string.mail_favourites), v.favorites) { set(v.copy(favorites = !v.favorites)) }
                FilterChip("📎 " + stringResource(R.string.mail_attachments), v.attachments) { set(v.copy(attachments = !v.attachments)) }
                FilterChip("● " + stringResource(R.string.mail_server_only), v.orange) { set(v.copy(orange = !v.orange)) }
                accounts.forEach { a -> FilterChip(a.name, a.id in v.accounts) { set(v.copy(accounts = if (a.id in v.accounts) v.accounts - a.id else v.accounts + a.id)) } }
            }
            Text(stringResource(R.string.mail_sort), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("date" to R.string.mail_sort_date, "from" to R.string.mail_sort_from, "subject" to R.string.mail_sort_subject, "size" to R.string.mail_sort_size, "account" to R.string.mail_sort_account)
                    .forEach { (k, s) -> FilterChip(stringResource(s), v.sort == k) { set(v.copy(sort = k)) } }
                FilterChip(stringResource(if (v.descending) R.string.mail_descending else R.string.mail_ascending), true) { set(v.copy(descending = !v.descending)) }
            }
            Text(stringResource(R.string.mail_group), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("day" to R.string.mail_group_day, "from" to R.string.mail_sort_from, "account" to R.string.mail_sort_account, "none" to R.string.mail_group_none)
                    .forEach { (k, s) -> FilterChip(stringResource(s), v.group == k) { set(v.copy(group = k)) } }
            }
            SheetSwitch(stringResource(R.string.mail_unread_first), v.unreadFirst) { set(v.copy(unreadFirst = it)) }
            SheetSwitch(stringResource(R.string.mail_favourites_first), v.favoritesFirst) { set(v.copy(favoritesFirst = it)) }
            SheetSwitch(stringResource(R.string.mail_attachments_first), v.attachmentsFirst) { set(v.copy(attachmentsFirst = it)) }
            SheetSwitch(stringResource(R.string.mail_keep_for_folder), keep, setKeep)
        }
    }
}

@Composable private fun FilterChip(text: String, on: Boolean, click: () -> Unit) = Surface(
    color = if (on) MAIL_COLOR else Color.White.copy(alpha = 0.10f), contentColor = islandContentColor(), shape = CircleShape, modifier = Modifier.clickable(onClick = click),
) { Text(text, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1) }

@Composable private fun SheetSwitch(text: String, on: Boolean, set: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth().clickable { set(!on) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f)); Switch(checked = on, onCheckedChange = set)
    }
