package com.mm.smartcgm.cloud

import com.mm.smartcgm.core.CarbEntry
import com.mm.smartcgm.core.GlucoseReading
import com.mm.smartcgm.core.InsulinDose
import com.mm.smartcgm.core.InsulinKind
import com.mm.smartcgm.data.AppSettings
import com.mm.smartcgm.data.Repo
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Family sharing over Firebase Realtime Database (plain REST, no SDK needed).
 *
 *   /patients/{accountCode}/readings/{epochMs} = mgdl
 *   /patients/{accountCode}/status            = latest value, IOB, COB, sensor start
 *   /patients/{accountCode}/events/{push id}  = doses / carbs entered by a primary follower
 *
 * The 16-character random account code is the shared secret. Lock the database with
 * the rules in docs/firebase-rules.json before real use.
 */
object CloudSync {

    private fun base(s: AppSettings) = "${s.firebaseUrl.trimEnd('/')}/patients/${s.activeAccountCode}"

    private const val PUSH_CURSOR = "cloudLastPushedMs"
    private const val PULL_CURSOR = "cloudLastPulledKey"
    private const val PULL_ACCOUNT = "cloudPulledAccount"
    /** Never try to backfill more than this after a long outage. */
    private const val MAX_BACKFILL_MS = 24 * 60 * 60_000L
    private const val BATCH = 300

    private fun http(method: String, url: String, body: String? = null): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = if (method == "PATCH") "POST" else method
        if (method == "PATCH") c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
        c.connectTimeout = 8000
        c.readTimeout = 8000
        try {
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            return if (c.responseCode in 200..299) c.inputStream.bufferedReader().readText() else null
        } finally {
            c.disconnect()
        }
    }

    /**
     * Offline queue (AGENTS.md P1-6): uploads everything newer than the last confirmed upload (persisted cursor), in
     * batches, so a phone that was offline for hours fills the followers' curve once it is back. The cursor only moves
     * after Firebase confirms the write.
     */
    @Synchronized
    fun pushLatest(s: AppSettings) {
        if (s.firebaseUrl.isBlank()) return
        val now = System.currentTimeMillis()
        val since = maxOf(Repo.store.getLong(PUSH_CURSOR, 0) + 1, now - MAX_BACKFILL_MS)
        val pending = Repo.readings(since)
        if (pending.isEmpty()) return
        for (chunk in pending.chunked(BATCH)) {
            val batch = JSONObject()
            chunk.forEach { batch.put(it.timeMs.toString(), Math.round(it.mgdl)) }
            http("PATCH", "${base(s)}/readings.json", batch.toString()) ?: return // offline: retry next minute
            Repo.store.putLong(PUSH_CURSOR, chunk.last().timeMs)
        }
        val last = pending.last()
        http("PUT", "${base(s)}/status.json", JSONObject()
            .put("time", last.timeMs).put("mgdl", Math.round(last.mgdl))
            .put("iob", Repo.iob(now)).put("cob", Repo.cob(now))
            .put("sensorStart", s.sensorStartMs).put("updated", now).toString())
    }

    /** Follower phone: copy the patient's last 24 h into the local DB. */
    fun pullAsFollower(s: AppSettings) {
        if (s.firebaseUrl.isBlank()) return
        // Incremental: after the first full day, only ask for keys newer than the newest one we already have.
        // Keys are 13-digit epoch-ms strings, so Firebase's lexicographic key order equals time order.
        if (Repo.store.getString(PULL_ACCOUNT) != s.activeAccountCode) {
            Repo.store.putString(PULL_ACCOUNT, s.activeAccountCode)
            Repo.store.putString(PULL_CURSOR, "")
        }
        val cursor = Repo.store.getString(PULL_CURSOR).orEmpty()
        val order = "orderBy=" + URLEncoder.encode("\"\$key\"", "UTF-8")
        val q = if (cursor.isEmpty()) "$order&limitToLast=1440"
        else "$order&startAt=" + URLEncoder.encode("\"${cursor.toLong() + 1}\"", "UTF-8")
        val json = http("GET", "${base(s)}/readings.json?$q") ?: return
        var added = false
        if (json != "null") {
            val o = JSONObject(json)
            var maxKey = cursor.toLongOrNull() ?: 0L
            o.keys().forEach { k ->
                val t = k.toLongOrNull() ?: return@forEach
                if (Repo.db.insertReading(GlucoseReading(t, o.getDouble(k), "cloud"))) added = true
                if (t > maxKey) maxKey = t
            }
            if (maxKey > 0) Repo.store.putString(PULL_CURSOR, maxKey.toString())
        }
        http("GET", "${base(s)}/status.json")?.takeIf { it != "null" }?.let {
            val st = JSONObject(it)
            val start = st.optLong("sensorStart", 0)
            if (start > 0 && start != s.sensorStartMs) Repo.updateSettings { cur -> cur.copy(sensorStartMs = start) }
        }
        if (added) Repo.touch()
    }

    /** Primary follower logs a dose or carbs for the patient. */
    fun sendEvent(s: AppSettings, type: String, value: Double) {
        http("POST", "${base(s)}/events.json", JSONObject()
            .put("type", type).put("value", value).put("time", System.currentTimeMillis()).toString())
    }

    /** Patient phone: import events written by primary followers, then delete them. */
    fun pullAdminEvents(s: AppSettings) {
        if (s.firebaseUrl.isBlank()) return
        val json = http("GET", "${base(s)}/events.json") ?: return
        if (json == "null") return
        val o = JSONObject(json)
        o.keys().forEach { id ->
            val e = o.getJSONObject(id)
            val t = e.optLong("time", System.currentTimeMillis())
            val v = e.optDouble("value", 0.0)
            when (e.optString("type")) {
                "dose" -> Repo.db.addDose(InsulinDose(t, v, InsulinKind.RAPID))
                "carbs" -> Repo.db.addCarbs(CarbEntry(t, v, "من المتابع"))
            }
            http("DELETE", "${base(s)}/events/$id.json")
        }
        Repo.touch()
    }

}
