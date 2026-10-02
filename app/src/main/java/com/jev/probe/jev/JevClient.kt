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

    /**
     * The shade copy of the progress readout.
     *
     * The panel only shows while its window is up and the user is looking at it;
     * the unpack runs on the first judgment, which is when the user is most
     * likely to lock the phone. Without this there was nothing at all to look at.
     */
    private val notifier = ModelPrepNotifier(prefs.appContext)

    @Volatile private var sawExtracting = false

    /**
     * Live progress while a bundled weight is unpacked and mapped.
     *
     * The caller's handler drives the panel; the notification is mirrored here so
     * no caller has to know it exists. Both routes can carry the cost: the judge
     * on the first judgment, the drafter on the first draft. See
     * [LocalJudgeClient.onPrepare] for the total = -1 / -2 contract.
     */
    @Volatile var onPrepare: ((done: Long, total: Long, extracting: Boolean) -> Unit)? = null
        set(value) {
            field = value
            // Wrap rather than forward verbatim: the local clients call exactly one
            // hook, and forgetting to mirror it here would silently drop the shade
            // notification while the panel kept working.
            val wrapped: ((Long, Long, Boolean) -> Unit)? = value?.let { cb ->
                { done, total, extracting ->
                    notifyProgress(done, total, extracting)
                    cb(done, total, extracting)
                }
            }
            judgeClient.onPrepare = wrapped
            localReply?.onPrepare = wrapped
        }

    /**
     * Feeds one preparation tick to the notification.
     *
     * @param total -2 means "unpacked, now mapping"; that is the last tick of the
     *   copy, so the notch progress is replaced by an indeterminate one there and
     *   [finishPrepare] posts the completion notice afterwards.
     */
    private fun notifyProgress(done: Long, total: Long, extracting: Boolean) {
        if (extracting) sawExtracting = true
        notifier.progress(extracting, done, total)
    }

    /** Clears the progress notification after a round, success or failure. */
    fun finishPrepare(ok: Boolean) {
        if (ok) notifier.done(sawExtracting) else notifier.cancel()
        sawExtracting = false
    }

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
            localReply ?: LocalReplyClient(prefs).also {
                it.onPrepare = onPrepare
                localReply = it
            }
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
