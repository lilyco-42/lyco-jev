package com.jev.probe.jev

import com.jev.probe.core.Analysis
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Prefs
import com.jev.probe.core.RankedReply
import com.jev.probe.core.kb.ChatContext

/**
 * Thin facade over the split clients so callers keep one entry point.
 * Construct with [Prefs] — every route reads its own address / key / model from
 * there, so switching providers in settings takes effect on the next call.
 *
 * Offline-first: when the judge route is [Prefs.PROVIDER_LOCAL] the candidate
 * replies are drafted on-device too ([LocalReplyClient]) rather than over HTTP,
 * so a local install never needs a network at any step.
 */
class JevClient(prefs: Prefs) {

    private val prefs = prefs
    private val judgeClient = JudgeClient(prefs)
    private val replyClient = ReplyClient(prefs)

    /** Created on first use — it is a second half-gigabyte model. */
    @Volatile private var localReply: LocalReplyClient? = null

    /** The 7 judgment questions. Errors come back inside [Analysis.error]. */
    fun judge(snapshot: ChatSnapshot, relationship: String, ctx: ChatContext? = null): Analysis =
        judgeClient.judge(snapshot, relationship, ctx)

    /** Draft 3 candidates on the reply route, then rank them on the judge route. */
    fun draftAndRank(
        snapshot: ChatSnapshot,
        relationship: String,
        ctx: ChatContext? = null
    ): List<RankedReply> {
        val candidates = draft(snapshot, relationship, ctx)
        return judgeClient.rank(snapshot, relationship, candidates, ctx)
    }

    /**
     * Local by default. A cloud judge keeps the cloud drafting route: someone who
     * configured an endpoint expects that endpoint to be the one used.
     */
    private fun draft(snapshot: ChatSnapshot, relationship: String, ctx: ChatContext?): List<String> {
        if (prefs.judgeProvider != Prefs.PROVIDER_LOCAL) {
            return replyClient.draft(snapshot, relationship, ctx)
        }
        val client = localReply ?: synchronized(this) {
            localReply ?: LocalReplyClient(prefs).also { localReply = it }
        }
        return client.draft(snapshot, relationship, ctx)
    }

    /** Judge + replies, sequential. Used by the settings connectivity test. */
    fun analyze(snapshot: ChatSnapshot, relationship: String, ctx: ChatContext? = null): Analysis {
        val a = judge(snapshot, relationship, ctx)
        if (a.error != null) return a
        val ranked = try { draftAndRank(snapshot, relationship, ctx) } catch (e: Exception) { emptyList() }
        return a.copy(rankedReplies = ranked)
    }
}
