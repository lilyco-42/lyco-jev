package com.jev.probe.jev

import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.kb.ChatContext
import org.json.JSONArray

/**
 * The drafting prompt, shared by the cloud route ([ReplyClient]) and the on-device
 * route ([LocalReplyClient]).
 *
 * Both must hand the judge's ranking question candidates of the same shape and
 * tone, otherwise "which reply is best" is comparing apples to oranges depending
 * on whether the phone happened to have a network.
 */
internal object ReplyPrompt {

    /** Exactly 3 varied candidate replies in Chinese. */
    const val SYSTEM =
        "你是中文即时通讯回复助手。只输出一个 JSON 数组，含且仅含 3 条候选回复文本，" +
            "三条策略要有区别（例如：一条稳妥承接、一条给具体行动或承诺、一条简短低姿态）。" +
            "每条不超过 40 字，口语、自然、像真人在聊天软件里发消息。不要解释，不要加引号以外的内容，直接输出 JSON 数组。"

    /** The last 10 turns as "我：… / 对方：…" lines. Shared so both routes see the same text. */
    fun convo(snapshot: ChatSnapshot): String = snapshot.messages.takeLast(10).joinToString("\n") {
        (if (it.side == "me") "我" else "对方") + "：" + it.text
    }

    /** The background + history preamble; empty string when there is no context. */
    fun knowledgeBlock(relationship: String, ctx: ChatContext?, historyCount: Int): String {
        ctx ?: return ""
        val background = ctx.background(relationship)
        val history = ctx.history
        if (background.isBlank() && history.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("以下是关于我和对方的背景与知识库，回复必须与之一致，")
            .append("可以直接引用其中事实，不要编造知识库里没有的事实。\n")
        if (background.isNotBlank()) sb.append(background).append('\n')
        if (history.isNotEmpty()) {
            sb.append("\n更早的聊天记录（越靠下越新）：\n")
            history.takeLast(historyCount.coerceIn(0, 100)).forEach {
                sb.append(if (it.side == "me") "我：" else "对方：").append(it.text).append('\n')
            }
        }
        sb.append('\n')
        return sb.toString()
    }

    fun user(relationship: String, ctx: ChatContext?, convo: String, historyCount: Int): String =
        knowledgeBlock(relationship, ctx, historyCount) +
            "关系：$relationship\n\n最近对话：\n$convo\n\n请给出 3 条候选回复。"

    /**
     * Tolerant parse of "a JSON array of 3 strings".
     *
     * A small model does not always obey "output only JSON", so a line-split
     * fallback exists; both routes return exactly 3 entries because the judge's
     * ranking question hard-requires 3 (see JevQuestions.rankQuestion).
     */
    fun parseThree(rawContent: String): List<String> {
        val content = stripThinking(rawContent)
        val start = content.indexOf('[')
        val end = content.lastIndexOf(']')
        if (start >= 0 && end > start) {
            try {
                val arr = JSONArray(content.substring(start, end + 1))
                val out = ArrayList<String>()
                for (i in 0 until arr.length()) out.add(arr.getString(i).trim())
                if (out.size >= 3) return out.take(3)
                while (out.size < 3) out.add(FILLER)
                return out
            } catch (_: Exception) {
            }
        }
        val lines = content.split("\n").map { it.trim().trimStart('-', '*', '1', '2', '3', '.', ' ', '"') }
            .filter { it.isNotBlank() }
        val out = lines.take(3).toMutableList()
        while (out.size < 3) out.add(FILLER)
        return out
    }

    /**
     * Qwen3.5 emits a `<think>...</think>` reasoning block unless the prompt talks
     * it out of one. Anything inside it is prose that happens to contain braces, so
     * drop it before hunting for the JSON array - otherwise the "first [" .. "last ]"
     * window can start inside the reasoning and never close cleanly.
     */
    internal fun stripThinking(content: String): String {
        val open = content.indexOf("<think")
        if (open < 0) return content
        val closeTag = content.indexOf("</think", open)
        if (closeTag < 0) return content.substring(0, open)
        val after = content.indexOf('>', closeTag)
        return if (after >= 0) content.substring(after + 1) else content.substring(0, open)
    }

    const val FILLER = "（稍等，我看下）"
}
