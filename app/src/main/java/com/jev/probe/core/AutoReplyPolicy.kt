package com.jev.probe.core

/** Why an automatic send was allowed or refused (shown in the overlay note). */
data class AutoReplyDecision(val allowed: Boolean, val reason: String)

/**
 * Goal 3: the gate in front of the one action the upstream project refuses to
 * take — pressing send without a human.
 *
 * The upstream rule is "only fill the box, never send", and that stays the
 * default. When auto-send IS turned on, every call has to pass this gate:
 * master switch, explicit opt-in, the conversation whitelist, a danger ceiling
 * (never auto-send into a fight), and a rate limit so a looping capture cannot
 * machine-gun someone. It is deliberately a pure function with no Android
 * imports so it can be unit tested without a device.
 *
 * Money is not gated here on purpose — the capture layer never exposes transfer
 * or red-packet nodes to this path at all, which is a stronger guarantee than a
 * flag.
 */
object AutoReplyPolicy {

    const val MAX_PER_HOUR = 3
    private const val HOUR_MS = 3_600_000L

    fun decide(
        enabled: Boolean,
        autoSend: Boolean,
        conversationAllowed: Boolean,
        dangerLevel: Double?,
        maxDanger: Int,
        sentAts: List<Long>,
        now: Long
    ): AutoReplyDecision {
        if (!enabled) return AutoReplyDecision(false, "助手总开关关闭")
        if (!autoSend) return AutoReplyDecision(false, "未开启自动发送")
        if (!conversationAllowed) return AutoReplyDecision(false, "会话不在白名单")
        if (dangerLevel != null && dangerLevel >= maxDanger) {
            return AutoReplyDecision(false, "危险等级 " + dangerLevel + " 不低于上限 " + maxDanger)
        }
        val recent = sentAts.count { now - it < HOUR_MS }
        if (recent >= MAX_PER_HOUR) {
            return AutoReplyDecision(false, "一小时内已自动发送 " + recent + " 条")
        }
        return AutoReplyDecision(true, "允许")
    }
}
