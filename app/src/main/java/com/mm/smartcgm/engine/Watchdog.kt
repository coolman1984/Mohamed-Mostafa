package com.mm.smartcgm.engine

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * AGENTS.md P1-1: every 15 minutes (the WorkManager minimum) make sure [MonitorService] is running.
 * If the OS killed it, this restarts it. Android 12+ may refuse a background foreground-service start unless the app
 * is exempt from battery optimisation, which is why Settings/the setup wizard ask for that exemption.
 */
class WatchdogWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        runCatching { MonitorService.start(applicationContext) }
        return Result.success()
    }
}

object Watchdog {
    fun schedule(ctx: Context) {
        runCatching {
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("monitor-watchdog", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build())
        }
    }
}
