package com.kalotrapezis.drive

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.floor
import kotlin.math.roundToInt

/*
 * Pills (the user, 7 Nov 2026: "I often forget the evening pill"): medicines, their times, the stock in hand, refill
 * reminders and a history. Files in Tetra/.System/Pills/Data, on this phone only (synced, a tablet would ring them too):
 *
 *   <id>.json   a medicine            {"id":"pill-k3f9a2","name":"Depon","strength":"500 mg","dose":1,"times":["21:00"],
 *                                      "days":[],"until":null,"perBox":20,"stock":34.5,"refillDays":7,"refillTimes":3,
 *                                      "archived":false,"refillAsked":0,"refillOn":null}
 *   <id>.jpg    its photo, the emblem
 *   log.jsonl   one line per event, never rewritten: {"at":"…Z","med":"pill-…","kind":"taken","due":"…Z","amount":1,"stock":33.5}
 *
 * A dose is one medicine at one time of one day ([Dose.due], an instant). Its state is the last taken/skipped/undo
 * event with that due; none, and it is waiting, then missed once its last reminder has gone by. Taken takes the dose
 * from the stock, undo gives it back; so pressing Taken twice (the row and the notification) cannot count twice.
 */

internal data class Medicine(
    val id: String,
    val name: String,
    val strength: String = "",
    /** Pills each time; halves allowed. */
    val dose: Double = 1.0,
    val times: List<LocalTime> = emptyList(),
    /** Empty: every day. */
    val days: Set<DayOfWeek> = emptySet(),
    /** The last day of a course (antibiotics); none: for good. */
    val until: LocalDate? = null,
    val perBox: Int = 0,
    val stock: Double = 0.0,
    /** A refill reminder when the stock lasts this many days or fewer… */
    val refillDays: Int = 7,
    /** …this many times, once a day, until a box is added. */
    val refillTimes: Int = 3,
    val archived: Boolean = false,
    val refillAsked: Int = 0,
    val refillOn: LocalDate? = null,
    /** When it was added: no dose before it is due, so a new medicine is not "missed" on its first day. */
    val since: Instant? = null,
    /** Its pills are counted (a box or a count was ever given); else no stock is shown and no refill asked. */
    val counted: Boolean = false,
    /** How the pill looks, drawn as its emblem when there is no photo: [SHAPES], its colour, a capsule's other half. */
    val shape: String = "round",
    val color: Int = WHITE,
    val color2: Int = WHITE,
    /** "", or one of [FOODS]: said under the name and in the reminder. */
    val food: String = "",
    /** Every [every] days from the day it was added (1: by [days], or every day). */
    val every: Int = 1,
) {
    val title get() = if (strength.isBlank()) name else "$name $strength"

    fun takenOn(day: LocalDate, zone: ZoneId = ZoneId.systemDefault()) = !archived && (until == null || !day.isAfter(until)) && when {
        every > 1 -> java.time.temporal.ChronoUnit.DAYS.between(since?.atZone(zone)?.toLocalDate() ?: day, day).let { it >= 0 && it % every == 0L }
        else -> days.isEmpty() || day.dayOfWeek in days
    }

    /** Pills a day, on average. */
    fun perDay(): Double = dose * times.size * (if (every > 1) 1.0 / every else (if (days.isEmpty()) 7 else days.size) / 7.0)

    /** Whole days the stock lasts; null when nothing is taken by the clock. */
    fun daysLeft(): Int? = perDay().takeIf { it > 0 && counted }?.let { floor(stock / it + 1e-9).toInt() }

    fun lastDay(today: LocalDate): LocalDate? = daysLeft()?.let { today.plusDays(it.toLong()) }

    fun low() = !archived && daysLeft()?.let { it <= refillDays } == true

    fun json(): String = JSONObject().put("id", id).put("name", name).put("strength", strength).put("dose", dose)
        .put("times", JSONArray(times.sorted().map { "%02d:%02d".format(it.hour, it.minute) }))
        .put("days", JSONArray(AlarmRecord.WEEK.filter { it in days }.map { AlarmRecord.DAY_CODES[it.ordinal] }))
        .put("until", until?.toString() ?: JSONObject.NULL).put("perBox", perBox).put("stock", stock).put("refillDays", refillDays)
        .put("refillTimes", refillTimes).put("archived", archived).put("refillAsked", refillAsked)
        .put("refillOn", refillOn?.toString() ?: JSONObject.NULL).put("since", since?.toString() ?: JSONObject.NULL).put("counted", counted)
        .put("shape", shape).put("color", "#%08X".format(color)).put("color2", "#%08X".format(color2)).put("food", food).put("every", every).toString(1) + "\n"

    companion object {
        fun parse(text: String): Medicine? = runCatching {
            val o = JSONObject(text)
            fun date(k: String) = o.optString(k, "").takeIf { it.isNotEmpty() && it != "null" }?.let { LocalDate.parse(it) }
            val times = o.optJSONArray("times")?.let { a -> (0 until a.length()).mapNotNull { runCatching { LocalTime.parse(a.getString(it).trim().padStart(5, '0')) }.getOrNull() } }.orEmpty()
            val days = o.optJSONArray("days")?.let { a -> (0 until a.length()).mapNotNull { AlarmRecord.DAY_CODES.indexOf(a.optString(it).uppercase().take(2)).takeIf { i -> i >= 0 }?.let { i -> AlarmRecord.WEEK[i] } } }.orEmpty().toSet()
            Medicine(o.getString("id"), o.getString("name"), o.optString("strength"), o.optDouble("dose", 1.0).takeIf { !it.isNaN() } ?: 1.0,
                times.distinct().sorted(), days, date("until"), o.optInt("perBox"), o.optDouble("stock", 0.0).takeIf { !it.isNaN() } ?: 0.0,
                o.optInt("refillDays", 7), o.optInt("refillTimes", 3), o.optBoolean("archived"), o.optInt("refillAsked"), date("refillOn"),
                o.optString("since", "").takeIf { it.isNotEmpty() && it != "null" }?.let { Instant.parse(it) },
                if (o.has("counted")) o.optBoolean("counted") else o.optInt("perBox") > 0 || o.optDouble("stock", 0.0) > 0,
                o.optString("shape").takeIf { it in SHAPES } ?: "round", color(o.optString("color")), color(o.optString("color2")),
                o.optString("food").takeIf { it in FOODS }.orEmpty(), o.optInt("every", 1).coerceIn(1, 365))
        }.getOrNull()

        const val WHITE = 0xFFF7F5F0.toInt()
        val SHAPES = listOf("round", "oval", "capsule", "liquid")
        val FOODS = listOf("with", "before", "after", "empty")
        /** The colours a pill is drawn in (the Add's swatches). */
        val COLORS = listOf(WHITE, 0xFFF4D35E.toInt(), 0xFFEF9F27.toInt(), 0xFFE5487A.toInt(), 0xFFE24B4A.toInt(), 0xFF8E6CC9.toInt(), 0xFF378ADD.toInt(), 0xFF1D9E75.toInt(), 0xFF8B6B4A.toInt())
        private fun color(s: String): Int = runCatching { java.lang.Long.parseLong(s.removePrefix("#"), 16).toInt() }.getOrNull()?.takeIf { s.isNotEmpty() } ?: WHITE

        /** Halves only: a stock or a dose typed as 2,3 is 2.5's neighbour, never 2.3. */
        fun halves(v: Double) = (v * 2).roundToInt() / 2.0
        /** "1", "0.5", "2.5" as the user reads them. */
        fun amount(v: Double): String = if (v == floor(v)) v.toLong().toString() else v.toString().replace('.', ',')
    }
}

internal data class PillEvent(val at: Instant, val med: String, val kind: String, val due: Instant? = null, val amount: Double = 0.0, val stock: Double = 0.0, val note: String = "") {
    fun json(): String = JSONObject().put("at", at.toString()).put("med", med).put("kind", kind).put("due", due?.toString() ?: JSONObject.NULL)
        .put("amount", amount).put("stock", stock).apply { if (note.isNotEmpty()) put("note", note) }.toString()

    companion object {
        const val TAKEN = "taken"
        const val SKIPPED = "skipped"
        const val UNDO = "undo"
        /** A box or pills added (amount > 0), or the count corrected (amount the difference). */
        const val STOCK = "stock"
        fun parse(line: String): PillEvent? = runCatching {
            val o = JSONObject(line)
            PillEvent(Instant.parse(o.getString("at")), o.getString("med"), o.getString("kind"),
                o.optString("due", "").takeIf { it.isNotEmpty() && it != "null" }?.let { Instant.parse(it) }, o.optDouble("amount", 0.0), o.optDouble("stock", 0.0), o.optString("note"))
        }.getOrNull()
    }
}

/** One medicine at one time of one day. */
internal data class Dose(val med: Medicine, val due: Instant, val state: State, val at: Instant? = null) {
    enum class State { Waiting, Taken, Skipped, Missed }
    fun lateMin(): Long = at?.let { (it.epochSecond - due.epochSecond) / 60 }?.coerceAtLeast(0) ?: 0
}

internal object Pills {
    /** Reminded at the dose's time and again after these, unless answered; then it is missed. */
    val AGAIN_MS = listOf(0L, 30 * 60_000L, 60 * 60_000L)
    val MISSED_MS = AGAIN_MS.last() + 30 * 60_000L

    fun dir(tetra: File) = TetraSystem.dir(tetra, "Pills", "Data")
    fun phoneDir() = dir(TetraFolder.root(android.os.Environment.getExternalStorageDirectory()))

    fun newId() = "pill-" + java.util.UUID.randomUUID().toString().take(8)

    fun medicines(dir: File): List<Medicine> = dir.listFiles { f -> f.isFile && f.name.startsWith("pill-") && f.name.endsWith(".json") }.orEmpty()
        .mapNotNull { runCatching { Medicine.parse(it.readText()) }.getOrNull() }.sortedWith(compareBy({ it.archived }, { it.times.minOrNull() ?: LocalTime.MAX }, { it.name.lowercase() }))

    fun photo(dir: File, id: String) = File(dir, "$id.jpg")

    /** Written beside, then renamed: never half a file. */
    fun save(dir: File, m: Medicine): Medicine {
        dir.mkdirs()
        val f = File(dir, "${m.id}.json"); val tmp = File(dir, ".${m.id}.json.tmp")
        tmp.writeText(m.json()); if (!tmp.renameTo(f)) { f.writeText(m.json()); tmp.delete() }
        return m
    }

    fun delete(dir: File, m: Medicine) { File(dir, "${m.id}.json").delete(); photo(dir, m.id).delete() }

    fun log(dir: File): List<PillEvent> = File(dir, "log.jsonl").takeIf { it.isFile }?.readLines()?.mapNotNull { PillEvent.parse(it) }.orEmpty()

    @Synchronized private fun append(dir: File, e: PillEvent) { dir.mkdirs(); File(dir, "log.jsonl").appendText(e.json() + "\n") }

    /** The state of every dose: the last answer for each (medicine, due). */
    private fun answers(log: List<PillEvent>): Map<Pair<String, Instant>, PillEvent> =
        log.filter { it.due != null && it.kind in setOf(PillEvent.TAKEN, PillEvent.SKIPPED, PillEvent.UNDO) }.associateBy { it.med to it.due!! }

    /** The doses due in [from, to), earliest first, with their states at [now]. */
    fun doses(meds: List<Medicine>, log: List<PillEvent>, from: Instant, to: Instant, now: Instant, zone: ZoneId): List<Dose> {
        val answered = answers(log)
        val first = from.atZone(zone).toLocalDate(); val last = to.atZone(zone).toLocalDate()
        return meds.filter { !it.archived }.flatMap { m ->
            generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.filter { m.takenOn(it, zone) }.flatMap { d -> m.times.asSequence().map { t -> d.atTime(t).atZone(zone).toInstant() } }
                .filter { it >= from && it < to && (m.since == null || it >= m.since) }.map { due ->
                    val a = answered[m.id to due]
                    val state = when (a?.kind) {
                        PillEvent.TAKEN -> Dose.State.Taken
                        PillEvent.SKIPPED -> Dose.State.Skipped
                        else -> if (now.toEpochMilli() - due.toEpochMilli() > MISSED_MS) Dose.State.Missed else Dose.State.Waiting
                    }
                    Dose(m, due, state, a?.at?.takeIf { a.kind != PillEvent.UNDO })
                }.toList()
        }.sortedWith(compareBy({ it.due }, { it.med.name.lowercase() }))
    }

    /** Today's doses, and before them any of the last day not answered (the user is reminded of yesterday's 21:00 at 08:00). */
    fun today(meds: List<Medicine>, log: List<PillEvent>, now: Instant, zone: ZoneId): List<Dose> {
        val d = now.atZone(zone).toLocalDate(); val start = d.atStartOfDay(zone).toInstant()
        return doses(meds, log, now.minusSeconds(86_400).coerceAtMost(start), d.plusDays(1).atStartOfDay(zone).toInstant(), now, zone)
            .filter { it.due >= start || it.state == Dose.State.Missed || it.state == Dose.State.Waiting }
    }

    /** Taken (at [at], the dose's time or later): the stock less the dose, once however often it is pressed. */
    @Synchronized fun take(dir: File, m: Medicine, due: Instant?, at: Instant): Medicine {
        val now = current(dir, m)
        if (due != null && answers(log(dir))[now.id to due]?.kind == PillEvent.TAKEN) return now
        val next = now.copy(stock = (now.stock - now.dose).coerceAtLeast(0.0))
        // what really came off (never below 0), so Undo gives back no more than that
        save(dir, next); append(dir, PillEvent(at, m.id, PillEvent.TAKEN, due, now.stock - next.stock, next.stock))
        return next
    }

    @Synchronized fun skip(dir: File, m: Medicine, due: Instant, at: Instant): Medicine {
        val now = current(dir, m)
        val last = answers(log(dir))[now.id to due]
        if (last?.kind == PillEvent.SKIPPED) return now
        val back = if (last?.kind == PillEvent.TAKEN) last.amount else 0.0
        val next = if (back != 0.0) now.copy(stock = now.stock + back).also { save(dir, it) } else now
        append(dir, PillEvent(at, m.id, PillEvent.SKIPPED, due, -back, next.stock))
        return next
    }

    /** Back to waiting; a taken dose's pills back in the stock. */
    @Synchronized fun undo(dir: File, m: Medicine, due: Instant, at: Instant): Medicine {
        val now = current(dir, m)
        val was = answers(log(dir))[now.id to due] ?: return now
        if (was.kind == PillEvent.UNDO) return now
        val back = if (was.kind == PillEvent.TAKEN) was.amount else 0.0
        val next = now.copy(stock = now.stock + back)
        if (back != 0.0) save(dir, next)
        append(dir, PillEvent(at, m.id, PillEvent.UNDO, due, back, next.stock))
        return next
    }

    /** [boxes] boxes and/or [pills] pills added (a box's size learnt when given); the refill reminders start over. */
    @Synchronized fun addStock(dir: File, m: Medicine, boxes: Int, pills: Double, perBox: Int?, at: Instant, note: String = ""): Medicine {
        val now = current(dir, m).let { if (perBox != null && perBox > 0) it.copy(perBox = perBox) else it }
        val added = boxes * now.perBox + pills
        val next = now.copy(stock = Medicine.halves(now.stock + added), refillAsked = 0, refillOn = null, counted = now.counted || added > 0)
        save(dir, next); append(dir, PillEvent(at, m.id, PillEvent.STOCK, null, added, next.stock, note))
        return next
    }

    /** The count corrected to what is really in hand. */
    @Synchronized fun setStock(dir: File, m: Medicine, stock: Double, at: Instant): Medicine {
        val now = current(dir, m)
        val next = now.copy(stock = Medicine.halves(stock.coerceAtLeast(0.0)), refillAsked = if (stock > now.stock) 0 else now.refillAsked, counted = true)
        if (next.stock == now.stock && now.counted) return now
        save(dir, next); append(dir, PillEvent(at, m.id, PillEvent.STOCK, null, next.stock - now.stock, next.stock, "count"))
        return next
    }

    /** A refill reminder said today ([PillsAlarms]): counted once a day, under the same lock as Taken. */
    @Synchronized fun refillSaid(dir: File, id: String, today: LocalDate) {
        val m = runCatching { Medicine.parse(File(dir, "$id.json").readText()) }.getOrNull() ?: return
        if (m.refillOn != today) save(dir, m.copy(refillAsked = m.refillAsked + 1, refillOn = today))
    }

    private fun current(dir: File, m: Medicine) = runCatching { Medicine.parse(File(dir, "${m.id}.json").readText()) }.getOrNull() ?: m

    /** A name said or typed ("depon", "Depon 500", "το depon των 500 mg"): the medicine it means, if one alone. */
    fun find(meds: List<Medicine>, name: String, strength: String = ""): List<Medicine> {
        fun norm(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").replace(Regex("[^\\p{L}\\p{N}.]+"), " ").trim()
        fun digits(s: String) = Regex("\\d+(?:[.,]\\d+)?").findAll(s).map { it.value.replace(',', '.') }.toSet()
        val n = norm(name)
        if (n.isEmpty()) return emptyList()
        val live = meds.filter { !it.archived }
        val byName = live.filter { norm(it.name) == n }.ifEmpty { live.filter { norm(it.name).isNotEmpty() && (n.contains(norm(it.name)) || norm(it.name).contains(n)) } }
        // the strength only tells two of one name apart; one alone is it, whatever number was said with it ("Depon 2")
        if (byName.size < 2) return byName
        val want = digits(strength).ifEmpty { digits(name) }
        return byName.filter { digits(it.strength).any { d -> d in want } }.ifEmpty { byName }
    }

    /** Part of the day a time falls in (Today's cards): 0 morning 04–11, 1 noon 11–17, 2 evening 17–22, 3 night. */
    fun part(t: LocalTime) = when (t.hour) { in 4..10 -> 0; in 11..16 -> 1; in 17..21 -> 2; else -> 3 }

    /** One medicine at one time of day over a stretch: what became of each dose, and how late on average. */
    data class Slot(val med: Medicine, val time: LocalTime, val doses: List<Dose>) {
        val due get() = doses.count { it.state != Dose.State.Waiting }
        val onTime get() = doses.count { it.state == Dose.State.Taken && it.lateMin() <= 30 }
        val late get() = doses.count { it.state == Dose.State.Taken && it.lateMin() > 30 }
        val missed get() = doses.count { it.state == Dose.State.Missed || it.state == Dose.State.Skipped }
        val lateMinutes get() = doses.filter { it.state == Dose.State.Taken }.map { it.lateMin() }.let { l -> if (l.isEmpty()) 0 else l.sum() / l.size }
    }

    /** The last [days] days, by medicine and time, the doses still waiting left out of the counts. */
    fun slots(meds: List<Medicine>, log: List<PillEvent>, now: Instant, zone: ZoneId, days: Long = 30): List<Slot> {
        val from = now.atZone(zone).toLocalDate().minusDays(days - 1).atStartOfDay(zone).toInstant()
        val all = doses(meds, log, from, now, now, zone)
        return all.groupBy { it.med.id to it.due.atZone(zone).toLocalTime() }.map { (k, l) -> Slot(l.first().med, k.second, l) }.sortedBy { it.time }
    }

    /** A change the numbers suggest (Progress), worked out here, with no AI: [time] to move a dose to, or a tip. */
    data class Advice(val slot: Slot, val kind: String, val time: LocalTime? = null, val days: List<java.time.DayOfWeek> = emptyList())

    fun advice(slots: List<Slot>, zone: ZoneId): List<Advice> = slots.filter { it.due >= 7 }.mapNotNull { s ->
        val taken = s.doses.filter { it.state == Dose.State.Taken && it.at != null }
        val lateOnes = taken.filter { it.lateMin() > 30 }
        // taken late more often than not, at about the same time: the reminder moved to when it is really taken
        if (lateOnes.size * 2 > taken.size && taken.size >= 4) {
            val mins = lateOnes.map { it.at!!.atZone(zone).toLocalTime().let { t -> t.hour * 60 + t.minute } }.sorted()
            val median = mins[mins.size / 2]; val rounded = (median + 7) / 15 * 15 % (24 * 60)
            return@mapNotNull Advice(s, "move", LocalTime.of(rounded / 60, rounded % 60))
        }
        // forgotten on the same weekdays
        val missedDays = s.doses.filter { it.state == Dose.State.Missed }.map { it.due.atZone(zone).dayOfWeek }
        val often = missedDays.groupingBy { it }.eachCount().filterValues { it >= 2 }.keys.sorted()
        val scheduled = s.doses.map { it.due.atZone(zone).dayOfWeek }.toSet()
        if (s.missed >= 3 && often.isNotEmpty() && often.size * 2 <= scheduled.size) return@mapNotNull Advice(s, "days", days = often) // not when it is every day it is due
        // forgotten at night: the box beside the bed
        if (s.missed * 5 >= s.due && Pills.part(s.time) >= 2) return@mapNotNull Advice(s, "bedside")
        if (s.missed * 5 >= s.due) Advice(s, "earlier", s.time.minusMinutes(30)) else null
    }

    /** The doses at [due]'s minute not answered yet: what a reminder rung then says. */
    fun waitingAt(meds: List<Medicine>, log: List<PillEvent>, due: Instant, now: Instant, zone: ZoneId) =
        doses(meds, log, due, due.plusSeconds(60), now, zone).filter { it.state == Dose.State.Waiting }

    /** When to ring next after [after]: a dose's time, or a reminder again for one not answered; with the doses' time. */
    fun nextRing(meds: List<Medicine>, log: List<PillEvent>, after: Instant, zone: ZoneId): Pair<Instant, Instant>? =
        doses(meds, log, after.minusMillis(MISSED_MS), after.plusSeconds(8 * 86_400L), after, zone)
            .filter { it.state == Dose.State.Waiting }
            .flatMap { d -> AGAIN_MS.map { d.due.plusMillis(it) to d.due } }
            .filter { it.first > after }.minByOrNull { it.first }
}
