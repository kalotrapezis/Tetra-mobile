package com.kalotrapezis.drive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle

/** The tab the Timer opened on last, while Tetra runs. */
internal var lastTab = 0
/** Under the header. */
internal val TOP = 84.dp
/** Above the island at the bottom. */
internal val BOTTOM = 96.dp

/** Timers · Alarms · Stopwatch, an island under the header. */
/** Countdown, Alarms and Stopwatch: the bottom island, as the other applets' (no handle: the Timer has no drawer). */
@Composable internal fun TimerTabs(tab: Int, choose: (Int) -> Unit, modifier: Modifier = Modifier) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = islandColor(), contentColor = islandContentColor(), modifier = modifier) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(R.string.timer_tab_timers to R.drawable.ic_hourglass, R.string.timer_tab_alarms to R.drawable.ic_alarm, R.string.stopwatch to R.drawable.ic_timer)
                .forEachIndexed { i, (label, icon) -> IslandNavigationItem(stringResource(label), i == tab, icon) { choose(i) } }
        }
    }
}

/** The alarms, one card each: the time, its days and label, a switch and the trash; a tap edits it; New alarm on top. */
@Composable internal fun AlarmsPane(wide: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val zone = ZoneId.systemDefault()
    var alarms by remember { mutableStateOf(emptyList<AlarmRecord>()) }
    var now by remember { mutableStateOf(Instant.now()) }
    var editing by remember { mutableStateOf<AlarmRecord?>(null) }
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            alarms = withContext(Dispatchers.IO) { runCatching { TimerAlarmFiles.alarms(TimerControl.dir()) }.getOrDefault(emptyList()) }
            now = Instant.now()
            delay(1000)
        }
    }
    fun io(work: () -> Unit) = scope.launch(Dispatchers.IO) { runCatching(work).onFailure { WriterLog.e("Alarms: change failed", it) } }
    val w = rememberUiWords()
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = TOP),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = if (wide) 640.dp else 520.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val next = alarms.mapNotNull { it.next(now, zone) }.minOrNull()
                Text(next?.let { stringResource(R.string.alarm_in, durationText(w, (it.epochSecond - now.epochSecond + 59) / 60 * 60)) } ?: "",
                    Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(onClick = { adding = true }) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = null); Text(" " + stringResource(R.string.alarm_new), maxLines = 1)
                }
            }
            if (alarms.isEmpty()) Text(stringResource(R.string.alarm_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
            alarms.forEach { a ->
                val ringing = a.ringing(now, zone)
                Surface(onClick = { editing = a }, shape = MaterialTheme.shapes.extraLarge, color = if (ringing != null) TIMER_COLOR else MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("%02d:%02d".format(a.time.hour, a.time.minute), style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace,
                                color = if (a.on(now, zone)) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                            // skipped: the next ring already answered (Skip here, in the 30-minute notice or on another device)
                            val skipped = a.repeats && a.state == AlarmRecord.ON && ringing == null && a.snooze == null && a.answered?.isAfter(now) == true
                            Text(listOf(daysText(a), a.label, if (skipped) stringResource(R.string.alarm_skipped) else "").filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (a.repeats && a.state == AlarmRecord.ON && ringing == null)
                                TextButton(onClick = { io { TimerControl.alarmChange(context, a.id, if (skipped) TimerService.ALARM_ON else TimerService.ALARM_SKIP) } },
                                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) {
                                    Icon(painterResource(R.drawable.ic_skip_next), contentDescription = null, modifier = Modifier.size(18.dp))
                                    Text(" " + stringResource(if (skipped) R.string.alarm_unskip else R.string.alarm_skip_next), maxLines = 1)
                                }
                        }
                        if (ringing != null) {
                            TextButton(onClick = { io { TimerControl.alarmChange(context, a.id, TimerService.ALARM_SNOOZE) } }) { Text(stringResource(R.string.alarm_snooze)) }
                            Button(onClick = { io { TimerControl.alarmChange(context, a.id, TimerService.ALARM_DISMISS) } }) { Text(stringResource(R.string.alarm_dismiss)) }
                        } else {
                            Switch(checked = a.on(now, zone), onCheckedChange = { on -> io { TimerControl.alarmChange(context, a.id, if (on) TimerService.ALARM_ON else TimerService.ALARM_OFF) } })
                            IconButton(onClick = { io { TimerControl.alarmChange(context, a.id, TimerService.ALARM_DELETE) } }) {
                                Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.common_delete), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.navigationBarsPadding().height(BOTTOM))
        }
    }
    if (adding || editing != null) AlarmDialog(editing, close = { adding = false; editing = null }) { time, days, label ->
        val e = editing
        io { if (e == null) TimerControl.alarmAdd(context, time, days, label) else TimerControl.alarmEdit(context, e, time, days, label) }
        adding = false; editing = null
    }
}

/** "Once", "Every day", or the days: "Mon, Wed, Fri" in the phone's language. */
@Composable private fun daysText(a: AlarmRecord): String = when {
    !a.repeats -> stringResource(R.string.alarm_once)
    a.days.size == 7 -> stringResource(R.string.alarm_every_day)
    else -> LocalContext.current.uiLocale().let { l -> AlarmRecord.WEEK.filter { it in a.days }.joinToString(", ") { it.getDisplayName(TextStyle.SHORT, l) } }
}

/**
 * A new alarm or an edit: the label on top, the time in a bubble with Set time (the clock in its modal, as a timer's), the
 * days (none: once). Many fields, so a dialog.
 */
@Composable private fun AlarmDialog(a: AlarmRecord?, close: () -> Unit, save: (LocalTime, Set<DayOfWeek>, String) -> Unit) {
    var time by remember { mutableStateOf(a?.time ?: LocalTime.of(7, 0)) }
    var dial by remember { mutableStateOf(false) }
    var days by remember { mutableStateOf(a?.days ?: emptySet()) }
    var label by remember { mutableStateOf(a?.label ?: "") }
    AlertDialog(onDismissRequest = close,
        confirmButton = { Button(onClick = { save(time, days, label.trim()) }) { Text(stringResource(R.string.common_save)) } },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.common_cancel)) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(label, { label = it }, label = { Text(stringResource(R.string.alarm_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(onClick = { dial = true }, shape = CircleShape, color = TIMER_COLOR.copy(alpha = 0.18f)) {
                        Text("%02d:%02d".format(time.hour, time.minute), Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.displaySmall, fontFamily = FontFamily.Monospace, maxLines = 1)
                    }
                    FilledTonalButton(onClick = { dial = true }) {
                        Icon(painterResource(R.drawable.ic_schedule), contentDescription = null); Text(" " + stringResource(R.string.alarm_set_time), maxLines = 1)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    AlarmRecord.WEEK.forEach { d ->
                        val on = d in days
                        Surface(onClick = { days = if (on) days - d else days + d }, shape = CircleShape, modifier = Modifier.size(36.dp),
                            color = if (on) TIMER_COLOR else MaterialTheme.colorScheme.surfaceVariant, contentColor = if (on) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSurfaceVariant) {
                            Box(contentAlignment = Alignment.Center) { Text(d.getDisplayName(TextStyle.NARROW, LocalContext.current.uiLocale())) }
                        }
                    }
                }
                Text(if (days.isEmpty()) stringResource(R.string.alarm_once) else if (days.size == 7) stringResource(R.string.alarm_every_day) else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        })
    if (dial) DialDialog(time.hour, time.minute, close = { dial = false }) { h, m -> time = LocalTime.of(h, m); dial = false }
}

/** The stopwatch, the same on every device: the time to a tenth, Start/Pause, Lap, Reset, the laps under them. */
@Composable internal fun StopwatchPane() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var watch by remember { mutableStateOf(StopwatchRecord()) }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        var n = 0
        while (true) {
            if (n++ % 5 == 0) watch = withContext(Dispatchers.IO) { runCatching { TimerAlarmFiles.stopwatch(TimerControl.dir()) }.getOrDefault(watch) }
            now = Instant.now()
            delay(100) // the tenths move; the file is read twice a second
        }
    }
    fun act(what: String) = scope.launch(Dispatchers.IO) {
        runCatching { TimerControl.stopwatch(context, what) }.onFailure { WriterLog.e("Stopwatch: $what failed", it) }
        watch = runCatching { TimerAlarmFiles.stopwatch(TimerControl.dir()) }.getOrDefault(watch)
    }
    val counting = watch.state == StopwatchRecord.COUNTING
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = TOP + 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(stopwatchText(watch.elapsedMs(now)), style = MaterialTheme.typography.displayLarge, fontFamily = FontFamily.Monospace, maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (counting) Button(onClick = { act(TimerService.WATCH_HOLD) }, modifier = Modifier.widthIn(min = 120.dp)) {
                Icon(painterResource(R.drawable.ic_pause), contentDescription = null); Text("  " + stringResource(R.string.common_pause), maxLines = 1)
            } else Button(onClick = { act(TimerService.WATCH_START) }, modifier = Modifier.widthIn(min = 120.dp)) {
                Icon(painterResource(R.drawable.ic_play), contentDescription = null)
                Text("  " + stringResource(if (watch.state == StopwatchRecord.HELD) R.string.common_resume else R.string.common_start), maxLines = 1)
            }
            OutlinedButton(onClick = { act(TimerService.WATCH_LAP) }, enabled = counting) { Text(stringResource(R.string.stopwatch_lap), maxLines = 1) }
            OutlinedButton(onClick = { act(TimerService.WATCH_RESET) }, enabled = watch.state == StopwatchRecord.HELD) { Text(stringResource(R.string.stopwatch_reset), maxLines = 1) }
        }
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            watch.laps.withIndex().reversed().forEach { (i, t) ->
                val split = t - (watch.laps.getOrNull(i - 1) ?: 0.0)
                Row(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.stopwatch_lap_n, i + 1), Modifier.weight(1f))
                    Text(stopwatchText((split * 1000).toLong()), Modifier.weight(1f), fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stopwatchText((t * 1000).toLong()), fontFamily = FontFamily.Monospace)
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(BOTTOM))
    }
}
