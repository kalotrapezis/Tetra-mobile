package com.kalotrapezis.drive

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * "Hey Tetra" (Settings › AI Assist, off unless switched on; the phone only — the car has its steering-wheel button and the car
 * build leaves this out). Its takes (four of each phrase) live in files/wake, this device's own mic, never synced.
 */
internal object Wake {
    const val TAKES = 4 // per phrase: 12 in all, a short session (the user, 15 Nov)
    private fun prefs(c: Context) = c.getSharedPreferences("assistant", Context.MODE_PRIVATE)
    fun on(c: Context) = prefs(c).getBoolean("wake", false)
    fun dir(c: Context) = File(c.filesDir, "wake")
    fun take(c: Context, phrase: String, i: Int) = File(dir(c), "${phrase.lowercase().replace(' ', '_')}$i.pcm")
    fun hasTakes(c: Context) = WakeWord.PHRASES.all { p -> (1..TAKES).all { take(c, p, it).isFile } }
    /** Every phrase from its kept takes; null when one cannot be read. */
    fun phrases(c: Context): List<WakePhrase>? = runCatching { WakeWord.PHRASES.map { p -> WakePhrase(p, (1..TAKES).map { WakeWord.features(read(take(c, p, it))) }) } }
        .getOrNull()?.takeIf { l -> l.all { p -> p.templates.all { it.isNotEmpty() } } }
    fun read(f: File): ShortArray { val b = f.readBytes(); return ShortArray(b.size / 2) { ((b[2 * it].toInt() and 0xff) or (b[2 * it + 1].toInt() shl 8)).toShort() } }
    fun write(f: File, pcm: ShortArray) { f.parentFile?.mkdirs(); f.writeBytes(ByteArray(pcm.size * 2) { (pcm[it / 2].toInt() shr (8 * (it % 2))).toByte() }) }

    fun set(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("wake", on).apply()
        WriterLog.i("Hey Tetra: switched ${if (on) "on" else "off"}")
        if (on) ensure(c) else c.stopService(Intent(c, WakeService::class.java))
    }

    /** Listening again whenever Tetra is on the screen (Android starts a microphone service only then: not at boot, not from the background). */
    fun ensure(c: Context) {
        if (CarMaps.edition || !on(c) || !hasTakes(c) || !hasMic(c) || WakeService.running) return
        runCatching { c.startForegroundService(Intent(c, WakeService::class.java)) }.onFailure { WriterLog.w("Hey Tetra: the service did not start: ${it.message}") }
    }

    /** One take from the mic: [ms] of sound, the source the service listens with. */
    @SuppressLint("MissingPermission")
    fun record(ms: Int): ShortArray? {
        val n = WakeWord.RATE * ms / 1000
        val rec = runCatching { AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, WakeWord.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(AudioRecord.getMinBufferSize(WakeWord.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), n * 2)) }.getOrNull()
        if (rec?.state != AudioRecord.STATE_INITIALIZED) { rec?.release(); WriterLog.w("Hey Tetra: the mic did not open for a take"); return null }
        return try {
            val out = ShortArray(n); var got = 0
            rec.startRecording()
            while (got < n) { val r = rec.read(out, got, n - got); if (r <= 0) break; got += r }
            out.copyOf(got)
        } finally { runCatching { rec.stop() }; rec.release() }
    }
}

/**
 * The listening (Plan.md § Hey Tetra): the mic in a foreground service with its notice. In quiet only each 10 ms frame's
 * loudness is worked out (WakeDetector); "Hey Tetra" heard: a tone, then AI Assist's sheet over the lock screen, listening.
 * The mic is let go while AI Assist is on the screen, while another of Tetra's recordings runs, and during a call.
 */
class WakeService : Service() {
    @Volatile private var stop = false
    @Volatile private var inCar = false
    private var thread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == OFF) { Wake.set(this, false); return START_NOT_STICKY }
        runCatching { startForeground(ID, notice(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) }
            .onFailure { WriterLog.w("Hey Tetra: Android did not let the mic start (Tetra not on the screen?): ${it.message}"); stopSelf(); return START_NOT_STICKY }
        if (thread == null) { stop = false; running = true; thread = Thread(::listen, "HeyTetra").apply { start() } }
        return START_STICKY
    }

    override fun onDestroy() { stop = true; running = false; thread?.interrupt(); thread = null; WriterLog.i("Hey Tetra: stopped"); super.onDestroy() }

    @SuppressLint("MissingPermission")
    private fun listen() {
        val phrases = Wake.phrases(this) ?: run { WriterLog.w("Hey Tetra: the takes could not be read; record them again"); stopSelf(); return }
        WriterLog.i("Hey Tetra: listening for " + phrases.joinToString { "${it.name} (under ${"%.2f".format(it.threshold)}, takes ${it.templates.joinToString("/") { t -> "${t.size}" }} frames)" })
        val audio = getSystemService(AudioManager::class.java)
        val buf = ShortArray(WakeWord.RATE / 10) // 100 ms a read
        var rec: AudioRecord? = null
        var near = Double.POSITIVE_INFINITY; var wakes = 0; var summaryAt = System.currentTimeMillis(); var pausedMs = 0L
        // every score near the threshold goes to the log, for tuning it on this phone
        fun detector() = WakeDetector(phrases) { p, r -> near = minOf(near, r); if (r < 1.25) WriterLog.i("Hey Tetra: heard ${p.name} at ${"%.2f".format(r)} of its threshold${if (r < 1) ": wakes" else ""}") }
        var det = detector()
        fun release() { rec?.let { runCatching { it.stop() }; it.release() }; rec = null }
        var carCheckAt = 0L
        // in the car (its Bluetooth, Tetra Car open, or Tetra Car SE saying hello) the steering-wheel button is the way in (the user, 15 Nov)
        fun inCar(now: Long): Boolean { if (now - carCheckAt >= 30_000) { carCheckAt = now; Car.inCar(this) { inCar = it } }; return inCar }
        try {
            while (!stop) {
                val now = System.currentTimeMillis()
                if (now - summaryAt >= SUMMARY_MS) {
                    WriterLog.i("Hey Tetra: last ${SUMMARY_MS / 60_000} min: ${det.allFrames / 100} s heard, ${"%.0f".format(100.0 * det.loudFrames / maxOf(1, det.allFrames))}% over the gate, " +
                        "${det.checks} looked at, nearest ${"%.2f".format(near)} of a threshold, $wakes woke, ${pausedMs / 1000} s let go")
                    near = Double.POSITIVE_INFINITY; wakes = 0; summaryAt = now; pausedMs = 0
                    det = detector()
                }
                val other = rec?.let { r -> audio.activeRecordingConfigurations.any { it.clientAudioSessionId != r.audioSessionId } } ?: audio.activeRecordingConfigurations.isNotEmpty()
                val car = inCar(now)
                if (assistantShown > 0 || other || car || audio.mode != AudioManager.MODE_NORMAL) {
                    if (rec != null) { release(); WriterLog.i("Hey Tetra: mic let go (${if (assistantShown > 0) "AI Assist open" else if (other) "another recording" else if (car) "in the car" else "a call"})") }
                    Thread.sleep(500); pausedMs += 500; continue
                }
                if (rec == null) {
                    val r = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, WakeWord.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        maxOf(AudioRecord.getMinBufferSize(WakeWord.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), buf.size * 4))
                    if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); WriterLog.w("Hey Tetra: the mic did not open; trying again in 5 s"); Thread.sleep(5000); continue }
                    r.startRecording(); rec = r
                    det = detector()
                }
                val n = rec!!.read(buf, 0, buf.size)
                if (n <= 0) { WriterLog.w("Hey Tetra: the mic read $n; opening it again"); release(); Thread.sleep(1000); continue }
                if (det.push(buf, n)) {
                    wakes++
                    release()
                    WriterLog.i("Hey Tetra: woke")
                    wake()
                    Thread.sleep(3000) // the sheet opens and takes the mic; then assistantShown keeps this away
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            WriterLog.e("Hey Tetra: listening failed", e)
        } finally { release() }
    }

    /** A tone, then AI Assist's sheet listening, the screen on and over the lock screen. */
    private fun wake() {
        Chime.play(up = true)
        Thread.sleep(350) // the tone done before the sheet's mic opens
        val open = Intent(this, AssistantSheetActivity::class.java).putExtra(EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Android opens a screen from the background only with "Display over other apps"; otherwise as an alarm's full-screen notice
        if (android.provider.Settings.canDrawOverlays(this)) { runCatching { startActivity(open) }.onFailure { WriterLog.w("Hey Tetra: the sheet did not open", it) }; return }
        val tap = PendingIntent.getActivity(this, ID + 1, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, channel(this, WOKE_CHANNEL, R.string.wake_title, NotificationManager.IMPORTANCE_HIGH)).setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.app_assistant)).setContentText(getString(R.string.assistant_listening)).setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setContentIntent(tap).setFullScreenIntent(tap, true).setAutoCancel(true).setTimeoutAfter(15_000).build()
        runCatching { NotificationManagerCompat.from(this).notify(ID + 1, n) }.onFailure { WriterLog.w("Hey Tetra: the notice was not allowed: ${it.message}") }
        WriterLog.i("Hey Tetra: opened by a full-screen notice (no \"Display over other apps\")")
    }

    private fun notice() = NotificationCompat.Builder(this, channel(this, CHANNEL, R.string.wake_title, NotificationManager.IMPORTANCE_LOW)).setSmallIcon(R.drawable.ic_mic)
        .setContentTitle(getString(R.string.wake_title)).setContentText(getString(R.string.wake_notice)).setOngoing(true).setSilent(true)
        .addAction(0, getString(R.string.wake_turn_off), PendingIntent.getService(this, ID, Intent(this, WakeService::class.java).setAction(OFF), PendingIntent.FLAG_IMMUTABLE))
        .build()

    companion object {
        private const val ID = 7421
        private const val CHANNEL = "wake"
        private const val WOKE_CHANNEL = "wake_open"
        private const val OFF = "com.kalotrapezis.drive.WAKE_OFF"
        private const val SUMMARY_MS = 10 * 60_000L
        /** On the sheet's intent: opened by "Hey Tetra" (over the lock screen, the screen turned on). */
        const val EXTRA = "com.kalotrapezis.drive.WAKE"
        @Volatile var running = false
        /** AI Assist's screens now started (AssistantActivity): the mic is theirs meanwhile. */
        @Volatile var assistantShown = 0
        fun cancelNotice(c: Context) = NotificationManagerCompat.from(c).cancel(ID + 1)
        private fun channel(c: Context, id: String, name: Int, importance: Int): String {
            c.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(id, c.getString(name), importance))
            return id
        }
    }
}

/**
 * Security is Android's (the user, 15 Nov): with the phone locked, a personal tool (calls, messages, mail, calendar, notes, files,
 * contacts…) asks Android to unlock first, then goes on; timers, the time, weather, music and questions run locked. In the car
 * (Tetra Car open, Tetra Car SE saying hello, or the car's Bluetooth) nothing is asked.
 */
internal object WakeUnlock {
    /** Set by AI Assist's screen while it is shown: asks Android's unlock, then says whether it happened. */
    @Volatile var ask: ((done: (Boolean) -> Unit) -> Unit)? = null

    /** Called on a tool's thread, never the main one: true when the phone is unlocked (now or after asking), or in the car. */
    fun ok(c: Context): Boolean {
        if (!c.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return true
        val car = CountDownLatch(1); var inCar = false
        Car.inCar(c) { inCar = it; car.countDown() }
        car.await(2, TimeUnit.SECONDS)
        if (inCar) { WriterLog.i("AI Assist: locked, in the car: no unlock asked"); return true }
        val asking = ask ?: run { WriterLog.i("AI Assist: locked and no screen to ask the unlock on"); return false }
        val done = CountDownLatch(1); var unlocked = false
        asking { unlocked = it; done.countDown() }
        done.await(60, TimeUnit.SECONDS)
        WriterLog.i("AI Assist: unlock asked for a personal tool: ${if (unlocked) "unlocked" else "not unlocked"}")
        return unlocked
    }
}
