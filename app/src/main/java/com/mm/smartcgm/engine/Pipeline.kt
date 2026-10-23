package com.mm.smartcgm.engine

import android.content.Context
import com.mm.smartcgm.cloud.CloudSync
import com.mm.smartcgm.core.AlertEngine
import com.mm.smartcgm.core.AlertState
import com.mm.smartcgm.core.AlertType
import com.mm.smartcgm.core.FollowerRole
import com.mm.smartcgm.core.HOUR_MS
import com.mm.smartcgm.core.Prediction
import com.mm.smartcgm.data.AppMode
import com.mm.smartcgm.data.Repo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The automatic background "AI engine": runs on every new reading and once a minute,
 * with no user action. Calibrates → predicts → decides alerts → syncs to the cloud.
 */
object Pipeline {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var alertState = AlertState()

    fun onNewReading(ctx: Context) {
        evaluate(ctx)
        val s = Repo.settings.value
        if (s.mode == AppMode.PATIENT && s.firebaseUrl.isNotBlank()) {
            scope.launch { runCatching { CloudSync.pushLatest(s) } }
        }
    }

    /** Called every minute by [MonitorService] so "signal lost" and repeats still fire with no new data. */
    fun tick(ctx: Context) {
        val s = Repo.settings.value
        if (s.mode == AppMode.FOLLOWER) {
            scope.launch {
                runCatching { CloudSync.pullAsFollower(s) }
                // Only primary (admin) followers get alarms; viewers just watch.
                if (s.followRole == FollowerRole.ADMIN) evaluate(ctx)
            }
        } else {
            scope.launch { runCatching { CloudSync.pullAdminEvents(s) } }
            evaluate(ctx)
        }
    }

    @Synchronized
    fun evaluate(ctx: Context) {
        val now = System.currentTimeMillis()
        val s = Repo.settings.value
        val recent = Repo.readings(now - HOUR_MS)
        val forecast = Prediction.forecast(recent, s.alerts.low.thresholdMgdl)
        val (alerts, next) = AlertEngine.evaluate(recent.lastOrNull(), forecast, Repo.sensorStatus(now), s.alerts, alertState, now)
        alertState = next
        alerts.forEach { Alarms.show(ctx, it) }
    }

    fun snooze(type: AlertType, minutes: Int) {
        alertState = AlertEngine.snooze(alertState, type, minutes, System.currentTimeMillis())
    }
}
