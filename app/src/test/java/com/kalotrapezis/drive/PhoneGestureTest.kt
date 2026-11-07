package com.kalotrapezis.drive

import org.junit.Assert.assertEquals
import org.junit.Test

/** A ringing call's motion played through PhoneGesture, a sample of each sensor every 50 ms. */
class PhoneGestureTest {
    private class Play(raise: Boolean = true, flip: Boolean = true, light: Boolean = true) {
        val rule = PhoneGesture(raise, flip, light) { println(it) }
        var t = 1_000L
        val verdicts = mutableListOf<PhoneGesture.Verdict>()
        fun hold(ms: Int, x: Float, y: Float, z: Float, near: Boolean, lux: Float = 0f) = apply {
            repeat(ms / 50) {
                t += 50_000_000L
                listOfNotNull(rule.gravity(t, x, y, z), rule.proximity(t, near), rule.light(t, lux)).let(verdicts::addAll)
            }
        }
    }
    private val pocket = floatArrayOf(0.5f, -9.6f, 1.5f) // upside down, screen to the leg
    private val flat = floatArrayOf(0.3f, 3f, 9.3f)      // in the hand, looked at
    private val ear = floatArrayOf(2f, 9.2f, 1f)         // upright at the ear
    private fun Play.at(g: FloatArray, ms: Int, near: Boolean, lux: Float) = hold(ms, g[0], g[1], g[2], near, lux)

    @Test fun inThePocketFromTheStartNeverAnswers() {
        val p = Play().at(pocket, 5_000, near = true, lux = 0f).at(ear, 5_000, near = true, lux = 1f)
        assertEquals(emptyList<PhoneGesture.Verdict>(), p.verdicts)
    }

    @Test fun takenOutLookedAtAndRaisedToTheEarAnswers() {
        val p = Play().at(pocket, 1_000, near = true, lux = 0f).at(flat, 1_000, near = false, lux = 200f)
            .at(ear, 300, near = false, lux = 200f).at(ear, 500, near = true, lux = 1f)
        assertEquals(listOf(PhoneGesture.Verdict.ANSWER), p.verdicts)
    }

    @Test fun takenOutAndTurnedFaceDownSilencesAndNeverAnswers() {
        val p = Play().at(pocket, 1_000, near = true, lux = 0f).at(flat, 1_000, near = false, lux = 200f)
            .hold(100, 9.8f, 0f, 0f, near = false, lux = 150f) // on its side, half way over
            .hold(1_000, 0f, 0f, -9.8f, near = true, lux = 0f)
        assertEquals(listOf(PhoneGesture.Verdict.SILENCE), p.verdicts)
    }

    @Test fun uprightInAShirtPocketWithLightDoesNotAnswer() {
        val shirt = floatArrayOf(0.2f, 9.6f, 1.5f)
        val p = Play().at(shirt, 2_000, near = false, lux = 150f).at(shirt, 2_000, near = true, lux = 2f)
        assertEquals(emptyList<PhoneGesture.Verdict>(), p.verdicts)
    }

    @Test fun lookedAtThenPutAwayDoesNotAnswer() {
        val p = Play().at(flat, 1_000, near = false, lux = 200f).at(flat, 300, near = true, lux = 0f).at(ear, 2_000, near = true, lux = 0f)
        assertEquals(emptyList<PhoneGesture.Verdict>(), p.verdicts)
    }

    @Test fun withoutALightSensorTheLookTakesASecond() {
        val brief = Play(light = false).at(flat, 700, near = false, lux = 0f).at(ear, 300, near = false, lux = 0f).at(ear, 500, near = true, lux = 0f)
        assertEquals(emptyList<PhoneGesture.Verdict>(), brief.verdicts)
        val p = Play(light = false).at(flat, 1_200, near = false, lux = 0f).at(ear, 300, near = false, lux = 0f).at(ear, 500, near = true, lux = 0f)
        assertEquals(listOf(PhoneGesture.Verdict.ANSWER), p.verdicts)
    }
}
