package com.osint.unified.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
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
import com.osint.unified.OsintApplication
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

    private fun notify(changes: List<String>) {
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

        val title = if (changes.size == 1) "A watched target changed"
        else "${changes.size} watched targets changed"

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            // A target identifier does not belong on a locked screen.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(applicationContext, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_compass)
                    .setContentTitle(
                        if (changes.size == 1) "A watched target changed"
                        else "${changes.size} watched targets changed"
                    )
                    .build()
            )
            .setContentTitle(title)
            .setContentText(changes.first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(changes.joinToString("\n")))
            .setAutoCancel(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL = "watched-targets"
        private const val NOTIFICATION_ID = 4201
        private const val WORK_NAME = "watch-check"

        /**
         * Six hours, whenever there is a network.
         *
         * These sources change over days, not minutes, and a phone that
         * spends its battery proving nothing happened is a phone someone
         * uninstalls. KEEP, so re-opening the app does not reset the clock
         * and thereby stop it ever firing.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WatchWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }
}
