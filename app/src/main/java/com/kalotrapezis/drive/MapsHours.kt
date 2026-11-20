package com.kalotrapezis.drive

import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * Open now or not, from OSM's opening_hours ("Mo-Fr 08:00-21:00; Sa 08:00-20:00; Su off"), for the search rows (the user,
 * 20 Nov, Google's list beside ours: «Κλειστά · Ανοίγει 8:00 π.μ. Σάβ»). Days, day ranges, several times a day, past
 * midnight, "off", 24/7; anything else (months, holidays alone, sunrise) gives null, so nothing wrong is ever shown.
 */
internal object OpenHours {
    /** [open] now; [change]: when it closes (open) or opens (closed), null if never within a week. */
    data class Now(val open: Boolean, val change: LocalDateTime?)

    private val DAYS = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
    private val TIME = Regex("""^(\d{1,2}):(\d{2})-(\d{1,2}):(\d{2})\+?$""")

    /** Each day's open spans in minutes from its midnight (an end past 1440 runs into the next day); null when not understood. */
    fun week(hours: String): Map<DayOfWeek, List<IntRange>>? {
        val h = hours.trim()
        if (h.isEmpty()) return null
        if (h == "24/7") return DayOfWeek.entries.associateWith { listOf(0..1440) }
        val week = DayOfWeek.entries.associateWith { emptyList<IntRange>() }.toMutableMap()
        for (rule in h.split(';').map { it.trim() }.filter { it.isNotEmpty() }) {
            val m = Regex("""^((?:(?:Mo|Tu|We|Th|Fr|Sa|Su|PH)(?:-(?:Mo|Tu|We|Th|Fr|Sa|Su))?,?\s*)+)?(.*)$""").find(rule) ?: return null
            val dayPart = m.groupValues[1].trim().trimEnd(',')
            val rest = m.groupValues[2].trim()
            val days = if (dayPart.isEmpty()) DayOfWeek.entries else dayPart.split(',').map { it.trim() }.filter { it.isNotEmpty() && it != "PH" }.flatMap { d ->
                val ab = d.split('-'); val a = ab[0]; val b = ab.getOrElse(1) { a }
                val i = DAYS.indexOf(a); val j = DAYS.indexOf(b)
                if (i < 0 || j < 0) return null
                (0..((j - i + 7) % 7)).map { DayOfWeek.of((i + it) % 7 + 1) }
            }
            if (days.isEmpty()) continue // "PH off": holidays alone, not known here
            val spans = if (rest == "off" || rest == "closed") emptyList() else rest.split(',').map { t ->
                val x = TIME.find(t.trim()) ?: return null
                val (h1, m1, h2, m2) = x.destructured
                val a = h1.toInt() * 60 + m1.toInt(); var b = h2.toInt() * 60 + m2.toInt()
                if (b <= a) b += 1440
                a..b
            }
            days.forEach { week[it] = spans } // a later rule overrides the days it names
        }
        return week
    }

    fun now(hours: String, at: LocalDateTime): Now? {
        val week = week(hours) ?: return null
        val today = at.toLocalDate().atStartOfDay()
        val spans = (-1..7).flatMap { d -> val day = today.plusDays(d.toLong()); week[day.dayOfWeek].orEmpty().map { day.plusMinutes(it.first.toLong()) to day.plusMinutes(it.last.toLong()) } }
            .sortedBy { it.first }
        val inside = spans.firstOrNull { !at.isBefore(it.first) && at.isBefore(it.second) }
        if (inside != null) {
            // spans that touch (23:00-24:00 and 00:00-02:00) are one
            var end = inside.second
            while (true) { end = spans.firstOrNull { !it.first.isAfter(end) && it.second.isAfter(end) }?.second ?: break }
            return Now(true, end.takeIf { it.isBefore(today.plusDays(7)) })
        }
        return Now(false, spans.firstOrNull { it.first.isAfter(at) }?.first)
    }
}
