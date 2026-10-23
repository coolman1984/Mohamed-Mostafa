package com.mm.smartcgm.sources

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.mm.smartcgm.core.GlucoseReading
import com.mm.smartcgm.core.NotificationParser
import com.mm.smartcgm.data.AppMode
import com.mm.smartcgm.data.Repo
import com.mm.smartcgm.engine.Pipeline
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/*
 * How readings reach the app (the sensor's Bluetooth encryption is handled by Juggluco,
 * the open-source app that already decodes LinX/AiDEX X, Libre 2/3 and Sibionics GS1):
 *
 *  1. Juggluco "glucodata" broadcast       → GlucoseBroadcastReceiver   (best, every minute)
 *  2. xDrip-style BgEstimate broadcast     → GlucoseBroadcastReceiver
 *  3. Juggluco local web server :17580     → JugglucoWebSource          (backup / backfill)
 *  4. Official app notification (LinX…)    → CgmNotificationListener    (works with the stock app)
 */

private fun ingestAsync(r: GlucoseReading) {
    if (Repo.settings.value.mode != AppMode.PATIENT) return
    Pipeline.scope.launch { Repo.ingest(r) }
}

class GlucoseBroadcastReceiver : BroadcastReceiver() {
    companion object {
        const val JUGGLUCO = "glucodata.Minute"
        const val XDRIP = "com.eveningoutpost.dexdrip.BgEstimate"
        val ACTIONS = listOf(JUGGLUCO, XDRIP)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val now = System.currentTimeMillis()
        val r = when (intent.action) {
            JUGGLUCO -> {
                val mgdl = intent.getIntExtra("glucodata.Minute.mgdl", 0)
                    .takeIf { it > 0 }?.toDouble()
                    ?: intent.getFloatExtra("glucodata.Minute.glucose", 0f).toDouble().takeIf { it > 0 }?.let {
                        if (it < 35) it * 18.0182 else it // value is mmol/L when the user set that unit
                    }
                mgdl?.let {
                    GlucoseReading(intent.getLongExtra("glucodata.Minute.Time", now), it, "juggluco",
                        intent.getStringExtra("glucodata.Minute.SerialNumber"))
                }
            }
            XDRIP -> {
                val v = intent.getDoubleExtra("com.eveningoutpost.dexdrip.Extras.BgEstimate", 0.0)
                v.takeIf { it > 0 }?.let {
                    GlucoseReading(intent.getLongExtra("com.eveningoutpost.dexdrip.Extras.Time", now), it, "xdrip-broadcast",
                        intent.getStringExtra("com.eveningoutpost.dexdrip.Extras.SensorId"))
                }
            }
            else -> null
        }
        r?.let(::ingestAsync)
    }
}

class CgmNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in NotificationParser.CGM_PACKAGES) return
        // Juggluco broadcasts directly; skip its notification to avoid double counting.
        if (sbn.packageName == "tk.glucodata") return
        val ex = sbn.notification.extras
        val text = listOfNotNull(
            ex.getCharSequence(Notification.EXTRA_TITLE),
            ex.getCharSequence(Notification.EXTRA_TEXT),
            ex.getCharSequence(Notification.EXTRA_BIG_TEXT),
        ).joinToString(" ")
        val mgdl = NotificationParser.parse(text) ?: return
        ingestAsync(GlucoseReading(sbn.postTime, mgdl, "app:" + sbn.packageName))
    }
}

/** Nightscout-compatible endpoint that Juggluco serves when "Web server" is on in its settings. */
object JugglucoWebSource {
    const val URL_ENTRIES = "http://127.0.0.1:17580/api/v1/entries.json?count=30"

    fun poll() {
        val c = URL(URL_ENTRIES).openConnection() as HttpURLConnection
        c.connectTimeout = 2000
        c.readTimeout = 3000
        try {
            if (c.responseCode != 200) return
            val arr = JSONArray(c.inputStream.bufferedReader().readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val sgv = o.optDouble("sgv", 0.0)
                val t = o.optLong("date", 0)
                if (sgv > 0 && t > 0) Repo.ingest(GlucoseReading(t, sgv, "juggluco-web"))
            }
        } finally {
            c.disconnect()
        }
    }
}
