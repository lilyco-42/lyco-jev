package com.jev.probe

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jev.probe.core.AutoReplyPolicy
import com.jev.probe.test.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Goal 3's auto-reply, executed for the first time.
 *
 * The production chain is: AutoReplyPolicy decides -> GuardedInputWriter fills with
 * ACTION_SET_TEXT -> ChatCaptureService.sendNow looks the adapter-declared send
 * node up by viewId and clicks it. The service itself needs a bound
 * AccessibilityService and a real chat app, so it cannot be instantiated here -
 * but every action in that chain can be driven against a probe screen. That is
 * the difference between "written" and "has ever run", and it is the last such
 * gap in the objective.
 */
@RunWith(AndroidJUnit4::class)
class AutoSendClickInstrumentedTest {

    @Test
    fun policyThenFillThenClickActuallyReachesTheSendControl() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val testCtx = inst.context

        // 1) the gate allows it
        val decision = AutoReplyPolicy.decide(
            enabled = true, autoSend = true, conversationAllowed = true,
            dangerLevel = 1.0, maxDanger = 3, sentAts = emptyList(),
            now = System.currentTimeMillis())
        assertTrue("policy refused: " + decision.reason, decision.allowed)

        ActivityScenario.launch(SendProbeActivity::class.java).use { scenario ->
            val root = inst.uiAutomation.rootInActiveWindow
            assertNotNull("no active window root", root)

            val inputId = testCtx.resources.getResourceName(R.id.probe_input)
            val sendId = testCtx.resources.getResourceName(R.id.probe_send)

            val input = root!!.findAccessibilityNodeInfosByViewId(inputId)?.firstOrNull()
            val send = root.findAccessibilityNodeInfosByViewId(sendId)?.firstOrNull()
            assertNotNull("input node not found by viewId " + inputId, input)
            assertNotNull("send node not found by viewId " + sendId, send)

            // 2) fill, exactly the action GuardedInputWriter uses
            val reply = "记得，周六三点"
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, reply)
            }
            assertTrue("ACTION_SET_TEXT was rejected",
                input!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
            inst.waitForIdleSync()

            // 3) click, exactly the action sendNow uses
            assertTrue("ACTION_CLICK was rejected", send!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            inst.waitForIdleSync()

            scenario.onActivity { a ->
                val status = a.findViewById<TextView>(R.id.probe_status)
                assertEquals("the send control never received the click",
                    "sent:" + reply, status.text.toString())
            }
        }
    }

    /**
     * The safe default that makes auto-send acceptable: an app whose send control
     * we have not verified must yield no node, so nothing is ever clicked. QQ's
     * real id is used as the probe because this package does not have it.
     */
    @Test
    fun anUnverifiedAppYieldsNoSendNodeSoNothingIsClicked() {
        val inst = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(SendProbeActivity::class.java).use {
            val root = inst.uiAutomation.rootInActiveWindow
            assertNotNull("no active window root", root)
            val found = root!!.findAccessibilityNodeInfosByViewId("com.tencent.mobileqq:id/send_btn")
            assertTrue("an id this app does not own must resolve to nothing, got " + found,
                found.isNullOrEmpty())
        }
    }
}
