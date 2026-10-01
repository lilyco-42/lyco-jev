package com.jev.probe.jev

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Goal 1 executed on a device: the parts a JVM test cannot reach at all.
 *
 *  - [nativeScorerReadsYesNoLogits] is the fast probe. It loads the real GGUF
 *    through llama.cpp, tokenises, decodes a tiny render and reads the
 *    " yes"/" no" logits. This is what caught the logits-indexing bug: passing a
 *    running output counter instead of the batch token index aborts inside
 *    llama_context::get_logits_ith.
 *
 *  - [oneQuestionRunsEndToEnd] then drives one real question through the whole
 *    render -> decode -> softmax -> answers chain. Deliberately ONE question:
 *    the emulator has no GPU, and the full 7-question set takes the better part
 *    of an hour here.
 *
 * The 0.53 GB GGUF is not bundled; stage it first:
 *   adb push Jev-Style-0.8B-Decision-v3-Q4_K_M.gguf /data/local/tmp/
 */
@RunWith(AndroidJUnit4::class)
class LocalJudgeInstrumentedTest {

    private val tag = "JEVLOCAL-IT"

    private fun model(ctx: android.content.Context): File {
        val f = File(ctx.filesDir, LocalJevModel.FILE_NAME)
        if (!f.isFile) {
            val staged = File("/data/local/tmp/" + LocalJevModel.FILE_NAME)
            if (staged.isFile) {
                Log.i(tag, "staging " + staged.length() + " bytes -> " + f.absolutePath)
                staged.copyTo(f, overwrite = true)
            }
        }
        assumeTrue("GGUF missing; adb push it to /data/local/tmp/ first", f.isFile)
        return f
    }

    @Test
    fun nativeScorerReadsYesNoLogits() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val m = model(ctx)
        LocalJevNative.ensureLoaded()?.let { throw AssertionError(it) }

        val h = LocalJevNative.nativeLoad(m.absolutePath, 2048, 4)
        assertTrue("nativeLoad returned 0", h != 0L)
        try {
            val render = "State:\nhi\n\nQuestion [choice]: pick\nOptions:\n- a\n- b\n" +
                "Judge each option:\na ->\nb ->\n"
            val ids = LocalJevNative.nativeTokenize(h, render)
            assertNotNull("tokenize returned null", ids)
            assertTrue("tokenize produced nothing", (ids?.size ?: 0) > 10)

            val yes = LocalJevNative.nativeTokenId(h, " yes")
            val no = LocalJevNative.nativeTokenId(h, " no")
            assertTrue("no single-token ' yes'", yes >= 0)
            assertTrue("no single-token ' no'", no >= 0)

            val n = ids!!.size
            val t0 = System.currentTimeMillis()
            val scores = LocalJevNative.nativeDecide(h, ids, intArrayOf(n - 2, n - 1), yes, no)
            val t1 = System.currentTimeMillis()
            // Second identical decode separates one-off graph build/reserve cost
            // from genuine per-token cost, which is what decides if a phone can
            // run this at all.
            LocalJevNative.nativeDecide(h, ids, intArrayOf(n - 2, n - 1), yes, no)
            val t2 = System.currentTimeMillis()
            Log.i(tag, "tiny decode1=" + (t1 - t0) + "ms decode2=" + (t2 - t1) + "ms tokens=" + n)
            assertNotNull("nativeDecide returned null", scores)
            assertEquals(2, scores!!.size)
            for (s in scores) assertTrue("non-finite score: " + s, s.isFinite())
            Log.i(tag, "tiny scores = " + scores.toList())
        } finally {
            LocalJevNative.nativeFree(h)
        }
    }

    @Test
    fun oneQuestionRunsEndToEnd() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        model(ctx)

        val snapshot = ChatSnapshot("小雨", listOf(
            Msg("me", "今晚加班，可能晚点回"),
            Msg("other", "又是加班"),
            Msg("other", "行，你忙"),
            Msg("other", "上次说好陪我看的那个电影，你还记得是哪部吗")))
        val state = JevQuestions.buildState(snapshot, "对方是我的伴侣")
        val all = JevQuestions.judge()
        val subset = JSONObject().put("true_intent", all.getJSONObject("true_intent"))

        val t0 = System.currentTimeMillis()
        val answers = LocalJudgeClient(ctx).decide(state, subset)
        Log.i(tag, "one question in " + (System.currentTimeMillis() - t0) + " ms: " + answers)
        println("JEVLOCAL_IT " + answers)

        val intent = answers.getJSONObject("true_intent")
        val choice = intent.optString("choice")
        assertTrue("empty choice", choice.isNotBlank())
        // Must be one of the criteria keys the question actually offered.
        val allowed = all.getJSONObject("true_intent").getJSONObject("criteria").keys().asSequence().toList()
        assertTrue("choice $choice not among $allowed", choice in allowed)

        var sum = 0.0
        val probs = intent.getJSONObject("probabilities")
        for (k in probs.keys()) sum += probs.getDouble(k)
        assertEquals("probabilities must sum to 1", 1.0, sum, 0.02)
    }
}
