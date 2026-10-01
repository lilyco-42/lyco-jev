package com.jev.probe

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jev.probe.core.AutoReplyPolicy
import com.jev.probe.test.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Goal 3's auto-reply, executed for the first time.
 *
 * Production chain: AutoReplyPolicy decides -> GuardedInputWriter fills with
 * ACTION_SET_TEXT -> ChatCaptureService.sendNow looks the adapter-declared send
 * node up by viewId and clicks it. The service needs a bound AccessibilityService
 * and a real chat app, so it cannot be instantiated here - but every action in the
 * chain can be driven against a probe screen, which is the difference between
 * "written" and "has ever run".
 *
 * The probe activity lives in THIS (test) APK, so it is started from the test
 * context rather than through ActivityScenario: that goes through the target app's
 * process, and Android refuses to start another process's activity.
 */
@RunWith(AndroidJUnit4::class)
class AutoSendClickInstrumentedTest {

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val testCtx: Context get() = inst.context

    private fun id(res: Int): String = testCtx.resources.getResourceName(res)

    /** Starts the probe screen and waits until its send control is in the tree. */
    private fun launchProbe(): AccessibilityNodeInfo {
        val intent = Intent(Intent.ACTION_MAIN)
            .setClassName(testCtx, SendProbeActivity::class.java.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        testCtx.startActivity(intent)
        val sendId = id(R.id.probe_send)
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val root = inst.uiAutomation.rootInActiveWindow
            if (root != null && !root.findAccessibilityNodeInfosByViewId(sendId).isNullOrEmpty()) return root
            Thread.sleep(200)
        }
        throw AssertionError("probe screen never became the active window")
    }

    private fun closeProbe() {
        SendProbeActivity.current?.let { a -> inst.runOnMainSync { a.finish() } }
    }

    @Test
    fun policyThenFillThenClickActuallyReachesTheSendControl() {
        try {
            // 1) the gate allows it
            val decision = AutoReplyPolicy.decide(
                enabled = true, autoSend = true, conversationAllowed = true,
                dangerLevel = 1.0, maxDanger = 3, sentAts = emptyList(),
                now = System.currentTimeMillis())
            assertTrue("policy refused: " + decision.reason, decision.allowed)

            val root = launchProbe()

            // 2) fill, exactly the action GuardedInputWriter uses
            val reply = "记得，周六三点"
            val input = root.findAccessibilityNodeInfosByViewId(id(R.id.probe_input))?.firstOrNull()
            assertTrue("input node not found", input != null)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, reply)
            }
            assertTrue("ACTION_SET_TEXT was rejected",
                input!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
            inst.waitForIdleSync()

            // 3) click, exactly the action sendNow uses
            val send = inst.uiAutomation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByViewId(id(R.id.probe_send))?.firstOrNull()
            assertTrue("send node not found", send != null)
            // Report the flags, so a rejection says WHY rather than just "false".
            assertTrue("send node is not actionable: clickable=" + send!!.isClickable +
                " enabled=" + send.isEnabled + " visible=" + send.isVisibleToUser +
                " class=" + send.className,
                send.isClickable && send.isEnabled && send.isVisibleToUser)
            assertTrue("ACTION_CLICK was rejected", send.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            inst.waitForIdleSync()

            // 4) the click landed: the screen reports what it "sent"
            val status = inst.uiAutomation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByViewId(id(R.id.probe_status))?.firstOrNull()
            assertEquals("the send control never received the click",
                "sent:" + reply, status?.text?.toString())
        } finally {
            closeProbe()
        }
    }

    /**
     * The safe default that makes auto-send acceptable: an app whose send control
     * we have not verified must yield no node, so nothing is ever clicked. QQ's
     * real id is used as the probe because this package does not own it.
     */
    @Test
    fun anUnverifiedAppYieldsNoSendNodeSoNothingIsClicked() {
        try {
            val root = launchProbe()
            val found = root.findAccessibilityNodeInfosByViewId("com.tencent.mobileqq:id/send_btn")
            assertTrue("an id this app does not own must resolve to nothing, got " + found,
                found.isNullOrEmpty())
        } finally {
            closeProbe()
        }
    }
}
