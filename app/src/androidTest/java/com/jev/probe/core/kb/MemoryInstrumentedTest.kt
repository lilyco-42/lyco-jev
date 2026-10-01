package com.jev.probe.core.kb

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Goal 4's model half on a real device: does the 23 MB bge-small-zh encoder load
 * and does retrieval actually find a note that shares no substring with the
 * current screen? That is the entire reason this goal exists - the upstream
 * knowledge base only matched literal tags and titles.
 */
@RunWith(AndroidJUnit4::class)
class MemoryInstrumentedTest {

    private val tag = "JEVMEM-IT"

    @Test
    fun encoderLoadsAndRelatedTextBeatsUnrelated() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("encoder unavailable: " + EmbeddingIndex.loadError(ctx), EmbeddingIndex.available(ctx))

        val a = EmbeddingIndex.embed(ctx, "她讨厌别人临时取消约定")
        val b = EmbeddingIndex.embed(ctx, "我临时有事放了她的鸽子")
        val c = EmbeddingIndex.embed(ctx, "她不吃香菜和葱花")
        assertNotNull(a); assertNotNull(b); assertNotNull(c)

        val related = EmbeddingIndex.cosine(a!!, b!!)
        val unrelated = EmbeddingIndex.cosine(a, c!!)
        Log.i(tag, "cos(related)=" + related + " cos(unrelated)=" + unrelated)
        assertTrue("related (" + related + ") should beat unrelated (" + unrelated + ")",
            related > unrelated)
    }

    @Test
    fun retrievalFindsANoteWithNoSharedSubstring() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val notes = listOf(
            Note("n1", "被放鸽子", "她很在意约定，临时取消会让她觉得不被重视"),
            Note("n2", "口味", "她不吃香菜"),
            Note("n3", "工作", "她最近在赶一个季度汇报"))

        // No tag or title from n1 appears anywhere in this query.
        val query = "今天又惹她生气了，因为我临时改成加班"
        val hits = MemoryRetriever.notes(ctx, query, notes, 1)
        Log.i(tag, "query=[" + query + "] top=" + hits.map { it.id + ":" + it.title })
        assertTrue("no note retrieved; check MemoryRetriever.MIN_NOTE_SCORE", hits.isNotEmpty())
        assertEquals("n1", hits[0].id)
    }
}
