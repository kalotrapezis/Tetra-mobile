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

    private val third = listOf(Triple(160.0, 5, 160), Triple(105.0, 8, 240))

    @Test fun eachPhraseWakesAsItselfAndQuietNever() {
        fun takes(w: List<Triple<Double, Int, Int>>) = listOf(1.0 to 1.0, 0.9 to 1.03, 1.1 to 0.97, 0.95 to 1.02).map { (s, p) -> WakeWord.features(quiet(400) + word(w, s, p) + quiet(400)) }
        val phrases = listOf(WakePhrase("Hey Tetra", takes(heyTetra)), WakePhrase("Tetra", takes(other)))
        phrases.flatMap { it.templates }.forEach { assertTrue("a take is found in its recording", it.size in 30..90) }
        fun heard(pcm: ShortArray): Pair<String, Double>? { var best: Pair<String, Double>? = null
            val woke = WakeDetector(phrases) { p, r -> if (best == null || r < best!!.second) best = p.name to r }.push(quiet(1500) + pcm + quiet(2000))
            return best?.takeIf { woke } }
        assertEquals("said a little slower", "Hey Tetra", heard(word(heyTetra, 1.15, 1.01))?.first)
        assertEquals("the other phrase", "Tetra", heard(word(other, 0.9, 0.99))?.first)
        assertEquals("a third word wakes neither", null, heard(word(third)))
        val quietOnly = WakeDetector(phrases)
        assertFalse(quietOnly.push(quiet(5000)))
        assertEquals("quiet is never looked at", 0, quietOnly.checks)
    }

    /** The user's own takes (tools/wake-record.sh), only where they are: never in git. Numbers from tools/wake-test.py, 15 Nov. */
    @Test fun sameAsThePcTest() {
        val d = File(System.getProperty("user.home"), ".cache/tetra-wake")
        assumeTrue(File(d, "template5.wav").exists())
        fun wav(n: String): ShortArray = javax.sound.sampled.AudioSystem.getAudioInputStream(File(d, "$n.wav")).use { s ->
            val b = s.readAllBytes(); ShortArray(b.size / 2) { ((b[2 * it].toInt() and 0xff) or (b[2 * it + 1].toInt() shl 8)).toShort() } }
        // phrase -> its takes' prefix, the PC's leave-one-out distances, and held-out recordings' best distance over the median
        val pc = mapOf("Hey Tetra" to Triple("template", doubleArrayOf(13.22, 11.96, 16.61, 11.55),
                mapOf("template5" to 0.86, "held1" to 0.92, "fast" to 1.11, "slow" to 1.03, "far" to 1.20, "quiet" to 1.24)),
            "OK Tetra" to Triple("ok", doubleArrayOf(14.28, 19.50, 14.32, 14.12), mapOf("ok5" to 0.98)),
            "Tetra" to Triple("tetra", doubleArrayOf(11.54, 12.49, 11.86, 12.34), mapOf("tetra5" to 1.02)))
        pc.forEach { (name, t) ->
            val (prefix, looPc, held) = t
            val takes = (1..4).map { WakeWord.features(wav("$prefix$it")) }
            val loo = WakeWord.leaveOneOut(takes)
            looPc.forEachIndexed { i, v -> assertEquals("$name take ${i + 1}", v, loo[i], 0.05) }
            val median = WakeWord.median(loo)
            val scoreOnly = WakePhrase(name, takes, 1e-9) // never wakes: every look is scored, as on the PC
            held.forEach { (n, v) ->
                var best = Double.POSITIVE_INFINITY
                WakeDetector(listOf(scoreOnly)) { _, r -> best = minOf(best, r * 1e-9) }.push(wav(n))
                assertEquals("$name $n", v, best / median, 0.006)
            }
        }
    }
}
