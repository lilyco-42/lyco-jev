package com.jev.probe

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.jev.probe.test.R

/**
 * A throwaway chat-like screen: one editable input and one send control, with
 * real resource ids. The auto-send test drives it through the same accessibility
 * actions the production path uses, so the click is observable as "sent:<text>".
 */
class SendProbeActivity : Activity() {

    companion object {
        /** So the test can finish the screen it started from the test process. */
        @Volatile var current: SendProbeActivity? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        current = this
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val input = EditText(this).apply { id = R.id.probe_input }
        val send = Button(this).apply { id = R.id.probe_send; text = "send" }
        val status = TextView(this).apply { id = R.id.probe_status; text = "idle" }
        send.setOnClickListener { status.text = "sent:" + input.text.toString() }
        root.addView(input)
        root.addView(send)
        root.addView(status)
        setContentView(root)
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }
}
