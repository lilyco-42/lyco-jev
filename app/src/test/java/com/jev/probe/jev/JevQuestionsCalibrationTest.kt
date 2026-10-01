package com.jev.probe.jev

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two calibration lessons that tools/jev_local/calibrate.py measured.
 *
 * The wording in [JevQuestions] is not free text: it was scored against a small
 * regression suite on the open Jev-Style-0.8B weights. Editing it casually drops
 * accuracy from 9/10 back to 6/10, and the failure mode is subtle enough that
 * only this test catches it. Re-run the harness after any change here.
 */
class JevQuestionsCalibrationTest {

    private val intent = JevQuestions.judge().getJSONObject("true_intent")
    private val crit = intent.getJSONObject("criteria")

    /** The instruction must keep steering the memory-test case explicitly. */
    @Test
    fun instructionKeepsTheMemoryTestCue() {
        val ins = intent.getString("instructions")
        // v6: without naming the push phrasing, "我上次说我喜欢吃什么来着 / 你说啊"
        // lands on casual_chat (9/10 -> 10/10 depends on this sentence).
        assertTrue("instruction lost the memory-test cue",
            ins.contains("told you before") && ins.contains("then say it") &&
                ins.contains("confirm_you_care"))
    }

    /**
     * close_topic must not mention another label's signal words. The old text
     * contained "not sarcastic 'I'm used to it'", and that case then scored
     * close_topic at p=0.807 instead of confirm_you_care.
     */
    @Test
    fun noCriterionNamesAnotherLabelsSignals() {
        val close = crit.getString("close_topic")
        assertFalse("close_topic mentions sarcastic, which pulled the case to itself: " + close,
            close.contains("sarcastic", ignoreCase = true))
        assertFalse("close_topic mentions 'used to it': " + close,
            close.contains("used to it", ignoreCase = true))
    }

    /** confirm_you_care is where those signals belong now. */
    @Test
    fun careCriterionCarriesTheSignals() {
        val care = crit.getString("confirm_you_care")
        assertTrue("confirm_you_care lost the 'I'm used to it' signal: " + care,
            care.contains("used to it"))
    }

    /**
     * Long blurbs cost two cases; keep each criterion to a single short line.
     * The budget is 200 chars: the calibrated confirm_you_care line is 164, while
     * the wording this replaced ran 230-290.
     */
    @Test
    fun criteriaStayShort() {
        val budget = 200
        for (k in crit.keys()) {
            val text = crit.getString(k)
            assertTrue("criterion " + k + " is " + text.length + " chars; keep it under " +
                budget + ": " + text, text.length <= budget)
        }
    }
}
