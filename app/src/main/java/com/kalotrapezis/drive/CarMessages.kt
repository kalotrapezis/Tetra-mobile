package com.kalotrapezis.drive

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import org.json.JSONObject

/**
 * The phone in the car, as Tetra Car SE (the head unit's own app) sees it over the hotspot (CarWire): while the car says hello,
 * an SMS goes to the car to be read aloud there, a ringing call is answered after 2 s, and the phone keeps quiet (CarQuiet).
 * The choices are AI Assist's car_assist and Settings › Tetra Car.
 */
internal object Car {
    private fun prefs(c: Context) = c.getSharedPreferences("car", Context.MODE_PRIVATE)
    fun reads(c: Context) = prefs(c).getBoolean("read_sms", true)
    fun answers(c: Context) = prefs(c).getBoolean("answer_calls", true)
    fun set(c: Context, read: Boolean?, answer: Boolean?) = prefs(c).edit().apply { read?.let { putBoolean("read_sms", it) }; answer?.let { putBoolean("answer_calls", it) } }.apply()
    /** Android lets an app open a screen from the background only with "Display over other apps" (or as the home screen). */
    fun mayOpen(c: Context) = android.provider.Settings.canDrawOverlays(c)
    fun askOpen(c: Context) = c.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    /** An SMS arrived (SmsDeliverReceiver, SmsReceivedReceiver): to Tetra Car SE while it is here, which reads it and sends the reply back. */
    fun sms(c: Context, from: String, body: String) {
        if (!reads(c) || !CarWire.alive()) return
        Thread { CarWire.tell(c, JSONObject().put("type", "sms").put("from", from).put("name", PhoneCalls.contactFor(from)?.name ?: from).put("body", body)) }.start() // the contact looked up off the receiver's thread
    }
}

/**
 * Quiet in the car (the user, 9 Nov: "when in car mode we should block notifications and take over"): Android's Do Not Disturb as
 * a driving rule of Tetra's own, so the user's own DND settings stay as they are. Calls (Tetra answers them), repeat callers and
 * alarms come through, and Tetra's car channel (a message read aloud); other apps' notifications wait. On while Tetra Car SE says hello
 * over the hotspot (CarWire), off after. Needs Android's "Do Not Disturb access", asked once in Settings › Tetra Car.
 */
internal object CarQuiet {
    private const val RULE = "car_quiet_rule"
    private val CONDITION = android.net.Uri.parse("condition://com.kalotrapezis.drive/car")
    private fun prefs(c: Context) = c.getSharedPreferences("car", Context.MODE_PRIVATE)
    fun wanted(c: Context) = prefs(c).getBoolean("quiet", true)
    fun allowed(c: Context) = c.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
    fun ask(c: Context) = c.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    fun want(c: Context, on: Boolean) { prefs(c).edit().putBoolean("quiet", on).apply(); if (!on) set(c, false) }

    /** The rule on or off; nothing without the access or when the user switched it off. */
    @Synchronized fun set(c: Context, on: Boolean) = runCatching {
        if (!allowed(c) || (on && !wanted(c))) return@runCatching
        val nm = c.getSystemService(NotificationManager::class.java)
        // media through (the user, 10 Nov, the car's first drive: "no sound" — Do Not Disturb mutes media unless it is let through:
        // the music, the route's voice and AI Assist went silent on the car's speakers); calls and alarms as before
        val policy = android.service.notification.ZenPolicy.Builder().disallowAllSounds().hideAllVisualEffects().allowMedia(true)
            .allowCalls(android.service.notification.ZenPolicy.PEOPLE_TYPE_ANYONE).allowRepeatCallers(true).allowAlarms(true).build()
        val car = android.content.ComponentName(c, MainActivity::class.java)
        val rule = if (android.os.Build.VERSION.SDK_INT >= 35) // Android's own driving rule, shown as such in its settings
            android.app.AutomaticZenRule.Builder(c.getString(R.string.app_car), CONDITION).setType(android.app.AutomaticZenRule.TYPE_DRIVING)
                .setConfigurationActivity(car).setZenPolicy(policy).setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY).setEnabled(true).build()
        else android.app.AutomaticZenRule(c.getString(R.string.app_car), null, car, CONDITION, policy, NotificationManager.INTERRUPTION_FILTER_PRIORITY, true)
        val kept = prefs(c).getString(RULE, null)?.takeIf { nm.getAutomaticZenRule(it) != null }
        if (kept != null && on && nm.getAutomaticZenRule(kept).zenPolicy?.priorityCategoryMedia != android.service.notification.ZenPolicy.STATE_ALLOW)
            runCatching { nm.updateAutomaticZenRule(kept, rule); WriterLog.i("Car: quiet lets media through now") }.onFailure { WriterLog.w("Car: quiet not updated", it) }
        val id = kept ?: run {
            if (!on) return@runCatching
            nm.addAutomaticZenRule(rule).also { prefs(c).edit().putString(RULE, it).apply() }
        }
        nm.setAutomaticZenRuleState(id, android.service.notification.Condition(CONDITION, c.getString(R.string.app_car),
            if (on) android.service.notification.Condition.STATE_TRUE else android.service.notification.Condition.STATE_FALSE))
        WriterLog.i("Car: quiet ${if (on) "on" else "off"}")
    }.onFailure { WriterLog.w("Car: quiet not set", it) }
}
