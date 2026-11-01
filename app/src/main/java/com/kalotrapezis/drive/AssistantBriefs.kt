package com.kalotrapezis.drive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date

/*
 * The computer's scheduled AI actions, shown here (the desktop's schedule.js and brief.js run them; the phone does not).
 * Each run writes a page into .System/Assistant/Shared/Briefs, which syncs as "AI Assist/Briefs", and latest.json says
 * which is new: after a sync, a new one rings like a Calendar reminder (the island on HyperOS), and a tap opens the page.
 */
internal object AssistantBriefs {
    private const val CHANNEL = "assistant_briefs"
    private const val ID = 7301

    fun dir(): File = File(TetraSystem.dir(TetraFolder.root(Environment.getExternalStorageDirectory()), "Assistant", "Shared"), "Briefs")

    /** The pages, newest first. */
    fun pages(): List<File> = dir().listFiles { f -> f.name.endsWith(".html") }.orEmpty().sortedByDescending { it.lastModified() }

    /** The page's own title (its <title>), else its name. */
    fun title(f: File): String = runCatching { Regex("<title>([^<]*)</title>").find(f.bufferedReader().use { r -> CharArray(2000).let { String(it, 0, r.read(it).coerceAtLeast(0)) } })?.groupValues?.get(1) }
        .getOrNull()?.replace("&amp;", "&")?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&quot;", "\"") ?: f.nameWithoutExtension

    /** After a sync: latest.json newer than the last one shown rings once; nothing while the AI is switched off. */
    fun check(c: Context) {
        val o = runCatching { JSONObject(File(dir(), "latest.json").readText()) }.getOrNull() ?: return
        val prefs = c.getSharedPreferences("assistant_briefs", Context.MODE_PRIVATE)
        val at = o.optLong("at")
        // the first look ever only notes where things are: the old pages that came with the first sync do not ring
        if (!prefs.contains("seen")) { prefs.edit().putLong("seen", at).apply(); return }
        if (at <= prefs.getLong("seen", 0)) return
        prefs.edit().putLong("seen", at).apply()
        if (!AiGate.load(c).enabled) return
        val page = File(dir(), File(o.optString("file")).name)
        if (page.isFile) post(c, o.optString("title"), o.optString("text"), page, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at)))
    }

    fun post(c: Context, title: String, text: String, page: File, time: String) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.assistant_briefs_channel), NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(c, ID, Intent(c, BriefActivity::class.java).putExtra("file", page.absolutePath)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // ✕: the island goes (the Calendar's Done does that, and for a time with no event, nothing more)
        val done = PendingIntent.getBroadcast(c, ID + 1, Intent(c, CalendarAlarmReceiver::class.java).setAction(CalendarAlarms.ACTION_DONE).putExtra("id", ID),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_auto_fix)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setOngoing(true) // HyperOS gives the island to an ongoing notification, as the Calendar's reminders
            .setAutoCancel(true)
            .addAction(R.drawable.ic_close, c.getString(R.string.calendar_reminder_done), done)
        if (HyperIsland.isSupported(c)) HyperIsland.reminder(n, title, text, time, done, android.graphics.drawable.Icon.createWithResource(c, R.drawable.ic_reminder_done), null)
        runCatching { NotificationManagerCompat.from(c).notify(ID, n.build()) }
    }
}

/** One page of the briefs, as it is: light or dark as the phone is (the page's own CSS). */
class BriefActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationManagerCompat.from(this).cancel(7301)
        val f = File(intent.getStringExtra("file").orEmpty())
        // only a page of the briefs' folder: an intent cannot point this at another file
        val html = if (f.parentFile?.canonicalPath == AssistantBriefs.dir().canonicalPath) runCatching { f.readText() }.getOrNull() else null
        setContentView(WebView(this).apply {
            settings.javaScriptEnabled = false
            fitsSystemWindows = true
            loadDataWithBaseURL(null, html ?: "<p>…</p>", "text/html", "utf-8", null)
        })
    }
}
