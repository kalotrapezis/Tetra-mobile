package com.kalotrapezis.drive

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Home's live cards (the user, 8 Nov: "cards with active content… today's pills and click I took it, the timer with the
 * favourite timers, the next events, unread mail, a mic for the assistant and the daily report"). What each shows is
 * chosen by the plain functions here (HomeCardsTest); the cards read the same synced files as their apps, on IO, and
 * look again only now and then while on screen.
 */
internal object HomeCards {
    /** A Timer tile: a pinned timer (restarted by [id]) or one of the four times that start at a tap. */
    data class TimerSlot(val id: String?, val label: String, val minutes: Int?)

    /** The user's pinned timers first, then the four times as before, four tiles in all. */
    fun timerSlots(pinned: List<Pair<String, String>>, presets: List<Int> = listOf(6, 15, 20, 30), minutes: (Int) -> String): List<TimerSlot> =
        (pinned.take(4).map { (id, label) -> TimerSlot(id, label, null) } + presets.map { TimerSlot(null, minutes(it), it) }).take(4)

    /** Unread Inbox mail, most urgent first (ai/urgency.jsonl), then newest; the count of all of them. */
    fun unread(entries: List<MailEntry>, urgency: Map<String, Int>, n: Int = 3): Pair<Int, List<MailEntry>> {
        val all = entries.filter { it.folder == "Inbox" && !it.seen && !it.gone }
        return all.size to all.sortedWith(compareByDescending<MailEntry> { urgency[it.id] ?: 0 }.thenByDescending { it.date }).take(n)
    }

    /** The time to answer with «Το πήρα»: the earliest dose still waiting or missed today, or null. */
    fun toTake(doses: List<Dose>): Instant? = doses.filter { it.state == Dose.State.Waiting || it.state == Dose.State.Missed }.minOfOrNull { it.due }

    /** A dose's line: its time and, unless reminders are private, the medicine × amount. */
    fun doseLine(d: Dose, time: String, private: Boolean) = if (private) time else "$time  ${d.med.title} × ${Medicine.amount(d.med.dose)}"

    /** The urgency dot's colour: none, low, needs attention, urgent. */
    fun urgencyColor(score: Int): Color? = when (score) { 3 -> Color(0xFFE53935); 2 -> Color(0xFFFB8C00); 1 -> Color(0xFFFDD835); else -> null }
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm")

/** The right half of a group: a light panel as tall as the art card beside it. */
@Composable private fun HomePanel(modifier: Modifier, content: @Composable () -> Unit) = Surface(
    shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f), modifier = modifier.height(156.dp),
) { Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() } }

@Composable private fun PanelLine(text: String, modifier: Modifier = Modifier, bold: Boolean = false) =
    Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = if (bold) FontWeight.SemiBold else null, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)

/** Pills: today's doses, and «Το πήρα» for the earliest one still waiting (every dose of that time, as the notification's button). */
@Composable internal fun HomePillsGroup(open: () -> Unit) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val changed = PillsAlarms.changed.intValue
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); tick++ } } // waiting turns missed with time alone
    val doses by produceState(emptyList<Dose>(), changed, tick) {
        value = withContext(Dispatchers.IO) { runCatching { val dir = Pills.phoneDir(); val log = Pills.log(dir); Pills.today(Pills.medicines(dir, log), log, Instant.now(), ZoneId.systemDefault()) }.getOrDefault(emptyList()) }
    }
    val private = remember(changed) { PillsAlarms.private(c) }
    val zone = ZoneId.systemDefault()
    HomeGroup(PILLS_COLOR) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HomeArtCard(stringResource(R.string.app_pills), R.drawable.pills_art, open, Modifier.weight(1f), artSize = 76)
            HomePanel(Modifier.weight(1f).clickable(onClick = open)) {
                val due = HomeCards.toTake(doses)
                when {
                    doses.isEmpty() -> PanelLine(stringResource(R.string.home_pills_none))
                    else -> doses.sortedBy { it.due }.take(if (due != null) 3 else 4).forEach { d ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(painterResource(when (d.state) { Dose.State.Taken -> R.drawable.ic_check; Dose.State.Skipped -> R.drawable.ic_block; Dose.State.Missed -> R.drawable.ic_close; else -> R.drawable.ic_schedule }),
                                contentDescription = null, modifier = Modifier.size(14.dp))
                            PanelLine(HomeCards.doseLine(d, TIME.format(d.due.atZone(zone)), private), bold = d.state == Dose.State.Waiting || d.state == Dose.State.Missed)
                        }
                    }
                }
                if (due != null) FilledTonalButton(onClick = {
                    scope.launch(Dispatchers.IO) { runCatching { PillsAlarms.answer(c, PillsAlarms.TAKE, due) }.onFailure { WriterLog.e("Home: pills not taken", it) } }
                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pills_taken), maxLines = 1) }
                else if (doses.isNotEmpty()) PanelLine(stringResource(R.string.home_pills_done), bold = true)
            }
        }
    }
}

/** Mail: how many are unread, and the three most urgent (the desktop's ai/urgency.jsonl), each opening its mail. */
@Composable internal fun HomeMailGroup(open: () -> Unit) {
    val c = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(120_000); tick++ } }
    val shown by produceState(0 to emptyList<Pair<MailEntry, Int>>(), tick) {
        value = withContext(Dispatchers.IO) { runCatching {
            val root = mailRoot()
            val urgency = MailAi.urgency(root).mapValues { it.value.score }
            HomeCards.unread(MailFiles.index(root, MailEngine.device()), urgency).let { (n, list) -> n to list.map { it to (urgency[it.id] ?: 0) } }
        }.getOrDefault(0 to emptyList()) }
    }
    HomeGroup(Category.Mail.color) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HomeArtCard(stringResource(R.string.app_mail), R.drawable.mail_art, open, Modifier.weight(1f), artSize = 76)
            HomePanel(Modifier.weight(1f)) {
                val (n, list) = shown
                PanelLine(if (n == 0) stringResource(R.string.home_mail_none) else stringResource(R.string.home_mail_unread, n), Modifier.clickable(onClick = open), bold = true)
                list.forEach { (e, score) ->
                    Row(Modifier.fillMaxWidth().clickable { openApplet(c, "mail:${e.id}") }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(8.dp).background(HomeCards.urgencyColor(score) ?: MaterialTheme.colorScheme.primary, CircleShape))
                        Column {
                            PanelLine(e.from.ifBlank { e.fromAddress }, bold = true)
                            PanelLine(e.subject)
                        }
                    }
                }
            }
        }
    }
}

/** Calendar: the next three events within two weeks; none, then the four «New» tiles as before. */
@Composable internal fun HomeCalendarNext(modifier: Modifier, open: () -> Unit, empty: @Composable (Modifier) -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(300_000); tick++ } }
    val next by produceState<List<Occurrence>?>(null, tick) {
        value = withContext(Dispatchers.IO) { runCatching {
            val now = Instant.now()
            CalendarFiles.occurrences(CalendarFiles.calendars(calendarRoot()), now, now.plusSeconds(14 * 86_400L)).filter { it.end.toInstant() > now }.take(3)
        }.getOrDefault(emptyList()) }
    }
    val list = next ?: return
    if (list.isEmpty()) { empty(modifier); return }
    val day = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
    HomePanel(modifier.clickable(onClick = open)) {
        list.forEach { o ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(8.dp).background(Color(o.calendar.color), CircleShape))
                Column {
                    PanelLine(if (o.allDay) day.format(o.start) else "${day.format(o.start)} ${TIME.format(o.start)}", bold = true)
                    PanelLine(o.event.title)
                }
            }
        }
    }
}

/** AI Assist on Home, one row: the latest brief's title (its page opens), and a mic that starts listening at once. */
@Composable internal fun HomeAssistantCard(openChat: () -> Unit) {
    val c = LocalContext.current
    val brief by produceState<Pair<String, File>?>(null) {
        value = withContext(Dispatchers.IO) { runCatching {
            val o = JSONObject(File(AssistantBriefs.dir(), "latest.json").readText())
            val f = File(AssistantBriefs.dir(), File(o.optString("file")).name).takeIf { it.isFile } ?: AssistantBriefs.pages().first()
            o.optString("title").ifBlank { AssistantBriefs.title(f) } to f
        }.getOrNull() }
    }
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().height(78.dp).clickable(onClick = openChat)) {
        Row(Modifier.padding(start = 18.dp, end = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            ArtImage(R.drawable.assistant_art, 44.dp)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.app_assistant), style = MaterialTheme.typography.titleMedium)
                val b = brief
                if (b != null) Text(b.first, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { c.startActivity(Intent(c, BriefActivity::class.java).putExtra("file", b.second.absolutePath)) })
                else Text(stringResource(R.string.assistant_home_detail), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { talk(c) }, modifier = Modifier.size(56.dp)) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_mic), contentDescription = stringResource(R.string.assistant_tap_to_talk), tint = MaterialTheme.colorScheme.onPrimary) }
                }
            }
        }
    }
}

/** The assist sheet: it listens as soon as it opens, the same as the assist gesture. */
private fun talk(c: Context) = c.startActivity(Intent(c, AssistantSheetActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

/** A share of a plan as a ring (Data's PlanHead's two arcs): red past the whole. */
@Composable internal fun HomeRing(share: Float, color: Color, modifier: Modifier) = Canvas(modifier) {
    val w = 4.dp.toPx(); val inset = w / 2; val arc = Size(size.width - w, size.height - w)
    drawArc(color.copy(alpha = 0.25f), 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(w))
    drawArc(if (share > 1f) Color(0xFFE53935) else color, -90f, 360f * share.coerceIn(0f, 1f), false, Offset(inset, inset), arc, style = Stroke(w, cap = StrokeCap.Round))
}
