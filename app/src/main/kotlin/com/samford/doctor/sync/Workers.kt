package com.samford.doctor.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.samford.doctor.MainActivity
import com.samford.doctor.R
import com.samford.doctor.SamfordDoctorApplication
import java.util.concurrent.TimeUnit

/** Uploads queued bundles. Expedited, network-constrained, exponential backoff. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as SamfordDoctorApplication).container
        val result = container.syncEngine.runOnce()
        runCatching { container.syncEngine.pollStatus() }
        SyncNotifier.update(applicationContext, result.needsDoctor)
        return if (result.retryLater) Result.retry() else Result.success()
    }
}

/** Periodic safety net: retries due bundles and refreshes pharmacy/lab/patient delivery states. */
class StatusWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as SamfordDoctorApplication).container
        val result = container.syncEngine.runOnce()
        runCatching { container.syncEngine.pollStatus() }
        SyncNotifier.update(applicationContext, result.needsDoctor)
        return Result.success()
    }
}

object SyncScheduler {
    private const val OUTBOX = "samford-outbox"
    private const val STATUS = "samford-status"

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Called after every completion and on "Retry now". Replaces a waiting run so it starts now. */
    fun kick(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(OUTBOX, ExistingWorkPolicy.REPLACE, request)
    }

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<StatusWorker>(15, TimeUnit.MINUTES)
            .setConstraints(network)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(STATUS, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

/** Notifications never include patient names or clinical content (lock-screen safe). */
object SyncNotifier {
    private const val CHANNEL = "sync"
    private const val ID = 4101

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.notif_channel_sync), NotificationManager.IMPORTANCE_DEFAULT)
        channel.lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun update(context: Context, needsDoctor: Int) {
        val manager = NotificationManagerCompat.from(context)
        if (needsDoctor <= 0) {
            manager.cancel(ID)
            return
        }
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val intent = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_SYNC)
        val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = context.resources.getQuantityString(R.plurals.notif_needs_attention, needsDoctor, needsDoctor)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(context.getString(R.string.notif_title))
            .setContentText(text)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_upload)
                    .setContentTitle(context.getString(R.string.notif_title))
                    .build(),
            )
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        manager.notify(ID, notification)
    }
}
