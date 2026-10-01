package com.jev.probe.core.kb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Goal 4's sliding window: the decay curve and its blend with similarity, kept
 * separate from retrieval so it is testable without a Context or a model.
 */
class MemoryScoreTest {

    @Test
    fun recencyHalvesEveryHalfLife() {
        assertEquals(1.0, MemoryScore.recency(0.0), 1e-9)
        assertEquals(0.5, MemoryScore.recency(MemoryScore.HALF_LIFE), 1e-9)
        assertEquals(0.25, MemoryScore.recency(2 * MemoryScore.HALF_LIFE), 1e-9)
    }

    @Test
    fun recencyDecaysMonotonically() {
        var prev = Double.MAX_VALUE
        for (age in 0..200 step 10) {
            val v = MemoryScore.recency(age.toDouble())
            assertTrue("recency must not rise with age (age=" + age + ")", v < prev)
            prev = v
        }
    }

    @Test
    fun blendUsesTheDocumentedWeights() {
        assertEquals(1.0, MemoryScore.blend(1.0, 0.0), 1e-9)
        assertEquals(0.35, MemoryScore.blend(0.0, 0.0), 1e-9)
        assertEquals(0.65 + 0.35 * 0.5, MemoryScore.blend(1.0, MemoryScore.HALF_LIFE), 1e-9)
    }

    /**
     * The behaviour the goal asks for: a very old perfect match must not beat a
     * fresh reasonable one, otherwise "long-term memory" would dominate the
     * current conversation.
     */
    @Test
    fun selectDropsEntriesBelowTheFloor() {
        val scored = listOf("a" to 0.72, "b" to 0.51, "c" to 0.49, "d" to 0.10)
        assertEquals(listOf("a", "b"), MemoryScore.selectByScore(scored, 0.50, 10))
    }

    @Test
    fun selectReturnsBestFirstAndTruncates() {
        val scored = listOf("a" to 0.60, "b" to 0.90, "c" to 0.75)
        assertEquals(listOf("b", "c", "a"), MemoryScore.selectByScore(scored, 0.0, 3))
        assertEquals(listOf("b", "c"), MemoryScore.selectByScore(scored, 0.0, 2))
    }

    /**
     * Pins the measured floor and, more importantly, what it does NOT buy.
     *
     * 0.50 cuts the injected set from ~13.8 notes to ~4.6, but the mean distractor
     * scored 0.553 - above the floor - so the floor bounds volume rather than
     * guaranteeing relevance. An earlier version of this test claimed otherwise
     * and failed, which is the useful part: ranking is what selects.
     */
    @Test
    fun shippedFloorBoundsVolumeNotRelevance() {
        assertEquals(0.50, MemoryScore.MIN_NOTE_SCORE, 1e-9)
        val scored = listOf("relevant" to 0.687, "meanDistractor" to 0.553, "weak" to 0.31)
        assertEquals(listOf("relevant", "meanDistractor"),
            MemoryScore.selectByScore(scored, MemoryScore.MIN_NOTE_SCORE, 5))
        assertEquals(listOf("weak"), MemoryScore.selectByScore(listOf("weak" to 0.31), 0.0, 5))
        assertEquals(emptyList<String>(),
            MemoryScore.selectByScore(listOf("weak" to 0.31), MemoryScore.MIN_NOTE_SCORE, 5))
    }

    @Test
    fun freshnessOutweighsAnAncientPerfectMatch() {
        val ancientPerfect = MemoryScore.blend(1.0, 3 * MemoryScore.HALF_LIFE)
        val freshReasonable = MemoryScore.blend(0.7, 0.0)
        assertTrue(freshReasonable > ancientPerfect)
    }
}
