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

    /**
     * Exactly 3 varied candidate replies in Chinese.
     *
     * The "only use what is in the conversation" rule AND the worked example are both
     * load-bearing. Measured on Qwen3.5-0.8B Q4_K_M, same conversation:
     *
     *   - without the rule  -> invented a movie title outright ("那部是《流浪地球》吧")
     *   - with the rule only -> still invented one ("记得是《肖申嘉》，挺帅的电影")
     *   - with the example   -> stopped ("没印象了，下次我帮你查一下")
     *
     * This matters beyond taste: JevQuestions' best_reply question explicitly
     * penalises "faking memory or inventing a plan", so an invented candidate is not
     * just unhelpful, it is the thing the ranker is built to reject.
     */
    const val SYSTEM =
        "你是中文即时通讯回复助手。只输出一个 JSON 数组，含且仅含 3 条候选回复文本，" +
            "三条策略要有区别（一条稳妥承接、一条给具体行动或承诺、一条简短低姿态）。每条不超过 30 字，口语、自然。\n" +
            "最重要的规则：只能使用对话里已经出现的信息。不记得或不确定的事，就说不记得了、" +
            "或者反问对方、或者答应去确认——绝对不要编造电影名、时间、地点、人名这类具体事实。\n" +
            "示例：对话里对方问「上次那个方案你放哪了」，而对话里从没说过放哪了。\n" +
            "正确输出：[\"我找一下，稍后发你\",\"应该在共享盘里，我确认下路径\",\"我记不太清了，你提醒我一下？\"]\n" +
            "错误输出（绝不要这样）：[\"在D盘的项目文件夹里\",\"我放共享盘了\",\"早就发你了\"]\n" +
            "格式必须是合法 JSON 数组，三条之间用逗号分隔。不要解释，直接输出。"

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
        val extracted = extract(content)
        // A candidate about the example rather than about the conversation is worse
        // than no candidate, so drop those - but never drop all three.
        val usable = extracted.filterNot { looksLikeExampleLeak(it) }
        return pad(if (usable.isEmpty()) extracted else usable)
    }

    /** Every plausible reading of the model's answer, in descending order of trust. */
    private fun extract(content: String): List<String> {
        // 1. Strict JSON: the happy path.
        val start = content.indexOf('[')
        val end = content.lastIndexOf(']')
        if (start >= 0 && end > start) {
            try {
                val arr = JSONArray(content.substring(start, end + 1))
                val out = ArrayList<String>()
                for (i in 0 until arr.length()) out.add(arr.getString(i).trim())
                if (out.isNotEmpty()) return out
            } catch (_: Exception) {
            }
        }

        // 2. The model usually gets the content right and the punctuation wrong -
        //    measured output put the three strings on separate lines with a missing
        //    comma. Pull every quoted string out instead of rejecting the whole
        //    answer for one absent character.
        val quoted = QUOTED.findAll(content)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .toList()
        if (quoted.isNotEmpty()) return quoted

        // 3. Last resort: treat non-blank lines as candidates.
        return content.split("\n")
            .map { it.trim().trimStart('-', '*', '1', '2', '3', '.', ' ', '"') }
            .filter { it.isNotBlank() }
    }

    /**
     * The worked example in [SYSTEM] is what stops the model inventing facts, but a
     * 0.8B model sometimes lifts the example's own nouns into its answer - measured
     * on a device: "在共享盘里确认一下，下次提醒我" for a conversation about a film.
     * Making the example vaguer did not help (the output collapsed into three
     * near-identical questions), so the example stays concrete and these get dropped.
     */
    private fun looksLikeExampleLeak(candidate: String): Boolean =
        EXAMPLE_NOUNS.any { candidate.contains(it) }

    private val EXAMPLE_NOUNS = listOf("共享盘", "D盘", "项目文件夹", "那个方案")

    private fun pad(items: List<String>): List<String> {
        val out = items.take(3).toMutableList()
        while (out.size < 3) out.add(FILLER)
        return out
    }

    /** A quoted run without newlines - long enough for a reply, short enough not to swallow prose. */
    private val QUOTED = Regex("\"([^\"\\n]{1,80})\"")

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
