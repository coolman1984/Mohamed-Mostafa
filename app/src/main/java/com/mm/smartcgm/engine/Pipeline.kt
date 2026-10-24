package com.mm.smartcgm.engine

import android.content.Context
import com.mm.smartcgm.cloud.CloudSync
import com.mm.smartcgm.core.ClinicalGate
import com.mm.smartcgm.core.AlertEngine
import com.mm.smartcgm.core.AlertState
import com.mm.smartcgm.core.AlertStateCodec
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

    private const val ALERT_STATE_KEY = "alertState"

    private var alertState: AlertState
        get() = AlertStateCodec.decode(Repo.store.getString("$ALERT_STATE_KEY:${Repo.profile}"))
        set(v) { Repo.store.putString("$ALERT_STATE_KEY:${Repo.profile}", AlertStateCodec.encode(v)) }

    fun onNewReading(ctx: Context) {
        evaluate(ctx)
        val s = Repo.settings.value
        if (s.mode == AppMode.PATIENT && CloudSync.enabled(s)) {
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
            scope.launch { runCatching { CloudSync.pullAdminEvents(s) }; runCatching { CloudSync.pullThresholdProposal(s) }; runCatching { CloudSync.pushLatest(s) } }
            evaluate(ctx)
        }
    }

    fun evaluate(ctx: Context) = synchronized(Repo) {
        val now = System.currentTimeMillis()
        val s = Repo.settings.value
        if (s.mode == AppMode.FOLLOWER && s.followRole != FollowerRole.ADMIN) return@synchronized
        val recent = ClinicalGate.forDosing(Repo.readings(now - HOUR_MS, now))
        val forecast = Prediction.forecast(recent, s.alerts.low.thresholdMgdl)
        val (alerts, next) = AlertEngine.evaluate(ClinicalGate.forAlarm(recent, now, s.alerts.staleMinutes * 60_000L), forecast, Repo.sensorStatus(now),
            s.alerts.copy(unit = s.unit), alertState, now)
        alertState = next
        // Before the very first reading ever (fresh install, setup not finished) "signal lost" every 30 min is
        // just noise that teaches people to ignore alarms. Once any reading exists it works normally.
        val neverHadData = Repo.db.lastReadingTime() == null
        alerts.filterNot { neverHadData && it.type == AlertType.SIGNAL_LOST }.forEach { Alarms.show(ctx, it) }
    }

    fun snooze(type: AlertType, minutes: Int) = synchronized(Repo) {
        alertState = AlertEngine.snooze(alertState, type, minutes, System.currentTimeMillis())
    }
}
