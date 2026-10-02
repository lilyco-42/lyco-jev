package com.jev.probe.jev

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp

/**
 * Goal 1: the judge route with no network and no API key. Runs the bundled
 * Jev-Style-0.8B (Apache-2.0) through llama.cpp in-process and returns the same
 * \`answers\` object the cloud \`/v1/systemone\` route returns, so [JudgeClient]
 * keeps one parser for both.
 *
 * Scoring is the reference readout: score = logit(" yes") - logit(" no") at each
 * option's " ->" slot, then softmax(scores / T) with the shipped global
 * calibration temperature.
 */
class LocalJudgeClient(private val context: Context) {

    /** readout_config.json -> temperatures.global (used when no category is given). */
    private val temperature = 0.880f

    /**
     * Where the one-off weight preparation reports progress.
     *
     * The weight ships inside the APK, so this is an unpack, not a download - but
     * it is still 505 MB of I/O on first use, and even a warm start spends time in
     * nativeLoad. Without a live readout the panel sat on a static "分析中…" through
     * both, which is indistinguishable from a hang. Set by the caller before
     * [decide]; invoked from the judge worker thread.
     */
    @Volatile var onPrepare: ((done: Long, total: Long, extracting: Boolean) -> Unit)? = null

    @Volatile private var handle: Long = 0L
    private val lock = Any()

    private fun ensureHandle(): Long {
        val cur = handle
        if (cur != 0L) return cur
        synchronized(lock) {
            if (handle != 0L) return handle
            LocalJevNative.ensureLoaded()?.let { throw IllegalStateException(it) }
            // The 0.53 GB is bundled in the APK and gets unpacked to filesDir on
            // first use. It used to say "正在下载" here, which was simply untrue:
            // no network is involved, and a user with no connection was told to
            // wait for a download that would never happen.
            val firstRun = !LocalJevModel.isPresent(context)
            val model = LocalJevModel.ensure(
                context,
                onStage = { stage ->
                    onPrepare?.invoke(0L, -1L, stage == ModelStore.Stage.EXTRACTING)
                },
                onProgress = { done, total -> onPrepare?.invoke(done, total, true) },
            )
            if (firstRun) {
                announce("端侧判读模型已就绪，之后判读完全离线。")
            }
            // total = -2: unpacking is done, mmap + warm-up is not. Otherwise the
            // panel shows a finished progress bar and then sits there again.
            onPrepare?.invoke(0L, -2L, true)
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
            val h = LocalJevNative.nativeLoad(model.absolutePath, N_CTX, threads)
            if (h == 0L) throw IllegalStateException("本地模型加载失败：" + model.name)
            handle = h
            Log.i(TAG, "local judge ready: " + model.name + " ctx=" + N_CTX + " threads=" + threads)
            return h
        }
    }

    /** Safe to call from the judge worker thread. */
    private fun announce(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    fun close() {
        synchronized(lock) {
            if (handle != 0L) { LocalJevNative.nativeFree(handle); handle = 0L }
        }
    }

    /** @return the \`answers\` object, one entry per question key. */
    fun decide(state: JSONObject, questions: JSONObject): JSONObject {
        val h = ensureHandle()
        val renderer = LocalJevRenderer.native(h)
        val answers = JSONObject()
        var totalMs = 0L
        for (key in questions.keys()) {
            val q = questions.getJSONObject(key)
            val rendered = renderer.render(pythonJson(state), q)
            val t0 = System.currentTimeMillis()
            val scores = LocalJevNative.nativeDecide(h, rendered.ids, rendered.slots, renderer.yesId, renderer.noId)
                ?: throw IllegalStateException("本地推理失败：" + key)
            totalMs += System.currentTimeMillis() - t0
            answers.put(key, toAnswer(rendered, softmax(scores, temperature)))
        }
        Log.i(TAG, "local judge: " + questions.length() + " questions in " + totalMs + "ms")
        return answers
    }

    // ------------------------------------------------------------ calibration

    private fun softmax(scores: FloatArray, t: Float): DoubleArray {
        val tt = if (t <= 0f) 1f else t
        var max = Double.NEGATIVE_INFINITY
        for (s in scores) {
            val v = (s / tt).toDouble()
            if (v > max) max = v
        }
        val out = DoubleArray(scores.size)
        var sum = 0.0
        for (i in scores.indices) {
            out[i] = exp((scores[i] / tt).toDouble() - max)
            sum += out[i]
        }
        if (sum <= 0.0) { for (i in out.indices) out[i] = 1.0 / out.size; return out }
        for (i in out.indices) out[i] /= sum
        return out
    }

    private fun toAnswer(r: LocalJevRenderer.Rendered, probs: DoubleArray): JSONObject {
        val o = JSONObject()
        val byName = JSONObject()
        for (i in r.names.indices) byName.put(r.names[i], probs.getOrElse(i) { 0.0 })
        o.put("probabilities", byName)
        when (r.qtype) {
            "choice" -> {
                val best = probs.indices.maxByOrNull { probs[it] } ?: 0
                o.put("choice", r.names[best])
                o.put("confidence", probs[best])
            }
            "score" -> {
                // The reference reports a score question as the probability-weighted
                // mean over levels ("danger 2.34"), not the argmax level. Callers and
                // the auto-send danger ceiling both depend on that.
                var expected = 0.0
                for (i in probs.indices) expected += i * probs[i]
                val legend = JSONObject()
                for (i in r.optionTexts.indices) legend.put(i.toString(), r.optionTexts[i])
                o.put("score", expected)
                o.put("confidence", probs.maxOrNull() ?: 0.0)
                o.put("legend", legend)
            }
            else -> {
                val trueIdx = r.names.indexOf("true")
                o.put("noul", if (trueIdx >= 0) probs[trueIdx] else 0.0)
            }
        }
        return o
    }

    companion object {
        private const val TAG = "JEVLOCAL"
        private const val N_CTX = 8192

        /**
         * Python \`json.dumps(obj, ensure_ascii=False)\` spacing, because the state is
         * tokenised and the reference renders it with ", " / ": " separators.
         * Android's JSONObject.toString() is compact and would tokenise differently.
         */
        fun pythonJson(v: Any?): String = when (v) {
            null, JSONObject.NULL -> "null"
            is JSONObject -> v.keys().asSequence()
                .joinToString(", ", "{", "}") { k -> JSONObject.quote(k) + ": " + pythonJson(v.opt(k)) }
            is JSONArray -> (0 until v.length())
                .joinToString(", ", "[", "]") { i -> pythonJson(v.opt(i)) }
            is String -> JSONObject.quote(v)
            is Boolean, is Number -> v.toString()
            else -> JSONObject.quote(v.toString())
        }
    }
}
