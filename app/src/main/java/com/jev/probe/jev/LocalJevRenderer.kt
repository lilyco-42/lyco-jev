package com.jev.probe.jev

import org.json.JSONArray
import org.json.JSONObject

/**
 * Kotlin port of the reference renderer ("macjev-render-v1"). The layout is
 * fixed by the shipped weights, so every piece is tokenised separately and
 * concatenated exactly as the reference runtime does:
 *
 *     State:\n<state>\n\n
 *     Question [<type>]: <instructions>\nOptions:\n
 *     - <option 1>\n ... - <option K>\n
 *     Judge each option:\n
 *     <option 1> ->\n ... <option K> ->\n
 *
 * The score of option k is logit(" yes") - logit(" no") at the last token of the
 * k-th " ->" segment, so only those tokens need a logits row.
 *
 * The tokenizer is injected rather than called directly, so the layout can be
 * pinned by a JVM unit test against vectors produced by the official Python
 * renderer (see LocalJevRendererTest). Use [native] on device.
 */
class LocalJevRenderer(
    val yesId: Int,
    val noId: Int,
    private val encoder: (String) -> IntArray
) {

    class Rendered(
        val ids: IntArray,
        val slots: IntArray,
        val names: List<String>,
        val qtype: String,
        val optionTexts: List<String>
    )

    private val dash = enc("- ")
    private val newline = enc("\n")
    private val judge = enc("Judge each option:\n")
    private val arrow = enc(" ->")
    private val openState = enc("State:\n")
    private val blankLine = enc("\n\n")

    fun enc(text: String): IntArray = encoder(text)

    private fun ArrayList<Int>.append(a: IntArray) { for (v in a) add(v) }

    fun render(stateJson: String, question: JSONObject): Rendered {
        val qtype = question.getString("type")
        val instructions = question.optString("instructions")
        val texts = optionTexts(question, qtype)
        val names = optionNames(question, qtype, texts.size)

        val prefix = ArrayList<Int>(64)
        prefix.append(openState)
        prefix.append(enc(stateJson))
        prefix.append(blankLine)

        val suffix = ArrayList<Int>(256)
        suffix.append(enc("Question [" + qtype + "]: " + instructions + "\nOptions:\n"))
        val encoded = ArrayList<IntArray>(texts.size)
        for (t in texts) {
            val ids = enc(t)
            encoded.add(ids)
            suffix.append(dash)
            suffix.append(ids)
            suffix.append(newline)
        }
        suffix.append(judge)

        val rel = IntArray(texts.size)
        for (i in texts.indices) {
            suffix.append(encoded[i])
            suffix.append(arrow)
            rel[i] = suffix.size - 1        // last token of this option's " ->"
            suffix.append(newline)
        }

        val ids = IntArray(prefix.size + suffix.size)
        for (i in prefix.indices) ids[i] = prefix[i]
        for (i in suffix.indices) ids[prefix.size + i] = suffix[i]
        val slots = IntArray(rel.size) { prefix.size + rel[it] }
        return Rendered(ids, slots, names, qtype, texts)
    }

    companion object {
        /** On-device renderer: token ids come from the bundled llama.cpp vocabulary. */
        fun native(handle: Long): LocalJevRenderer {
            val yes = LocalJevNative.nativeTokenId(handle, " yes")
            val no = LocalJevNative.nativeTokenId(handle, " no")
            require(yes >= 0) { "tokenizer has no single-token ' yes'" }
            require(no >= 0) { "tokenizer has no single-token ' no'" }
            return LocalJevRenderer(yes, no) { text ->
                LocalJevNative.nativeTokenize(handle, text) ?: IntArray(0)
            }
        }

        /** Choice -> criterion keys; noul -> false then true (the reference order). */
        fun optionTexts(question: JSONObject, qtype: String): List<String> = when (qtype) {
            "choice" -> {
                val c = question.optJSONObject("criteria") ?: JSONObject()
                c.keys().asSequence().map { k ->
                    val v = c.opt(k)
                    if (v == null || v == JSONObject.NULL || v.toString().isEmpty()) k else k + ": " + v
                }.toList()
            }
            "score" -> {
                val a: JSONArray = question.optJSONArray("criteria") ?: JSONArray()
                (0 until a.length()).map { i -> "level " + i + ": " + a.optString(i) }
            }
            else -> {
                val c = question.optJSONObject("criteria") ?: JSONObject()
                val f = c.optString("false")
                val t = c.optString("true")
                listOf(
                    "false: " + (if (f.isBlank()) "no, the statement does not hold" else f),
                    "true: " + (if (t.isBlank()) "yes, the statement holds" else t)
                )
            }
        }

        fun optionNames(question: JSONObject, qtype: String, n: Int): List<String> = when (qtype) {
            "choice" -> (question.optJSONObject("criteria") ?: JSONObject()).keys().asSequence().toList()
            "score" -> (0 until n).map { "level " + it }
            else -> listOf("false", "true")
        }
    }
}
