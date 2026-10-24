package com.osint.unified.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.osint.unified.MainActivity
import com.osint.unified.OsintApplication
import com.osint.unified.data.WatchRepository
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Re-checks every watched target in the background and says what changed.
 *
 * The work is useful whether or not notifications are permitted: the change
 * is written to the watch row either way and shown in the app. A refused
 * notification permission makes this quieter, not broken — which is why
 * nothing here depends on having it.
 */
class WatchWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? OsintApplication ?: return Result.success()
        val attempt = runCatching { app.watchRepository.checkAll() }
        // Being stopped is not a failure to retry; it is the system asking
        // us to stop.
        attempt.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        val changes = attempt.getOrNull() ?: return Result.retry()
        if (changes.isNotEmpty()) notify(changes)
        return Result.success()
    }

    /**
     * One notification per changed target, under a group summary.
     *
     * A single notification with a fixed id meant the second target to
     * change replaced the first, so a night of checks left one line about
     * one target and no trace of the others. Each target now posts under
     * its own id — derived from the row id, so a later change to the same
     * target updates its notification rather than stacking another copy —
     * and tapping one opens the watch list instead of doing nothing.
     */
    private fun notify(changes: List<WatchRepository.Change>) {
        val manager = applicationContext
            .getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Watched targets", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Changes found on targets you are watching" }
        )

        // POST_NOTIFICATIONS exists only from Android 13. Below that the
        // package manager answers DENIED for a permission that was never
        // required, so checking it unconditionally silenced the notification
        // on every device from Android 8 to 12L — the majority of the range
        // this app supports.
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ActivityCompat.checkSelfPermission(
                applicationContext, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return

        val notifier = NotificationManagerCompat.from(applicationContext)

        changes.forEach { change ->
            val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                // A target identifier does not belong on a locked screen.
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    NotificationCompat.Builder(applicationContext, CHANNEL)
                        .setSmallIcon(android.R.drawable.ic_menu_compass)
                        .setContentTitle("A watched target changed")
                        .build()
                )
                .setContentTitle(change.target)
                .setContentText(change.summary.substringAfter(" — ", change.summary))
                .setStyle(NotificationCompat.BigTextStyle().bigText(change.summary))
                .setGroup(GROUP)
                .setContentIntent(openWatch(change.watchId))
                .setAutoCancel(true)
                .build()
            runCatching { notifier.notify(idFor(change.watchId), notification) }
        }

        // Android collapses several notifications in a group behind this
        // one; without it they are shown loose, and on older versions the
        // group is ignored entirely and this is simply the total.
        val summary = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentTitle(
                if (changes.size == 1) "A watched target changed"
                else "${changes.size} watched targets changed"
            )
            .setStyle(
                NotificationCompat.InboxStyle()
                    .also { style -> changes.take(6).forEach { style.addLine(it.target) } }
                    .setSummaryText("Watched targets")
            )
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setContentIntent(openWatch(null))
            .setAutoCancel(true)
            .build()
        runCatching { notifier.notify(SUMMARY_ID, summary) }
    }

    /** Opens the app on the watch list, at this target if there is one. */
    private fun openWatch(watchId: Long?): PendingIntent {
        val intent = Intent(applicationContext, MainActivity::class.java)
            .setAction(ACTION_OPEN_WATCH)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_WATCH_ID, watchId ?: -1L)
        return PendingIntent.getActivity(
            applicationContext,
            if (watchId == null) SUMMARY_ID else idFor(watchId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Clear of the summary's own id, and of every other watch's at
     * any row count this app will ever see.
     *
     * It was modulo 100,000, and the comment above it claimed the id
     * was stable per target — rows 1 and 100,001 shared one, so a
     * change on the second replaced the first's notification. Not
     * reachable in practice; the claim was wrong, which is the part
     * worth fixing.
     */
    private fun idFor(watchId: Long): Int =
        NOTIFICATION_BASE + (watchId % 1_000_000L).toInt()

    companion object {
        private const val CHANNEL = "watched-targets"
        private const val GROUP = "com.osint.unified.WATCHES"
        private const val NOTIFICATION_BASE = 4300
        private const val SUMMARY_ID = 4201
        private const val WORK_NAME = "watch-check"

        /** Set on the intent a watch notification launches. */
        const val ACTION_OPEN_WATCH = "com.osint.unified.OPEN_WATCH"
        const val EXTRA_WATCH_ID = "watchId"

        /**
         * Six hours, whenever there is a network.
         *
         * These sources change over days, not minutes, and a phone that
         * spends its battery proving nothing happened is a phone someone
         * uninstalls. KEEP, so re-opening the app does not reset the clock
         * and thereby stop it ever firing.
         */
        fun schedule(context: Context) = enqueue(context, ExistingPeriodicWorkPolicy.KEEP)

        /**
         * Same work, re-enqueued because the Wi-Fi-only choice
         * changed. UPDATE rather than KEEP, since KEEP would leave
         * the old constraint in place and the switch would appear to
         * do nothing.
         */
        fun reschedule(context: Context) =
            enqueue(context, ExistingPeriodicWorkPolicy.UPDATE)

        private fun enqueue(context: Context, policy: ExistingPeriodicWorkPolicy) {
            // Ten passive tools per watched target, four times a day.
            // CONNECTED was the only constraint and there was no
            // setting anywhere that could say otherwise.
            val wifiOnly = runCatching {
                com.osint.unified.data.AppSettings(context).watchOnWifiOnly
            }.getOrDefault(false)
            val request = PeriodicWorkRequestBuilder<WatchWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                        )
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, policy, request
            )
        }
    }
}
