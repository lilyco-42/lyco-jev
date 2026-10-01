package com.jev.probe;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.jev.probe.test.R;

/**
 * A throwaway chat-like screen: one send control and one editable input, with real
 * resource ids, so the auto-send test can drive it through the same accessibility
 * actions the production path uses.
 *
 * Deliberately JAVA. An activity declared in the test APK's manifest runs in the
 * test package's own process, whose classpath is the test APK alone - the
 * instrumentation classes get away with Kotlin because they load through the app
 * APK's classloader, a component of the test APK does not. A Kotlin version died
 * with ClassNotFoundException: kotlin.jvm.internal.Intrinsics.
 */
public class SendProbeActivity extends Activity {

    /** So the test can finish the screen it started from the test process. */
    public static volatile SendProbeActivity current;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        current = this;

        // The send control goes ABOVE the input: focusing the EditText opens the
        // soft keyboard, and a covered node stops being clickable. ADJUST_NOTHING
        // keeps the keyboard from resizing the window for the same reason.
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        EditText input = new EditText(this);
        input.setId(R.id.probe_input);

        Button send = new Button(this);
        send.setId(R.id.probe_send);
        send.setText("send");

        TextView status = new TextView(this);
        status.setId(R.id.probe_status);
        status.setText("idle");

        send.setOnClickListener(v -> status.setText("sent:" + input.getText().toString()));

        root.addView(send);
        root.addView(input);
        root.addView(status);
        setContentView(root);
    }

    @Override
    protected void onDestroy() {
        if (current == this) {
            current = null;
        }
        super.onDestroy();
    }
}
