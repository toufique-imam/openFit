package com.openfit.health

import android.content.Context
import androidx.health.connect.client.permission.HealthPermission
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Keeps openFit's copy of Health Connect current while the app is closed, so it opens on today's data: finishes the
 * history import if it stopped, else follows the changes feed and re-reads the last days. Needs Health Connect's
 * background read permission; without it the work does nothing and the app syncs when opened.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val gateway = HealthConnectGateway.get(applicationContext)
        if (gateway.availability() != HealthConnectAvailability.Available) return Result.success()
        if (HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND !in gateway.grantedPermissions()) return Result.success()
        val sync = HealthSync.get(applicationContext)
        // Background reads: Health Connect's background quota is half the foreground one, and these leave a reserve.
        withContext(HcPriority.Background) {
            // No waiting for the quota here: Android stops background work after ten minutes.
            if (!sync.imported) sync.importAll(waitForQuota = false)
            sync.syncRecent()
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "health_sync"
        private const val EVERY_HOURS = 1L

        /** Schedules the hourly sync; keeps the one already scheduled. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(EVERY_HOURS, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
