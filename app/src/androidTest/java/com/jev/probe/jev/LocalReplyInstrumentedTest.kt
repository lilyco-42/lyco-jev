package com.jev.probe.jev

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The drafting route executed on a device, with no network involved at any point.
 *
 * This is the test that decides whether "everything on-device, offline first"
 * actually holds: before it, [com.jev.probe.jev.ReplyClient] was the only source of
 * candidate replies and it is a plain HTTP call, so an offline install had a
 * judgment but no options - and the options are the whole galgame idea.
 *
 * The 508 MB reply GGUF is not bundled in a local build; stage it first:
 *   adb push Qwen3.5-0.8B-Q4_K_M.gguf /data/local/tmp/
 *
 * Expected to be slow on an emulator (no GPU, no AVX-512, QEMU overhead) - the
 * point here is that it produces three usable candidates at all; the on-device
 * latency is a separate question.
 */
@RunWith(AndroidJUnit4::class)
class LocalReplyInstrumentedTest {

    private val tag = "JEVREPLY-IT"

    private fun stage(ctx: android.content.Context): File {
        val f = File(ctx.filesDir, LocalReplyModel.FILE_NAME)
        if (!f.isFile) {
            val staged = File("/data/local/tmp/" + LocalReplyModel.FILE_NAME)
            if (staged.isFile) {
                Log.i(tag, "staging " + staged.length() + " bytes -> " + f.absolutePath)
                staged.copyTo(f, overwrite = true)
            }
        }
        assumeTrue("reply GGUF missing; adb push it to /data/local/tmp/ first", f.isFile)
        return f
    }

    @Test
    fun draftsThreeCandidatesWithNoNetwork() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        stage(ctx)
        LocalJevNative.ensureLoaded()?.let { throw AssertionError(it) }

        val snapshot = ChatSnapshot(
            "小雨", listOf(
                Msg("me", "今晚加班，可能晚点回"),
                Msg("other", "又是加班"),
                Msg("me", "项目赶"),
                Msg("other", "行，你忙"),
                Msg("other", "上次说好陪我看的那个电影，你还记得是哪部吗")
            )
        )

        val t0 = System.currentTimeMillis()
        val out = LocalReplyClient(Prefs(ctx)).draft(snapshot, "对方是我的伴侣")
        val dt = System.currentTimeMillis() - t0
        Log.i(tag, "draft in " + dt + "ms (" + (if (dt > 0) out.size * 1000L / dt else 0) + " cand/s): " + out)
        println("JEVREPLY_IT " + out)

        assertEquals("must return exactly 3 candidates", 3, out.size)
        for (s in out) {
            assertTrue("empty candidate", s.isNotBlank())
            // The parser's placeholder means the model produced nothing usable, which
            // is a failure even though the list has 3 entries.
            assertTrue("parser fell back to filler: " + s, s != ReplyPrompt.FILLER)
        }
    }
}
