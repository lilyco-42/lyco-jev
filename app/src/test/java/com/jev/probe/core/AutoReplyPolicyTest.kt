package com.jev.probe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoReplyPolicyTest {

    private val now = 1_000_000_000_000L

    private fun decide(
        enabled: Boolean = true,
        autoSend: Boolean = true,
        allowed: Boolean = true,
        danger: Double? = 1.0,
        maxDanger: Int = 3,
        sent: List<Long> = emptyList()
    ) = AutoReplyPolicy.decide(enabled, autoSend, allowed, danger, maxDanger, sent, now)

    @Test fun refusesWhenOptInIsOff() {
        assertFalse(decide(autoSend = false).allowed)
        assertFalse(decide(enabled = false).allowed)
    }

    @Test fun refusesOutsideWhitelist() {
        assertFalse(decide(allowed = false).allowed)
    }

    @Test fun refusesAtOrAboveDangerCeiling() {
        assertFalse(decide(danger = 3.0).allowed)
        assertFalse(decide(danger = 7.4).allowed)
        assertTrue(decide(danger = 2.9).allowed)
    }

    @Test fun unknownDangerStillAllows() {
        assertTrue(decide(danger = null).allowed)
    }

    @Test fun rateLimitsWithinTheHour() {
        val recent = listOf(now - 60_000, now - 120_000, now - 180_000)
        val d = decide(sent = recent)
        assertFalse(d.allowed)
        assertEquals("一小时内已自动发送 3 条", d.reason)
    }

    @Test fun ignoresSendsOlderThanAnHour() {
        val old = listOf(now - 3_600_000L, now - 7_200_000L, now - 10_800_000L)
        assertTrue(decide(sent = old).allowed)
    }
}
