package com.jev.probe.core.kb

import android.content.Context
import android.util.Log
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Prefs

/**
 * Turns "what is on screen right now" into the extra context one analysis gets:
 * which contact this is, their older history, and which knowledge notes apply.
 *
 * Deliberately dumb and cheap (v1.3 revision): exact name/alias matching, plain
 * substring note matching, a hard character budget. No scoring, no embeddings,
 * no auto-created contacts, no network.
 */
object ContextBuilder {

    /** Notes + history together may not exceed this many characters. */
    const val BUDGET_CHARS = 1500

    /** At most this many matched (non-always-on) notes. */
    const val MAX_HIT_NOTES = 5

    /** Only the last few messages are searched for note keywords. */
    private const val MATCH_WINDOW = 6

    /** Identical on-screen copies this short are not worth de-duplicating. */
    private const val DEDUPE_MIN_LEN = 4

    /**
     * @param app package name of the foreground chat app (may be blank).
     * @return context for this snapshot; every field may be empty, which is the
     *         normal state before the user has built a knowledge base.
     */
    fun build(context: Context, snapshot: ChatSnapshot, app: String, prefs: Prefs): ChatContext {
        val store = KbStore.get(context)
        val title = snapshot.title ?: ""

        // 1. Contact — matched only, never created here.
        val contact = store.findContact(title, app)

        // Goal 4: retrieve by meaning + recency when the encoder is present.
        // Absent asset -> identical to the old substring behaviour.
        val semantic = prefs.semanticMemory && EmbeddingIndex.available(context)
        val query = queryText(snapshot)
        val want = prefs.contextHistoryCount.coerceIn(0, 100)

        // 2. History — recorded and injected only with the user's opt-in. In
        //    semantic mode read the whole retained window and re-rank it down.
        val history = if (prefs.contextEnabled && contact != null)
            historyFor(store, contact, snapshot, app, prefs,
                if (semantic) KbStore.MAX_LOG else want)
        else emptyList()

        // 3. Notes — always-on ones, plus the ones this screen is about.
        val enabled = store.notes().filter { it.enabled }
        val alwaysOn = enabled.filter { it.alwaysOn }
        val pool = enabled.filter { !it.alwaysOn }
        val hits = if (semantic) MemoryRetriever.notes(context, query, pool, MAX_HIT_NOTES)
        else matchNotes(pool, snapshot)

        // 4. Budget: always-on notes are exempt; the rest share BUDGET_CHARS,
        //    dropping oldest history first, then whole notes (never half a note).
        val trimmedHistory = ArrayList(history)
        val trimmedHits = ArrayList(hits)
        while (cost(trimmedHits, trimmedHistory) > BUDGET_CHARS && trimmedHistory.isNotEmpty())
            trimmedHistory.removeAt(0)
        while (cost(trimmedHits, trimmedHistory) > BUDGET_CHARS && trimmedHits.isNotEmpty())
            trimmedHits.removeAt(trimmedHits.size - 1)

        Log.d(TAG, "context: contact=${contact != null} notes=${alwaysOn.size + trimmedHits.size} " +
            "history=${trimmedHistory.size}")
        return ChatContext(contact, trimmedHistory, alwaysOn + trimmedHits)
    }

    /**
     * Record the visible messages, then read back the recent window minus the
     * copies already on screen (only for lines long enough that an exact repeat
     * is certainly the same message).
     */
    private fun historyFor(
        store: KbStore,
        contact: Contact,
        snapshot: ChatSnapshot,
        app: String,
        prefs: Prefs,
        limit: Int = prefs.contextHistoryCount.coerceIn(0, 100)
    ): List<LogEntry> {
        val now = System.currentTimeMillis()
        store.appendLog(contact.id, snapshot.messages.map { LogEntry(it.side, it.text, now, app) })
        val n = limit.coerceIn(0, KbStore.MAX_LOG)
        if (n == 0) return emptyList()
        val onScreen = snapshot.messages
            .filter { it.text.length >= DEDUPE_MIN_LEN }
            .map { it.side + " " + it.text }
            .toHashSet()
        // Filter the widest window FIRST, then take n. The other order lets the
        // messages currently on screen eat into the quota — ask for 30 lines of
        // history, get 30 minus however many are already visible.
        return store.recentLog(contact.id, KbStore.MAX_LOG)
            .filter { (it.side + " " + it.text) !in onScreen }
            .takeLast(n)
    }

    /**
     * A note matches when any of its tags, or its title, appears in the
     * conversation title or in the last few messages. Newest notes win the
     * [MAX_HIT_NOTES] cap.
     */
    /** The current screen as one string: the query the retriever scores against. */
    private fun queryText(snapshot: ChatSnapshot): String = buildString {
        append(snapshot.title ?: "")
        snapshot.messages.takeLast(MATCH_WINDOW).forEach { append('\n').append(it.text) }
    }

    private fun matchNotes(candidates: List<Note>, snapshot: ChatSnapshot): List<Note> {
        if (candidates.isEmpty()) return emptyList()
        val haystack = buildString {
            append(KbStore.normalizeText(snapshot.title))
            snapshot.messages.takeLast(MATCH_WINDOW).forEach {
                append('\n').append(KbStore.normalizeText(it.text))
            }
        }
        if (haystack.isBlank()) return emptyList()
        return candidates
            .filter { note ->
                (note.tags + note.title).any { raw ->
                    val needle = KbStore.normalizeText(raw)
                    needle.isNotEmpty() && haystack.contains(needle)
                }
            }
            .sortedByDescending { it.updatedAt }
            .take(MAX_HIT_NOTES)
    }

    private fun cost(notes: List<Note>, history: List<LogEntry>): Int =
        notes.sumOf { it.title.length + it.content.length + 2 } +
            history.sumOf { it.text.length + 3 }

    private const val TAG = "JEVASSIST"
}
