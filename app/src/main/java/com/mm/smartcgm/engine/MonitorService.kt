package com.mm.smartcgm.engine

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.mm.smartcgm.R
import com.mm.smartcgm.core.HOUR_MS
import com.mm.smartcgm.core.Trend
import com.mm.smartcgm.data.AppMode
import com.mm.smartcgm.data.Repo
import com.mm.smartcgm.sources.GlucoseBroadcastReceiver
import com.mm.smartcgm.sources.JugglucoWebSource
import com.mm.smartcgm.ui.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the app alive in the background: listens for broadcasts, polls the
 * Juggluco local server, and runs the alert engine every minute.
 */
class MonitorService : Service() {
    private var loop: Job? = null
    private val receiver = GlucoseBroadcastReceiver()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            GlucoseBroadcastReceiver.ACTIONS.forEach { addAction(it) }
        }
        // Dynamic registration also catches implicit broadcasts that the manifest receiver misses.
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(receiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) startForeground(Alarms.SERVICE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(Alarms.SERVICE_ID, n)
        if (loop?.isActive != true) {
            loop = Pipeline.scope.launch {
                while (isActive) {
                    val s = Repo.settings.value
                    if (s.mode == AppMode.PATIENT && s.pollJuggluco) {
                        Repo.lastJugglucoWebStatus = runCatching { JugglucoWebSource.poll(s.jugglucoSecret) }.getOrDefault(0)
                    }
                    Pipeline.tick(this@MonitorService)
                    getSystemService(NotificationManager::class.java).notify(Alarms.SERVICE_ID, buildNotification())
                    delay(60_000)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        loop?.cancel()
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val now = System.currentTimeMillis()
        val recent = Repo.readings(now - HOUR_MS)
        val last = recent.lastOrNull()
        val title = if (last == null) "في انتظار قراءات الحساس…"
        else "%s %s".format(Repo.settings.value.unit.formatWithUnit(last.mgdl), Trend.arrow(recent).symbol)
        val text = Repo.sensorStatus(now)?.message ?: "📡 متابعة مستمرة"
        return Notification.Builder(this, Alarms.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    companion object {
        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, MonitorService::class.java))
        }
    }
}
