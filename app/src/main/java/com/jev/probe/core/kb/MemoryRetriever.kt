package com.jev.probe.core.kb

import android.content.Context

/**
 * Goal 4, the retrieval half: score what to put in front of the judge model
 * instead of "whatever literally matched".
 *
 * Two signals, both cheap:
 *  - semantic similarity to the current screen (bge-small-zh, 512 dims), so
 *    "她又生气了" still finds a note titled "她讨厌被放鸽子";
 *  - recency with a half-life over messages, which is the sliding-window idea
 *    applied at retrieval time: old lines fade smoothly instead of falling off
 *    a hard "last N" cliff.
 *
 * Everything degrades to the old lexical behaviour when the encoder is missing,
 * so a build without the 23 MB asset still works — it just remembers less.
 */
object MemoryRetriever {

    fun notes(ctx: Context, query: String, candidates: List<Note>, topK: Int): List<Note> {
        if (candidates.isEmpty() || topK <= 0) return emptyList()
        val q = EmbeddingIndex.embed(ctx, query)
        if (q == null) return lexicalNotes(query, candidates, topK)
        val scored = candidates.mapNotNull { note ->
            val v = EmbeddingIndex.embed(ctx, note.title + "\n" + note.content)
                ?: return@mapNotNull null
            note to EmbeddingIndex.cosine(q, v).toDouble()
        }
        return MemoryScore.selectByScore(scored, MemoryScore.MIN_NOTE_SCORE, topK)
    }

    fun history(ctx: Context, query: String, entries: List<LogEntry>, n: Int): List<LogEntry> {
        if (entries.isEmpty() || n <= 0) return emptyList()
        val q = EmbeddingIndex.embed(ctx, query)
        if (q == null) return entries.takeLast(n)
        val total = entries.size
        return entries
            .mapIndexed { i, e ->
                val age = (total - 1 - i).toDouble()
                val sim = EmbeddingIndex.embed(ctx, e.text)?.let { EmbeddingIndex.cosine(q, it).toDouble() } ?: 0.0
                e to MemoryScore.blend(sim, age)
            }
            .sortedByDescending { it.second }
            .take(n)
            .sortedBy { it.first.ts }      // hand them back in chronological order
            .map { it.first }
    }

    private fun lexicalNotes(query: String, candidates: List<Note>, topK: Int): List<Note> {
        val q = KbStore.normalizeText(query)
        if (q.isBlank()) return emptyList()
        return candidates
            .filter { note ->
                (note.tags + note.title).any { raw ->
                    val needle = KbStore.normalizeText(raw)
                    needle.isNotEmpty() && q.contains(needle)
                }
            }
            .sortedByDescending { it.updatedAt }
            .take(topK)
    }
}
