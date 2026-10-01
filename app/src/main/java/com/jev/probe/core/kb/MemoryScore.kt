package com.jev.probe.core.kb

import kotlin.math.pow

/**
 * The sliding-window half of goal 4, kept separate from retrieval so the decay
 * curve is testable without a Context or a model.
 *
 * Old lines fade smoothly instead of falling off a hard "last N" cliff, which is
 * the whole point: the upstream knowledge base only ever looked at a fixed
 * window of recent messages, so anything just outside it vanished.
 */
object MemoryScore {

    /** Recency half-life, in messages back from the newest one. */
    const val HALF_LIFE = 40.0

    /** Weight of semantic similarity vs recency when ranking history. */
    const val W_SIM = 0.65
    const val W_RECENCY = 0.35

    /**
     * Similarity below this is not injected as memory.
     *
     * Set from measurement, not taste (tools/jev_local/eval_memory.py, the same
     * int8 model the app ships, 14 notes x 10 labelled queries): relevant pairs
     * scored 0.578-0.777, the best distractor averaged 0.553. The previous 0.28
     * kept 13.8 of 14 notes and filtered nothing; 0.50 keeps about 4.6 (the
     * inject cap is 5) and still kept every target in that set.
     *
     * A relative margin was measured too and rejected: "keep within delta of the
     * best" collapses to ~1.2 notes, which throws away complementary context.
     *
     * Be honest about what this buys: the mean distractor scored 0.553, which is
     * ABOVE 0.50, so the floor bounds how many notes get injected - it does not
     * guarantee that what is injected is relevant. Ranking decides that.
     */
    const val MIN_NOTE_SCORE = 0.50

    /**
     * Keep the highest-scoring entries at or above [floor], best first, at most
     * [topK]. Pure so the policy can be tested without an encoder or a Context.
     */
    fun <T> selectByScore(scored: List<Pair<T, Double>>, floor: Double, topK: Int): List<T> =
        scored.asSequence()
            .filter { it.second >= floor }
            .sortedByDescending { it.second }
            .take(topK)
            .map { it.first }
            .toList()

    /** 1.0 at the newest message, 0.5 one half-life back, 0.25 two. */
    fun recency(ageMessages: Double): Double = 0.5.pow(ageMessages / HALF_LIFE)

    /** Combined score. [similarity] is a cosine in [-1, 1]; [ageMessages] >= 0. */
    fun blend(similarity: Double, ageMessages: Double): Double =
        W_SIM * similarity + W_RECENCY * recency(ageMessages)
}
