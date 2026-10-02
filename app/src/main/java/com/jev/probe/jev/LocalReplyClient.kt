package com.jev.probe.jev

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Prefs
import com.jev.probe.core.kb.ChatContext

/**
 * The drafting route with no network and no API key: 3 candidate replies from a
 * bundled generative weight, which the judge then ranks exactly as before.
 *
 * Jev-Style cannot draft - it answers only yes/no - so this uses the second GGUF
 * ([LocalReplyModel], Qwen3.5-0.8B, the same base Jev-Style was fine-tuned from).
 * The prompt and parser come from [ReplyPrompt], shared with the cloud route, so
 * the two produce candidates of the same shape.
 */
class LocalReplyClient(private val prefs: Prefs) {

    private val context: Context get() = prefs.appContext

    @Volatile private var handle: Long = 0L
    private val lock = Any()

    private fun ensureHandle(): Long {
        val cur = handle
        if (cur != 0L) return cur
        synchronized(lock) {
            if (handle != 0L) return handle
            LocalJevNative.ensureLoaded()?.let { throw IllegalStateException(it) }
            // Same first-run courtesy as the judge: half a gigabyte with no
            // feedback is indistinguishable from a hang.
            val firstRun = !LocalReplyModel.isPresent(context)
            if (firstRun) {
                announce("首次使用：正在准备本地起草模型（约 508 MB，仅此一次），请稍候…")
            }
            val model = LocalReplyModel.ensure(context)
            if (firstRun) {
                announce("本地起草模型已就绪，候选回复此后完全离线。")
            }
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
            val h = LocalJevNative.nativeLoad(model.absolutePath, N_CTX, threads)
            if (h == 0L) throw IllegalStateException("本地起草模型加载失败：" + model.name)
            handle = h
            Log.i(TAG, "local draft ready: " + model.name + " ctx=" + N_CTX + " threads=" + threads)
            return h
        }
    }

    /** Safe to call from the drafting worker thread. */
    private fun announce(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    fun close() {
        synchronized(lock) {
            if (handle != 0L) {
                LocalJevNative.nativeFree(handle)
                handle = 0L
            }
        }
    }

    /** @return exactly 3 candidates, same shape as [ReplyClient.draft]. */
    fun draft(snapshot: ChatSnapshot, relationship: String, ctx: ChatContext? = null): List<String> {
        val h = ensureHandle()
        val user = ReplyPrompt.user(
            relationship, ctx, ReplyPrompt.convo(snapshot), prefs.contextHistoryCount
        )
        // The prompt is rendered with the model's own chat template inside native
        // code; doing it in Kotlin would mean guessing a template that ships with
        // the GGUF and can change between releases. skipThinking = true because
        // Qwen3.5 otherwise spends the entire budget inside <think> - measured,
        // and not fixable from the prompt text.
        val ids = LocalJevNative.nativeChatPrompt(h, ReplyPrompt.SYSTEM, user, true)
            ?: throw IllegalStateException("本地起草：题面渲染失败")
        val t0 = System.currentTimeMillis()
        val text = LocalJevNative.nativeGenerate(h, ids, MAX_NEW_TOKENS, TEMPERATURE, TOP_P)
            ?: throw IllegalStateException("本地起草：生成失败")
        Log.i(TAG, "local draft: " + text.length + " chars in " + (System.currentTimeMillis() - t0) + "ms")
        return ReplyPrompt.parseThree(text)
    }

    companion object {
        private const val TAG = "JEVLOCAL"

        /** Prompt + 200 tokens must fit; the prompt is a few hundred tokens at most. */
        private const val N_CTX = 4096
        private const val MAX_NEW_TOKENS = 200
        private const val TEMPERATURE = 0.8f
        private const val TOP_P = 0.9f
    }
}
