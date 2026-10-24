package com.mm.smartcgm.sources

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.mm.smartcgm.core.GlucoseReading
import com.mm.smartcgm.core.FrozenNotificationGuard
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
    Pipeline.scope.launch { runCatching { Repo.ingest(r) } }
}

/*
 * Contract verified against the Juggluco source (github.com/j-kaltes/Juggluco, Common/src/main/java/tk/glucodata):
 *  - JugglucoSend.java: action "glucodata.Minute", extras SerialNumber (String), mgdl (int), glucose (float, in the
 *    user's display unit), Rate (float), Alarm (int), Time (long, epoch ms). Sent with setPackage() to every app the
 *    user ticked in Juggluco, so the manifest receiver gets it on Android 8+ as long as our app is selected there.
 *  - SendLikexDrip.java: action "com.eveningoutpost.dexdrip.BgEstimate", Extras.BgEstimate (double, mg/dL),
 *    Extras.Time (long ms). No SensorId extra is sent (we get null, which is fine).
 */
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
                // The verified contract supplies an explicit mg/dL integer. Missing units are not guessed.
                val mgdl = intent.getIntExtra("glucodata.Minute.mgdl", 0).takeIf { it > 0 }?.toDouble()
                if (!intent.hasExtra("glucodata.Minute.Time")) return
                mgdl?.let {
                    GlucoseReading(intent.getLongExtra("glucodata.Minute.Time", now), it, "juggluco",
                        intent.getStringExtra("glucodata.Minute.SerialNumber"))
                }
            }
            XDRIP -> {
                if (!intent.hasExtra("com.eveningoutpost.dexdrip.Extras.Time")) return
                val v = intent.getDoubleExtra("com.eveningoutpost.dexdrip.Extras.BgEstimate", 0.0)
                v.takeIf { it > 0 }?.let {
                    GlucoseReading(intent.getLongExtra("com.eveningoutpost.dexdrip.Extras.Time", now), it, "xdrip-broadcast",
                        intent.getStringExtra("com.eveningoutpost.dexdrip.Extras.SensorId"))
                }
            }
            else -> null
        }
        r?.let { reading ->
            if (Repo.settings.value.mode != AppMode.PATIENT) return
            val pending = goAsync()
            Pipeline.scope.launch {
                try { runCatching { Repo.ingest(reading) } } finally { pending?.finish() }
            }
        }
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
        if (!guard.accept(sbn.packageName, text, sbn.postTime)) return
        val mgdl = NotificationParser.parse(text) ?: return
        ingestAsync(GlucoseReading(sbn.postTime, mgdl, "app:" + sbn.packageName))
    }

    companion object { private val guard = FrozenNotificationGuard() }
}

/** Nightscout-compatible endpoint that Juggluco serves when "Web server" is on in its settings. */
object JugglucoWebSource {
    /*
     * Verified in Juggluco net/watchserver: default port 17580 (settings.hpp defaulthttpport), entries carry
     * "date" (epoch ms) and "sgv" (int mg/dL). If an API secret is set inside Juggluco, requests must send the
     * header "api-secret: <sha1 hex of the secret>", otherwise Juggluco answers 403.
     */
    const val URL_ENTRIES = "http://127.0.0.1:17580/api/v1/entries.json?count=30"

    fun sha1Hex(s: String): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** @return HTTP status (0 = not reachable) so the health card can explain what is wrong. */
    private var lastSuccessfulPollMs = 0L
    fun poll(apiSecret: String = ""): Int {
        if (Repo.settings.value.mode != AppMode.PATIENT) return 0
        val now = System.currentTimeMillis()
        val recovery = lastSuccessfulPollMs == 0L || now < lastSuccessfulPollMs || now - lastSuccessfulPollMs > 25 * 60_000L
        val endpoint = if (recovery) URL_ENTRIES.replace("count=30", "count=1440") else URL_ENTRIES
        val c = URL(endpoint).openConnection() as HttpURLConnection
        c.connectTimeout = 2000
        c.readTimeout = 3000
        if (apiSecret.isNotBlank()) c.setRequestProperty("api-secret", sha1Hex(apiSecret.trim()))
        try {
            val code = c.responseCode
            if (code != 200) return code
            val arr = JSONArray(c.inputStream.use { com.mm.smartcgm.net.BoundedBody.read(it) })
            val samples = mutableListOf<GlucoseReading>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val sgv = o.optDouble("sgv", Double.NaN)
                val t = o.optLong("date", 0)
                if (sgv.isFinite() && sgv in 20.0..600.0 && t > 0) samples += GlucoseReading(t, sgv, "juggluco-web")
            }
            samples.sortedBy { it.timeMs }.forEach { Repo.ingest(it) }
            lastSuccessfulPollMs = now
            return code
        } catch (e: java.io.IOException) {
            return 0
        } finally {
            c.disconnect()
        }
    }
}
