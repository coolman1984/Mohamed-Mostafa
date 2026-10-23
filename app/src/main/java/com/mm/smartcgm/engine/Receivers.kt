package com.mm.smartcgm.engine

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mm.smartcgm.core.AlertType

class SnoozeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val type = runCatching { AlertType.valueOf(intent.getStringExtra("type") ?: "") }.getOrNull() ?: return
        Pipeline.snooze(type, intent.getIntExtra("minutes", 15))
        Alarms.stopSound()
        ctx.getSystemService(NotificationManager::class.java).cancel(1000 + type.ordinal)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        runCatching { MonitorService.start(ctx) }
    }
}
