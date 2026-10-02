package com.jev.probe.jev

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.util.Log

/**
 * A system notification for the one-off weight unpack.
 *
 * The overlay panel only exists while the accessibility service is up and the
 * user is looking at it. The unpack is ~1 GB across two weights and happens on
 * the first judgment, which is exactly when the user tends to swipe away or lock
 * the phone - so "上模型没通知，根本不知道下好没" was really "the only progress
 * display lived in a window that disappears".
 *
 * Deliberately a normal (non-foreground) notification: it is transient progress
 * for something the user triggered, not something that should pin the process.
 * It is updated in place via a fixed id and cancelled as soon as the weights are
 * ready, so it never accumulates in the shade.
 */
class ModelPrepNotifier(private val ctx: Context) {

    private var shown = false
    private var lastPercent = -1
    private var startedAt = 0L

    /** Starts (or refreshes) the progress notification. Safe to call repeatedly. */
    fun progress(extracting: Boolean, done: Long, total: Long) {
        // POST_NOTIFICATIONS can be denied on 33+; a missing notification must not
        // take the analysis down with it.
        if (!canPost()) return
        if (!shown) {
            shown = true
            startedAt = System.currentTimeMillis()
            lastPercent = -1
            ensureChannel()
        }
        // One update per whole percent: the unpack reports every 8 MiB, which on a
        // 505 MB weight is ~63 callbacks, but a shade that flickers 63 times for
        // one copy is worse than useless.
        val pct = if (total > 0) ((done * 100 / total).coerceIn(0, 100)).toInt() else -1
        if (total != -2L && pct == lastPercent) return
        lastPercent = pct

        val verb = if (extracting) "解压" else "下载"
        val text = when {
            total == -2L -> "正在载入模型…"
            pct >= 0 -> "正在$verb 端侧模型 $pct%（共 ${total / (1024 * 1024)} MB，仅首次）"
            else -> "正在$verb 端侧模型…（仅首次）"
        }
        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("准备端侧模型")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (total == -2L) {
            // The copy finished; a bar stuck at 100% while nativeLoad runs would
            // read as a hang. Drop back to indeterminate for the mapping phase.
            builder.setProgress(0, 0, true)
        } else if (pct >= 0) {
            builder.setProgress(100, pct, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        post(builder.build())
    }

    /**
     * Replaces the progress notification with a short completion notice that
     * carries the wall-clock cost, because "就是很久" was never quantified and
     * this is the only place that can tell us where the time actually went.
     *
     * No-op unless [progress] actually ran this round. Almost every judgment
     * happens long after the weights are ready, and posting "模型已就绪" then would
     * be both false and the kind of noise that gets a channel switched off.
     */
    fun done(extracting: Boolean) {
        if (!shown) return
        if (!canPost()) { shown = false; return }
        val elapsed = if (startedAt > 0) System.currentTimeMillis() - startedAt else 0L
        val secs = elapsed / 1000.0
        val verb = if (extracting) "解压" else "下载"
        val text = if (elapsed > 0) "端侧模型${verb}完成，用时 ${"%.1f".format(secs)} 秒，之后完全离线"
        else "端侧模型已就绪，之后完全离线"
        Log.i(TAG, "model prep finished: $verb ${"%.1f".format(secs)}s")
        shown = false
        startedAt = 0L
        lastPercent = -1
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("端侧模型已就绪")
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        post(n)
    }

    /** Called when the round fails, so a progress notification never sticks around. */
    fun cancel() {
        if (!shown) return
        shown = false
        startedAt = 0L
        lastPercent = -1
        NotificationManagerCompat.from(ctx).cancel(NOTIF_ID)
    }

    private fun post(n: android.app.Notification) {
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n)
        } catch (e: SecurityException) {
            Log.w(TAG, "notify denied: " + e.message)
        }
    }

    private fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        // LOW, not MIN: this one should actually be visible while it runs (the
        // keep-alive channel is MIN so it never makes a sound or pops).
        val ch = NotificationChannel(CHANNEL_ID, "端侧模型准备", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        // Explicit types: setSound(null, null) is ambiguous in Kotlin (the one-arg
        // and two-arg overloads both match), which is a compile error, not a warning.
        ch.setSound(null as android.net.Uri?, null as android.media.AudioAttributes?)
        nm.createNotificationChannel(ch)
    }

    companion object {
        private const val TAG = "JEVLOCAL"

        /**
         * Distinct from KeepAliveService's fixed id 1, which owns the foreground
         * notification - reusing it would replace the service's notification and
         * the service would lose its foreground status.
         */
        private const val NOTIF_ID = 0x4A45_5601
        private const val CHANNEL_ID = "jev_model_prep"
    }
}
