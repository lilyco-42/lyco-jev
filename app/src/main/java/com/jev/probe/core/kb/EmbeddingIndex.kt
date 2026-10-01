package com.jev.probe.core.kb

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * Goal 4, the model half: a 23 MB int8 sentence encoder (bge-small-zh-v1.5) on
 * the on-device ONNX Runtime.
 *
 * The upstream knowledge base only matches notes when a tag or title *literally*
 * appears in the last few messages, which is why it feels like the phone "forgets"
 * the moment you phrase something differently. A 512-dim sentence vector fixes
 * that for 23 MB and no network, which is the trade this goal is about.
 *
 * The tokenizer is [BertTokenizer], vendored from the MIT-licensed sibling
 * project jev-wingman rather than rewritten: WordPiece plus the normaliser is
 * exactly the kind of code that is wrong in subtle ways.
 *
 * Embeddings are cached per process; notes are re-embedded only when their text
 * changes because the cache is keyed by the text itself.
 */
object EmbeddingIndex {

    private const val TAG = "JEVMEM"
    private const val MODEL_ASSET = "models/bge-small-zh/model_quantized.onnx"
    private const val VOCAB_ASSET = "models/bge-small-zh/vocab.txt"
    private const val MAX_LEN = 128
    private const val CACHE_LIMIT = 1024

    private class Loaded(val env: OrtEnvironment, val session: OrtSession, val tk: BertTokenizer)

    @Volatile private var loaded: Loaded? = null
    @Volatile private var loadError: String? = null
    @Volatile private var attempted = false
    @Volatile private var appCtx: Context? = null
    private val cache = ConcurrentHashMap<String, FloatArray>()

    /** null when the embedder is fine, otherwise the reason it is not. */
    fun loadError(ctx: Context): String? {
        ensure(ctx)
        return loadError
    }

    fun available(ctx: Context): Boolean = ensure(ctx) != null

    fun embed(ctx: Context, text: String): FloatArray? {
        if (text.isBlank()) return null
        val l = ensure(ctx) ?: return null
        cache[text]?.let { return it }
        return try {
            val v = normalize(run(l, text))
            if (cache.size < CACHE_LIMIT) cache[text] = v
            v
        } catch (t: Throwable) {
            Log.w(TAG, "embed failed: " + t.javaClass.simpleName + ": " + t.message)
            null
        }
    }

    /** Both sides are L2-normalised, so the dot product is the cosine. */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val n = minOf(a.size, b.size)
        var s = 0f
        for (i in 0 until n) s += a[i] * b[i]
        return s
    }

    private fun ensure(ctx: Context): Loaded? {
        loaded?.let { return it }
        synchronized(this) {
            loaded?.let { return it }
            if (attempted) return null
            attempted = true
            val app = ctx.applicationContext
            appCtx = app
            return try {
                val model = readAsset(app, MODEL_ASSET)
                val tk = app.assets.open(VOCAB_ASSET).use {
                    BertTokenizer.fromReader(InputStreamReader(it, StandardCharsets.UTF_8))
                }
                val env = OrtEnvironment.getEnvironment()
                val opts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(2)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                val session = env.createSession(model, opts)
                Loaded(env, session, tk).also {
                    loaded = it
                    Log.i(TAG, "sentence encoder ready")
                }
            } catch (t: Throwable) {
                loadError = t.javaClass.simpleName + ": " + t.message
                Log.w(TAG, "sentence encoder unavailable: " + loadError)
                null
            }
        }
    }

    private fun run(l: Loaded, text: String): FloatArray {
        val ids = l.tk.encode(text, MAX_LEN)
        val n = ids.size
        require(n > 0) { "tokenizer produced no ids" }
        val inputIds = LongArray(n)
        val mask = LongArray(n)
        val types = LongArray(n)
        for (i in 0 until n) {
            inputIds[i] = ids[i].toLong()
            mask[i] = 1L
            types[i] = 0L
        }
        val shape = longArrayOf(1, n.toLong())
        OnnxTensor.createTensor(l.env, LongBuffer.wrap(inputIds), shape).use { tIds ->
            OnnxTensor.createTensor(l.env, LongBuffer.wrap(mask), shape).use { tMask ->
                OnnxTensor.createTensor(l.env, LongBuffer.wrap(types), shape).use { tTypes ->
                    val inputs = linkedMapOf(
                        "input_ids" to tIds,
                        "attention_mask" to tMask,
                        "token_type_ids" to tTypes
                    )
                    l.session.run(inputs).use { r ->
                        @Suppress("UNCHECKED_CAST")
                        val hidden = r[0].value as Array<Array<FloatArray>>
                        return hidden[0][0]   // [CLS]
                    }
                }
            }
        }
    }

    private fun normalize(v: FloatArray): FloatArray {
        var s = 0f
        for (x in v) s += x * x
        val norm = sqrt(s)
        if (norm < 1e-9f) return v
        return FloatArray(v.size) { v[it] / norm }
    }

    private fun readAsset(ctx: Context, path: String): ByteArray =
        ctx.assets.open(path).use { input: InputStream ->
            ByteArrayOutputStream(1 shl 20).also { out -> input.copyTo(out) }.toByteArray()
        }
}
