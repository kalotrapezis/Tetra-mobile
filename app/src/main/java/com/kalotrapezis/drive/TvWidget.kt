package com.kalotrapezis.drive

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * The AI Assist TV on the phone's own home screen (8 Nov: "a real widget, this TV… to turn on and off; now and then the robot
 * cleans the screen from the other side"). On: the mascot's frames flip by themselves (no app running), a tap on the screen
 * talks (AI Assist's TV sheet), the knobs at the right switch it off. Off: the dark set, a tap switches it on.
 */
class TvWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, manager: AppWidgetManager, ids: IntArray) = show(c, manager, ids)

    override fun onReceive(c: Context, intent: Intent) {
        if (intent.action == POWER) {
            val on = !isOn(c)
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ON, on).apply()
            val manager = AppWidgetManager.getInstance(c)
            val ids = manager.getAppWidgetIds(ComponentName(c, TvWidget::class.java))
            if (on && AiLook.decorated(c)) { // switched on: the «fsst», the mascot walking in with its hello, then the TV as usual
                Zap.play()
                show(c, manager, ids, entering = true)
                val done = goAsync()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ show(c, manager, ids); done.finish() }, ENTER_MS)
            } else show(c, manager, ids)
        }
        super.onReceive(c, intent)
    }

    companion object {
        private const val POWER = "com.kalotrapezis.drive.TV_POWER"
        private const val PREFS = "tv_widget"
        private const val ON = "on"
        fun isOn(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ON, true)

        private const val ENTER_MS = 4_000L

        /** All the TVs on the home screen again (the AI decorations switch changed, say). */
        fun refresh(c: Context) { val m = AppWidgetManager.getInstance(c); show(c, m, m.getAppWidgetIds(ComponentName(c, TvWidget::class.java))) }

        private fun show(c: Context, manager: AppWidgetManager, ids: IntArray, entering: Boolean = false) {
            if (ids.isEmpty()) return
            val power = PendingIntent.getBroadcast(c, 0, Intent(c, TvWidget::class.java).setAction(POWER), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val layout = when { entering -> R.layout.widget_tv_enter; AiLook.decorated(c) -> R.layout.widget_tv_on; else -> R.layout.widget_tv_still }
            val views = if (isOn(c)) RemoteViews(c.packageName, layout).apply {
                if (entering) setTextViewText(R.id.tv_hello, c.resources.getStringArray(R.array.tv_hello).random())
                val talk = PendingIntent.getActivity(c, 1, Intent(c, AssistantSheetActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                setOnClickPendingIntent(R.id.tv_talk, talk)
                setOnClickPendingIntent(R.id.tv_power, power)
            } else RemoteViews(c.packageName, R.layout.widget_tv_off).apply { setOnClickPendingIntent(R.id.tv_off, power) }
            ids.forEach { manager.updateAppWidget(it, views) }
        }
    }
}
