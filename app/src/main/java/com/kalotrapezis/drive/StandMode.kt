package com.kalotrapezis.drive

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.provider.Settings
import android.service.dreams.DreamService
import android.view.View
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Stand mode (the user, 30 Oct: "for amoled, super low light clock thing when charging"): Android's own screen saver, which
 * starts by itself while the phone charges, showing a dim grey clock on black with the screen at its lowest. Burn-in: the
 * clock moves a little every minute, and nothing else is lit.
 */
class StandDream : DreamService() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLocale.wrap(base)) // Tetra's language, not only the phone's
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false // a touch ends it
        isFullscreen = true
        isScreenBright = false
        setContentView(StandClockView(this))
    }
}

/** The time and the date, dim grey on black, a little elsewhere each minute. */
internal class StandClockView(context: Context) : View(context) {
    private val time = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF505050.toInt(); typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL); textAlign = Paint.Align.CENTER }
    private val date = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF383838.toInt(); typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); textAlign = Paint.Align.CENTER }
    private val hhmm = DateTimeFormatter.ofPattern(if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm", context.uiLocale())
    private val day = DateTimeFormatter.ofPattern("EEEE d MMMM", context.uiLocale())
    private val tick = object : Runnable {
        override fun run() { invalidate(); postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000) }
    }

    init { setBackgroundColor(Color.BLACK) }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(tick) }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val now = LocalDateTime.now()
        time.textSize = minOf(width, height) * 0.24f
        date.textSize = time.textSize * 0.2f
        // a 5 × 5 walk around the middle, one step a minute, so no pixel stays lit for long
        val m = now.minute
        val x = width / 2f + (m % 5 - 2) * width * 0.04f
        val y = height / 2f + (m / 5 % 5 - 2) * height * 0.04f
        canvas.drawText(now.format(hhmm), x, y, time)
        canvas.drawText(now.format(day), x, y + date.textSize * 2f, date)
    }
}

/** The Clock's header button: what stand mode is, and Android's screen saver page, where it is chosen. */
@Composable internal fun StandButton() {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
        IconButton(onClick = { open = true }) { Icon(painterResource(R.drawable.ic_bedtime), contentDescription = stringResource(R.string.stand_mode)) }
    }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(stringResource(R.string.stand_mode)) },
        text = { Text(stringResource(R.string.stand_about)) },
        confirmButton = { Button(onClick = {
            open = false
            runCatching { context.startActivity(Intent(Settings.ACTION_DREAM_SETTINGS)) }.onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS)) } }
        }) { Text(stringResource(R.string.stand_settings)) } },
        dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.common_cancel)) } })
}
