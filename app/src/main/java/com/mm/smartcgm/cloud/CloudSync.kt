package com.mm.smartcgm.cloud

import com.mm.smartcgm.core.CarbEntry
import com.mm.smartcgm.core.FollowerRole
import com.mm.smartcgm.core.GlucoseReading
import com.mm.smartcgm.core.InsulinDose
import com.mm.smartcgm.core.InsulinKind
import com.mm.smartcgm.core.SafeLimits
import com.mm.smartcgm.data.AppSettings
import com.mm.smartcgm.data.Repo
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Family sharing over the Cloudflare Worker in backend/ (D1 database, free tier). Replaces Firebase.
 *
 * Every phone has its OWN random bearer token (the server stores only its hash):
 *  - the patient's phone registers its account code + owner token once, then uploads readings/status;
 *  - a follower redeems a one-time invite code (24 h) and receives a follower token with role ADMIN or VIEWER.
 * The server enforces roles and the proposal limits; the patient's phone re-checks and must approve.
 * No push service: followers poll every minute from MonitorService.
 */
object CloudSync {

    private fun base(s: AppSettings) = "${s.backendUrl.trimEnd('/')}/v1/p/${s.activeAccountCode}"

    private const val PUSH_CURSOR = "cloudLastPushedMs"
    private const val PULL_CURSOR = "cloudLastPulledKey"
    private const val PULL_ACCOUNT = "cloudPulledAccount"
    private const val REGISTERED = "cloudRegisteredAt"
    /** Never try to backfill more than this after a long outage. */
    private const val MAX_BACKFILL_MS = 24 * 60 * 60_000L
    private const val BATCH = 300

    /** Swappable for tests. Returns (status code, body) or null when offline. */
    var transport: (method: String, url: String, token: String?, body: String?) -> Pair<Int, String>? = ::http

    private fun http(method: String, url: String, token: String?, body: String?): Pair<Int, String>? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 8000
        c.readTimeout = 8000
        if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
        try {
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            code to ((if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: "")
        } finally { c.disconnect() }
    } catch (e: java.io.IOException) { null }

    private fun call(method: String, url: String, token: String?, body: JSONObject? = null): JSONObject? {
        val r = transport(method, url, token, body?.toString()) ?: return null
        return if (r.first in 200..299) runCatching { JSONObject(r.second) }.getOrNull() ?: JSONObject() else null
    }

    fun enabled(s: AppSettings) = s.backendUrl.isNotBlank() && s.activeToken.isNotBlank() && s.activeAccountCode.isNotBlank()

    /** Patient phone: claim the account code with this phone's token (idempotent). */
    fun ensureRegistered(s: AppSettings): Boolean {
        val key = "${s.backendUrl}|${s.accountCode}"
        if (Repo.store.getString(REGISTERED) == key) return true
        val ok = call("POST", "${s.backendUrl.trimEnd('/')}/v1/p/${s.accountCode}/register", null,
            JSONObject().put("token", s.ownerToken)) != null
        if (ok) Repo.store.putString(REGISTERED, key)
        return ok
    }

    /** Patient phone: ask the server for a one-time invite code (valid 24 h). */
    fun createInvite(s: AppSettings, role: FollowerRole): String? {
        if (!ensureRegistered(s)) return null
        return call("POST", "${s.backendUrl.trimEnd('/')}/v1/p/${s.accountCode}/invites", s.ownerToken,
            JSONObject().put("role", role.name))?.optString("code")?.takeIf { it.isNotBlank() }
    }

    /** Follower phone: redeem a code. On success the phone switches to follower mode with its own token. */
    fun redeemInvite(s: AppSettings, code: String): Boolean {
        val r = call("POST", "${s.backendUrl.trimEnd('/')}/v1/invites/redeem", null, JSONObject().put("code", code)) ?: return false
        val patient = r.optString("patient")
        val token = r.optString("token")
        val role = runCatching { FollowerRole.valueOf(r.optString("role")) }.getOrNull() ?: return false
        if (patient.isBlank() || token.isBlank() || patient == s.accountCode) return false
        Repo.updateSettings { it.copy(mode = com.mm.smartcgm.data.AppMode.FOLLOWER, followAccountCode = patient,
            followToken = token, followRole = role) }
        return true
    }

    /**
     * Offline queue (AGENTS.md P1-6): uploads everything newer than the last confirmed upload (persisted cursor), in
     * batches. The cursor only moves after the server confirms the write.
     */
    @Synchronized
    fun pushLatest(s: AppSettings) {
        if (!enabled(s) || !ensureRegistered(s)) return
        val now = System.currentTimeMillis()
        val since = maxOf(Repo.store.getLong(PUSH_CURSOR, 0) + 1, now - MAX_BACKFILL_MS)
        val pending = Repo.readings(since)
        if (pending.isEmpty()) return
        for (chunk in pending.chunked(BATCH)) {
            val arr = JSONArray()
            chunk.forEach { arr.put(JSONArray().put(it.timeMs).put(Math.round(it.mgdl))) }
            call("POST", "${base(s)}/readings", s.ownerToken, JSONObject().put("readings", arr)) ?: return
            Repo.store.putLong(PUSH_CURSOR, chunk.last().timeMs)
        }
        val last = pending.last()
        call("PUT", "${base(s)}/status", s.ownerToken, JSONObject()
            .put("time", last.timeMs).put("mgdl", Math.round(last.mgdl))
            .put("iob", Repo.iob(now)).put("cob", Repo.cob(now)).put("sensorStart", s.sensorStartMs))
    }

    /** Follower phone: copy the patient's last 24 h into the local DB (incremental after the first pull). */
    fun pullAsFollower(s: AppSettings) {
        if (!enabled(s)) return
        if (Repo.store.getString(PULL_ACCOUNT) != s.activeAccountCode) {
            Repo.store.putString(PULL_ACCOUNT, s.activeAccountCode)
            Repo.store.putString(PULL_CURSOR, "")
        }
        val cursor = Repo.store.getString(PULL_CURSOR)?.toLongOrNull() ?: 0L
        val r = call("GET", "${base(s)}/readings?after=$cursor", s.followToken) ?: return
        val arr = r.optJSONArray("readings") ?: JSONArray()
        var added = false
        var maxKey = cursor
        for (i in 0 until arr.length()) {
            val p = arr.getJSONArray(i)
            val t = p.getLong(0)
            if (Repo.db.insertReading(GlucoseReading(t, p.getDouble(1), "cloud"))) added = true
            if (t > maxKey) maxKey = t
        }
        if (maxKey > cursor) Repo.store.putString(PULL_CURSOR, maxKey.toString())
        call("GET", "${base(s)}/status", s.followToken)?.optJSONObject("status")?.let { st ->
            val start = st.optLong("sensorStart", 0)
            if (start > 0 && start != s.sensorStartMs) Repo.updateSettings { cur -> cur.copy(sensorStartMs = start) }
        }
        if (added) Repo.touch()
    }

    /** Primary follower logs a dose or carbs for the patient. */
    fun sendEvent(s: AppSettings, type: String, value: Double): Boolean =
        call("POST", "${base(s)}/events", s.followToken, JSONObject()
            .put("type", type).put("value", value).put("time", System.currentTimeMillis())) != null

    /** P2-6: ADMIN follower proposes alert thresholds; the server range-checks; the patient must approve. */
    fun proposeThresholds(s: AppSettings, lowMgdl: Double, highMgdl: Double): Boolean =
        call("PUT", "${base(s)}/proposal", s.followToken, JSONObject().put("low", lowMgdl).put("high", highMgdl)) != null

    /** Patient phone: pick up a pending threshold proposal (validated again locally), then clear it. */
    fun pullThresholdProposal(s: AppSettings) {
        if (!enabled(s)) return
        val o = call("GET", "${base(s)}/proposal", s.ownerToken)?.optJSONObject("proposal") ?: return
        Repo.receiveProposal(com.mm.smartcgm.core.ThresholdProposal(o.optDouble("low", 0.0), o.optDouble("high", 0.0),
            o.optLong("time", System.currentTimeMillis())))
        call("DELETE", "${base(s)}/proposal", s.ownerToken)
    }

    /** Patient phone: import events written by primary followers, then delete them. */
    fun pullAdminEvents(s: AppSettings) {
        if (!enabled(s)) return
        val arr = call("GET", "${base(s)}/events", s.ownerToken)?.optJSONArray("events") ?: return
        if (arr.length() == 0) return
        val now = System.currentTimeMillis()
        var added = false
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val id = e.optLong("id", -1)
            if (id < 0) continue
            // Delete FIRST and import only if the server confirmed it. The old order (import, then delete) re-imported
            // the same dose every minute whenever the delete failed, which doubled the insulin-on-board.
            call("DELETE", "${base(s)}/events/$id", s.ownerToken) ?: continue
            val t = e.optLong("time", now).let { if (it <= 0 || it > now) now else it }
            val v = e.optDouble("value", 0.0)
            when (e.optString("type")) {
                "dose" -> if (SafeLimits.validDose(v)) { Repo.db.addDose(InsulinDose(t, v, InsulinKind.RAPID)); added = true }
                "carbs" -> if (SafeLimits.validCarbs(v)) { Repo.db.addCarbs(CarbEntry(t, v, "من المتابع")); added = true }
            }
        }
        if (added) Repo.touch()
    }
}
