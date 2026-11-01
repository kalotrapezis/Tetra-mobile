package com.kalotrapezis.drive

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.DateFormat
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Date
import org.json.JSONArray
import org.json.JSONObject

/*
 * AI Assist's places besides the chat (the user, 1 Nov 2026: "where is the history, the chats, the memory, the scheduled
 * actions"): History (this phone's own chats), Personal details (AssistantMemory, shared), Scheduled (AssistantSchedules,
 * run by the computer) with the reports they made. And the bubbles, each in its applet's colour.
 */

internal enum class AssistTab(val label: Int) { Chat(R.string.assistant_tab_chat), History(R.string.assistant_tab_history), Memory(R.string.ai_memory), Scheduled(R.string.assistant_tab_schedules) }

/** Tetra's applet colours (applet_colors.xml): what a bubble is about. */
internal object AppColor {
    val photos = Color(0xFF95DF6B); val calendar = Color(0xFFF41879); val mail = Color(0xFFFD0DA4)
    val notes = Color(0xFFFED00A); val timer = Color(0xFF04D5CC); val assistant = ASSISTANT_COLOR
}

/** A bubble tinted with its applet's colour, readable on light and dark. */
@Composable
internal fun AppChip(text: String, color: Color, onClick: () -> Unit) {
    val surface = MaterialTheme.colorScheme.surface
    Surface(onClick = onClick, shape = CircleShape, color = color.copy(alpha = 0.22f).compositeOver(surface), contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, color.copy(alpha = 0.6f))) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(10.dp).background(color, CircleShape))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppChips(chips: List<Triple<String, Color, () -> Unit>>, modifier: Modifier = Modifier, center: Boolean = false) =
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp, if (center) Alignment.CenterHorizontally else Alignment.Start), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        chips.forEach { (t, c, go) -> AppChip(t, c, go) }
    }

private val dateTime: DateFormat get() = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

@Composable
internal fun HistoryTab(open: (AssistantChats.Saved) -> Unit) {
    val c = LocalContext.current
    var chats by remember { mutableStateOf(AssistantChats.list(c)) }
    if (chats.isEmpty()) { Empty(stringResource(R.string.assistant_no_chats)); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(chats, key = { it.id }) { s ->
            Card(Modifier.fillMaxWidth().clickable { open(s) }) {
                Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(dateTime.format(Date(s.at)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { AssistantChats.delete(c, s.id); chats = AssistantChats.list(c) }) { Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.common_delete)) }
                }
            }
        }
    }
}

@Composable
internal fun MemoryTab() {
    val tetra = remember { driveRoot() }
    val device = remember { TimerControl.device() }
    var facts by remember { mutableStateOf(AssistantMemory.list(tetra)) }
    var typed by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<AssistantMemory.Fact?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    fun reload() { facts = AssistantMemory.list(tetra) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(stringResource(R.string.ai_memory_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(typed, { typed = it }, Modifier.weight(1f), placeholder = { Text(stringResource(R.string.ai_memory_hint)) }, shape = RoundedCornerShape(16.dp))
                FilledTonalButton(onClick = { runCatching { AssistantMemory.add(tetra, typed, device) }.onSuccess { typed = ""; problem = null; reload() }.onFailure { problem = it.message } }, enabled = typed.isNotBlank()) { Text(stringResource(R.string.common_add)) }
            }
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        items(facts, key = { it.id }) { f ->
            Card(Modifier.fillMaxWidth().clickable { editing = f }) {
                Row(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(f.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    IconButton(onClick = { AssistantMemory.change(tetra, f.id, "", device); reload() }) { Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.common_delete)) }
                }
            }
        }
    }
    editing?.let { f ->
        var text by remember(f.id) { mutableStateOf(f.text) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(stringResource(R.string.common_edit)) },
            text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth()) },
            confirmButton = { TextButton({ AssistantMemory.change(tetra, f.id, text, device); editing = null; reload() }) { Text(stringResource(R.string.common_save)) } },
            dismissButton = { TextButton({ editing = null }) { Text(stringResource(R.string.common_cancel)) } })
    }
}

private val REPEAT_LABEL = mapOf("once" to R.string.assistant_sched_once, "daily" to R.string.assistant_sched_daily, "weekly" to R.string.assistant_sched_weekly,
    "monthly" to R.string.assistant_sched_monthly, "yearly" to R.string.assistant_sched_yearly)

private fun weekday(c: Context, n: Int) = DayOfWeek.of(if (n == 0) 7 else n).getDisplayName(TextStyle.SHORT, c.uiLocale())

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScheduledTab() {
    val c = LocalContext.current
    val tetra = remember { driveRoot() }
    var list by remember { mutableStateOf(AssistantSchedules.list(tetra)) }
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var said by remember { mutableStateOf<String?>(null) }
    var pages by remember { mutableStateOf(AssistantBriefs.pages()) }
    fun reload() { list = AssistantSchedules.list(tetra); pages = AssistantBriefs.pages() }
    fun sync() = ScopedSync.soon(c, "assistant", 0, orWhole = true)
    fun change(id: String, x: JSONObject) = runCatching { AssistantSchedules.change(tetra, id, x) }.onSuccess { sync(); reload() }.onFailure { said = it.message }
    val title = { s: JSONObject -> if (s.optString("kind") == "brief") c.getString(R.string.assistant_briefs_channel) else s.optString("title") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.assistant_sched_how), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = { editing = JSONObject().put("time", "08:00").put("repeat", "daily").put("days", JSONArray(listOf(1))) }) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.assistant_sched_new))
                }
            }
            said?.let { Text(it, Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.primary) }
        }
        items(list, key = { it.optString("id") }) { s ->
            val id = s.optString("id")
            val how = c.getString(REPEAT_LABEL[s.optString("repeat")] ?: R.string.assistant_sched_daily) + when (s.optString("repeat")) {
                "weekly" -> s.optJSONArray("days")?.let { a -> " (" + (0 until a.length()).joinToString(", ") { weekday(c, a.optInt(it)) } + ")" }.orEmpty()
                "daily" -> ""
                else -> s.optString("date").takeIf { !s.isNull("date") && it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            } + " · " + s.optString("time")
            val state = if (s.optBoolean("paused")) c.getString(R.string.assistant_sched_paused)
                else AssistantSchedules.due(s)?.let { c.getString(R.string.assistant_sched_next, dateTime.format(Date(it))) } ?: c.getString(R.string.common_done)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(if (s.optString("kind") == "brief") AppColor.calendar else AppColor.mail, CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(title(s), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text("$how · $state", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                        TextButton({ change(id, JSONObject().put("runNow", true)).onSuccess { said = c.getString(R.string.assistant_sched_asked) } }) { Text(stringResource(R.string.assistant_sched_run_now)) }
                        TextButton({ change(id, JSONObject().put("paused", !s.optBoolean("paused"))) }) { Text(stringResource(if (s.optBoolean("paused")) R.string.common_resume else R.string.common_pause)) }
                        TextButton({ editing = JSONObject(s.toString()) }) { Text(stringResource(R.string.common_edit)) }
                        if (s.optString("kind") != "brief") TextButton({ AssistantSchedules.remove(tetra, id); sync(); reload() }) { Text(stringResource(R.string.common_delete)) }
                    }
                }
            }
        }
        item { Text(stringResource(R.string.assistant_briefs), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium) }
        if (pages.isEmpty()) item { Text(stringResource(R.string.assistant_briefs_none), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(pages, key = { it.name }) { f ->
            Card(Modifier.fillMaxWidth().clickable { c.startActivity(Intent(c, BriefActivity::class.java).putExtra("file", f.absolutePath)) }) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(AssistantBriefs.title(f), style = MaterialTheme.typography.titleSmall)
                    Text(DateFormat.getDateInstance(DateFormat.FULL).format(Date(f.lastModified())), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    editing?.let { e -> ScheduleDialog(e, close = { editing = null }) { x, sections ->
        runCatching {
            if (e.has("id")) { sections?.let { AssistantSchedules.setSections(tetra, it) }; AssistantSchedules.change(tetra, e.getString("id"), x) } else AssistantSchedules.add(tetra, x)
        }.onSuccess { editing = null; said = null; sync(); reload() }.onFailure { said = it.message }
    } }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleDialog(s: JSONObject, close: () -> Unit, save: (JSONObject, JSONObject?) -> Unit) {
    val c = LocalContext.current
    val brief = s.optString("kind") == "brief"
    var prompt by remember { mutableStateOf(s.optString("prompt")) }
    var time by remember { mutableStateOf(s.optString("time", "08:00")) }
    var repeat by remember { mutableStateOf(s.optString("repeat", "daily")) }
    var days by remember { mutableStateOf(s.optJSONArray("days")?.let { a -> (0 until a.length()).map { a.optInt(it) }.toSet() } ?: setOf(1)) }
    var date by remember { mutableStateOf(s.optString("date").takeIf { !s.isNull("date") }.orEmpty()) }
    val tetra = remember { driveRoot() }
    val now = remember { AssistantSchedules.read(tetra).optJSONObject("brief")?.optJSONObject("sections") ?: JSONObject() }
    val sections = remember { mutableStateMapOf<String, Boolean>().apply { listOf("calendar", "mail", "weather", "notes").forEach { put(it, now.optBoolean(it, true)) } } }
    var menu by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = close,
        title = { Text(if (brief) stringResource(R.string.assistant_briefs_channel) else if (s.has("id")) stringResource(R.string.common_edit) else stringResource(R.string.assistant_sched_new)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!brief) OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.assistant_sched_prompt)) }, minLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(time, { time = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.assistant_sched_time)) }, singleLine = true)
                    ExposedDropdownMenuBox(menu, { menu = it }, Modifier.weight(1.4f)) {
                        OutlinedTextField(stringResource(REPEAT_LABEL[repeat]!!), {}, Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable), readOnly = true, label = { Text(stringResource(R.string.assistant_sched_repeat)) })
                        ExposedDropdownMenu(menu, { menu = false }) { AssistantSchedules.REPEATS.forEach { r -> DropdownMenuItem({ Text(stringResource(REPEAT_LABEL[r]!!)) }, { repeat = r; menu = false }) } }
                    }
                }
                if (repeat in listOf("once", "monthly", "yearly")) OutlinedTextField(date, { date = it }, Modifier.fillMaxWidth(), label = { Text("YYYY-MM-DD") }, singleLine = true)
                if (repeat == "weekly") FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1, 2, 3, 4, 5, 6, 0).forEach { d -> FilterChip(d in days, { days = if (d in days) days - d else days + d }, { Text(weekday(c, d)) }) }
                }
                if (brief) {
                    Text(stringResource(R.string.assistant_brief_sections), style = MaterialTheme.typography.titleSmall)
                    listOf("calendar" to R.string.app_calendar, "mail" to R.string.app_mail, "weather" to R.string.assistant_brief_weather, "notes" to R.string.assistant_brief_lists).forEach { (k, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(sections[k] == true, { sections[k] = it }); Text(stringResource(label)) }
                    }
                }
            }
        },
        confirmButton = { TextButton({
            val x = JSONObject().put("time", time.trim()).put("repeat", repeat).put("days", JSONArray(days.sorted())).put("date", date.trim().ifEmpty { JSONObject.NULL })
            if (!brief) x.put("prompt", prompt)
            save(x, if (brief) JSONObject(sections.toMap() as Map<*, *>) else null)
        }, enabled = brief || prompt.isNotBlank()) { Text(stringResource(R.string.common_save)) } },
        dismissButton = { TextButton(close) { Text(stringResource(R.string.common_cancel)) } })
}

@Composable
private fun Empty(text: String) = Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
