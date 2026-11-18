package com.kalotrapezis.drive

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import org.json.JSONObject

/** The phone's GPS for Tetra Auto SE (the head unit's own app, in the Tetra-Auto repository), which may use it over the car link (/car/where). */
internal object CarGps {
    // ---- the phone: its GPS while the car asks, stopped 15 s after the last ask ----

    private val thread by lazy { HandlerThread("car-gps").apply { start() } }
    private val handler by lazy { Handler(thread.looper) }
    @Volatile private var lm: LocationManager? = null
    @Volatile private var last: Location? = null
    @Volatile private var sats = 0 to 0
    @Volatile private var askedAt = 0L
    private val listener = locationListener { last = it }
    private val gnss = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(s: GnssStatus) { sats = (0 until s.satelliteCount).count(s::usedInFix) to s.satelliteCount }
    }

    /** /car/where on the phone: its latest GPS fix, or why there is none. */
    fun where(c: Context): JSONObject {
        askedAt = SystemClock.elapsedRealtime()
        if (lm == null) handler.post { start(c.applicationContext) }
        val l = last ?: return JSONObject().put("error", "No fix yet on the phone.")
        return fix(l, sats)
    }

    @SuppressLint("MissingPermission") // a SecurityException without it: told, not crashed
    private fun start(c: Context) {
        if (lm != null) return
        val m = c.getSystemService(LocationManager::class.java) ?: return
        runCatching { m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, thread.looper); m.registerGnssStatusCallback(gnss, handler) }
            .onFailure { WriterLog.w("Car GPS: the phone's GPS not started (${it.message})"); return }
        lm = m
        WriterLog.i("Car GPS: the phone's GPS for the car")
        handler.postDelayed(object : Runnable { override fun run() {
            if (SystemClock.elapsedRealtime() - askedAt < 15_000) { handler.postDelayed(this, 5000); return }
            runCatching { m.removeUpdates(listener); m.unregisterGnssStatusCallback(gnss) }
            lm = null; last = null
            WriterLog.i("Car GPS: the phone's GPS stopped (the car stopped asking)")
        } }, 5000)
    }

    /** A fix for the wire: its age, not its time (the two clocks differ), and the satellites used / seen. */
    fun fix(l: Location, sats: Pair<Int, Int>): JSONObject = JSONObject().put("lat", l.latitude).put("lng", l.longitude).put("acc", l.accuracy.toDouble())
        .put("speed", l.speed.toDouble()).put("hasSpeed", l.hasSpeed()).put("bearing", l.bearing.toDouble()).put("hasBearing", l.hasBearing())
        .put("alt", l.altitude).put("t", l.time).put("age", (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000)
        .put("used", sats.first).put("seen", sats.second)

}
