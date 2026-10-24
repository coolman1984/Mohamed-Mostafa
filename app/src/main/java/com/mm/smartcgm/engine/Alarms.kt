package com.mm.smartcgm.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.mm.smartcgm.R
import com.mm.smartcgm.core.Alert
import com.mm.smartcgm.core.AlertType
import com.mm.smartcgm.ui.AlarmActivity
import com.mm.smartcgm.ui.MainActivity

object Alarms {
    const val CH_CRITICAL = "critical_v1"
    const val CH_NORMAL = "alerts_v1"
    const val CH_SERVICE = "service_v1"
    const val SERVICE_ID = 1

    // Touched from the engine thread (start) and the UI/receivers (stop): guarded by @Synchronized below.
    private var player: MediaPlayer? = null

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val alarmAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        nm.createNotificationChannel(NotificationChannel(CH_CRITICAL, "🚨 إنذارات حرجة", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "هبوط خطير — بيتجاوز الصامت وعدم الإزعاج"
            setBypassDnd(true)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 800, 300, 800, 300, 800)
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), alarmAttrs)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        nm.createNotificationChannel(NotificationChannel(CH_NORMAL, "🔔 تنبيهات السكر", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "📡 المتابعة المستمرة", NotificationManager.IMPORTANCE_LOW))
    }

    private fun openApp(ctx: Context) = PendingIntent.getActivity(ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun snoozeIntent(ctx: Context, type: AlertType, minutes: Int) = PendingIntent.getBroadcast(ctx,
        type.ordinal * 100 + minutes,
        Intent(ctx, SnoozeReceiver::class.java).putExtra("type", type.name).putExtra("minutes", minutes),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Stops the looping alarm sound without snoozing (the engine still repeats the alert on schedule). */
    private fun ackIntent(ctx: Context, type: AlertType) = PendingIntent.getBroadcast(ctx,
        5000 + type.ordinal,
        Intent(ctx, SnoozeReceiver::class.java).putExtra("type", type.name).putExtra("ack", true),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Urgent low opens the dedicated red alarm screen; other critical alerts open the app. */
    private fun fullScreen(ctx: Context, a: Alert): PendingIntent =
        if (a.type == AlertType.URGENT_LOW) PendingIntent.getActivity(ctx, 7000,
            AlarmActivity.intent(ctx, a.title, a.body), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        else openApp(ctx)

    fun show(ctx: Context, a: Alert) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val n = Notification.Builder(ctx, if (a.critical) CH_CRITICAL else CH_NORMAL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(a.title)
            .setContentText(a.body)
            .setStyle(Notification.BigTextStyle().bigText(a.body))
            .setCategory(if (a.critical) Notification.CATEGORY_ALARM else Notification.CATEGORY_STATUS)
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
        if (a.type == AlertType.URGENT_LOW) {
            // Urgent low can't be snoozed (medical rule #3): the only action is acknowledging the sound.
            n.addAction(Notification.Action.Builder(null, "✅ أخدت سكر", ackIntent(ctx, a.type)).build())
                .setOngoing(true).setAutoCancel(false)
        } else {
            n.addAction(Notification.Action.Builder(null, "⏸️ تأجيل 15 د", snoozeIntent(ctx, a.type, 15)).build())
                .addAction(Notification.Action.Builder(null, "⏸️ تأجيل 30 د", snoozeIntent(ctx, a.type, 30)).build())
        }
        if (a.critical) n.setDeleteIntent(ackIntent(ctx, a.type))
        if (a.critical) n.setFullScreenIntent(fullScreen(ctx, a), true)
        runCatching { nm.notify(1000 + a.type.ordinal, n.build()) }
        if (a.critical) playCritical(ctx, a.type)
    }

    /**
     * Alarm stream plays even when the ringer is on silent. It LOOPS until the user acknowledges it
     * (notification action, swipe, snooze, or opening the app), as AGENTS.md P1-2 requires.
     */
    @Synchronized
    private fun playCritical(ctx: Context, type: AlertType) {
        if (activeSoundType == AlertType.URGENT_LOW && type != AlertType.URGENT_LOW) return
        activeSoundType = type
        if (runCatching { player?.isPlaying == true }.getOrDefault(false)) return // already ringing
        runCatching {
            player?.release()
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(ctx, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                isLooping = true
                prepare()
                start()
            }
        }
        val vib = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
        runCatching { vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 300, 800, 300, 800), 0)) }
        vibrator = vib
    }

    private var vibrator: Vibrator? = null
    var activeSoundType: AlertType? = null
        private set

    @Synchronized
    fun acknowledge(type: AlertType) {
        if (activeSoundType != type) return
        activeSoundType = null
        releaseSound()
    }

    @Synchronized
    fun stopSound() {
        if (activeSoundType == AlertType.URGENT_LOW) return
        activeSoundType = null
        releaseSound()
    }

    private fun releaseSound() {
        runCatching { player?.stop(); player?.release() }; player = null
        runCatching { vibrator?.cancel() }; vibrator = null
    }
}
