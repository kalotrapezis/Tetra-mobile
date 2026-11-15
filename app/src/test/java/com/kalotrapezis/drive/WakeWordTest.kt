package com.kalotrapezis.drive

import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** "Hey Tetra" (WakeWord): a made-up word found in a stream and another word not; and, on this PC, the same numbers as tools/wake-test.py. */
class WakeWordTest {
    private val rate = WakeWord.RATE
    private val rnd = java.util.Random(7)

    /** A made-up "word": vowel-like buzzes, each [ms] long at its pitch with two formant-ish harmonics. */
    private fun word(parts: List<Triple<Double, Int, Int>>, stretch: Double = 1.0, pitch: Double = 1.0): ShortArray {
        val out = ArrayList<Short>()
        for ((f0, h, ms) in parts) {
            val n = (rate * ms / 1000 * stretch).toInt()
            for (i in 0 until n) {
                val t = i.toDouble() / rate; val env = minOf(1.0, i / 200.0, (n - i) / 200.0)
                val v = (1..12).sumOf { k -> sin(2 * PI * f0 * pitch * k * t) * (if (k == h || k == h + 3) 1.0 else 0.15) }
                out += (v * env * 1500 + rnd.nextGaussian() * 30).toInt().toShort()
            }
        }
        return out.toShortArray()
    }
    private fun quiet(ms: Int) = ShortArray(rate * ms / 1000) { (rnd.nextGaussian() * 30).toInt().toShort() }
    private val heyTetra = listOf(Triple(130.0, 4, 180), Triple(150.0, 2, 160), Triple(120.0, 6, 220))
    private val other = listOf(Triple(110.0, 7, 200), Triple(170.0, 1, 150), Triple(140.0, 9, 200), Triple(100.0, 3, 180))

    @Test fun findsItsWordAndNotAnother() {
        val takes = listOf(1.0 to 1.0, 0.9 to 1.03, 1.1 to 0.97, 1.0 to 0.98, 0.95 to 1.02).map { (s, p) -> WakeWord.features(quiet(400) + word(heyTetra, s, p) + quiet(400)) }
        takes.forEach { assertTrue("a take is found in its recording", it.size in 40..80) }
        val th = WakeWord.threshold(takes)
        val scores = ArrayList<Double>()
        val said = WakeDetector(takes, th) { scores += it }
        assertTrue("said a little slower", said.push(quiet(1500) + word(heyTetra, 1.15, 1.01) + quiet(2000)))
        val notSaid = WakeDetector(takes, th)
        assertFalse("another word", notSaid.push(quiet(1500) + word(other) + quiet(2000)))
        assertFalse("quiet is never looked at", WakeDetector(takes, th).push(quiet(5000)))
        assertEquals(1, said.checks)
    }

    /** The user's own takes (tools/wake-record.sh), only where they are: never in git. Numbers from tools/wake-test.py, 15 Nov. */
    @Test fun sameAsThePcTest() {
        val d = File(System.getProperty("user.home"), ".cache/tetra-wake")
        assumeTrue(File(d, "template5.wav").exists())
        fun wav(n: String): ShortArray = javax.sound.sampled.AudioSystem.getAudioInputStream(File(d, "$n.wav")).use { s ->
            val b = s.readAllBytes(); ShortArray(b.size / 2) { ((b[2 * it].toInt() and 0xff) or (b[2 * it + 1].toInt() shl 8)).toShort() } }
        val takes = (1..5).map { WakeWord.features(wav("template$it")) }
        val loo = WakeWord.leaveOneOut(takes)
        doubleArrayOf(12.96, 10.73, 16.16, 11.55, 10.75).forEachIndexed { i, v -> assertEquals("take ${i + 1}", v, loo[i], 0.05) }
        mapOf("held1" to 11.52, "fast" to 13.26, "slow" to 13.03, "far" to 15.08, "quiet" to 14.85, "near" to 13.99).forEach { (n, v) ->
            val scores = ArrayList<Double>()
            WakeDetector(takes, -1.0) { scores += it }.push(wav(n))
            assertEquals(n, v, scores.min(), 0.05)
        }
    }
}
