package com.kalotrapezis.drive

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** AI Assist's speech as AAC (m4a): a tenth of the WAV, a real .m4a of the same length (the car's slow uplink, 17 Nov). */
@RunWith(AndroidJUnit4::class)
class SpeechAacDeviceTest {
    @Test fun threeSecondsOfSpeechBecomeASmallM4a() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val pcm = ShortArray(3 * Vad.RATE) { i -> (6000 * Math.sin(i * 2 * Math.PI * 220 / Vad.RATE)).toInt().toShort() }
        val aac = m4a(pcm, dir)
        assertNotNull(aac)
        val wav = Vad.wav(pcm).size
        assertTrue("${aac!!.size} of $wav", aac.size < wav / 5)
        val f = File(dir, "check.m4a").apply { writeBytes(aac) }
        val x = MediaExtractor().apply { setDataSource(f.path) }
        val format = x.getTrackFormat(0)
        assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, format.getString(MediaFormat.KEY_MIME))
        assertEquals(3.0, format.getLong(MediaFormat.KEY_DURATION) / 1e6, 0.2)
        x.release(); f.delete()
        assertEquals(0, dir.listFiles { _, n -> n.startsWith("speech") }!!.size) // its temporary file gone
    }
}
