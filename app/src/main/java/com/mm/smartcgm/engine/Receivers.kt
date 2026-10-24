package com.mm.smartcgm.engine

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mm.smartcgm.core.AlertType

class SnoozeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val type = runCatching { AlertType.valueOf(intent.getStringExtra("type") ?: "") }.getOrNull() ?: return
        val ack = intent.getBooleanExtra("ack", false)
        if (type == AlertType.URGENT_LOW && !ack) return
        Alarms.acknowledge(type)
        if (ack) {
            // Acknowledge only: silence the sound, keep the schedule (urgent low re-alarms in 5 min if still low).
            ctx.getSystemService(NotificationManager::class.java).cancel(1000 + type.ordinal)
            return
        }
        if (type == AlertType.URGENT_LOW) return // never snoozable
        Pipeline.snooze(type, intent.getIntExtra("minutes", 15).coerceIn(1, 240))
        ctx.getSystemService(NotificationManager::class.java).cancel(1000 + type.ordinal)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        runCatching { MonitorService.start(ctx) }
        Watchdog.schedule(ctx)
    }
}
