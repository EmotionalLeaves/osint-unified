package com.osint.unified.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
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
        val changes = runCatching { app.watchRepository.checkAll() }.getOrNull()
            ?: return Result.retry()
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

        // On Android 13 and later this is refused until the person grants it.
        // Posting anyway throws; checking keeps the work succeeding quietly.
        val granted = ActivityCompat.checkSelfPermission(
            applicationContext, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return

        val title = if (changes.size == 1) "A watched target changed"
        else "${changes.size} watched targets changed"

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
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
