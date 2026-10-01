package com.jev.probe

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app has been built many times but never launched by a test. This is the
 * cheapest "does it actually start" check, and it covers the screens this fork
 * edited: SettingsActivity gained a seventh provider pill plus a new branch in
 * the pill callback, which would be an index or `when` crash if the mapping were
 * wrong, and nothing else in the suite would notice.
 */
@RunWith(AndroidJUnit4::class)
class AppSmokeInstrumentedTest {

    /** Every TextView string in the hierarchy, for asserting on rendered UI. */
    private fun texts(root: View): List<String> {
        val out = ArrayList<String>()
        fun walk(v: View) {
            if (v is TextView) v.text?.toString()?.let { out.add(it) }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    @Test
    fun mainActivityReachesResumedWithoutCrashing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a -> assertFalse("MainActivity is already finishing", a.isFinishing) }
        }
    }

    @Test
    fun settingsActivityRendersTheLocalOnDeviceProviderPill() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            var found = false
            var seen: List<String> = emptyList()
            scenario.onActivity { a ->
                assertFalse("SettingsActivity is already finishing", a.isFinishing)
                seen = texts(a.window.decorView)
                found = seen.any { it.contains("本地端侧（离线）") }
            }
            assertTrue(
                "settings did not render the local on-device provider pill; screen showed: " + seen,
                found)
        }
    }
}
