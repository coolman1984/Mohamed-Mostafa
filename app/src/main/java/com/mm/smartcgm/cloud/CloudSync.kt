package com.mm.smartcgm.cloud

import com.mm.smartcgm.core.CarbEntry
import com.mm.smartcgm.core.FollowerRole
import com.mm.smartcgm.core.GlucoseReading
import com.mm.smartcgm.core.InsulinDose
import com.mm.smartcgm.core.InsulinKind
import com.mm.smartcgm.core.SafeLimits
import com.mm.smartcgm.core.ReadingTrust
import com.mm.smartcgm.data.AppMode
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

    private fun key(s: AppSettings, purpose: String, credentials: Boolean = true): String = purpose + ":" +
        java.security.MessageDigest.getInstance("SHA-256").digest(
            ("${s.backendUrl.trimEnd('/')}|${s.activeAccountCode}" + if (credentials) "|${s.activeToken}" else "").toByteArray())
            .joinToString("") { "%02x".format(it) }
    private fun current(s: AppSettings) = Repo.isCurrent(s)

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
        require(URL(url).protocol == "https") { "HTTPS required" }
        c.instanceFollowRedirects = false
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
            code to ((if (code in 200..299) c.inputStream else c.errorStream)?.use { stream ->
                val bytes = boundedRead(stream)
                require(bytes.size <= 1_000_000) { "Response too large" }
                bytes.toString(Charsets.UTF_8)
            } ?: "")
        } finally { c.disconnect() }
    } catch (e: java.io.IOException) { null }

    private fun boundedRead(stream: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            require(out.size() + n <= 1_000_000) { "Response too large" }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun call(method: String, url: String, token: String?, body: JSONObject? = null): JSONObject? {
        val r = runCatching { transport(method, url, token, body?.toString()) }.getOrNull() ?: return null
        return if (r.first in 200..299) runCatching { if (r.second.isBlank()) JSONObject() else JSONObject(r.second) }.getOrNull() else null
    }

    fun enabled(s: AppSettings) = runCatching {
        val u = java.net.URI(s.backendUrl)
        u.scheme == "https" && !u.host.isNullOrBlank() && u.rawUserInfo == null && u.rawQuery == null && u.rawFragment == null &&
            s.activeToken.length in 32..128 && s.activeAccountCode.matches(Regex("[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{12,32}"))
    }.getOrDefault(false)

    /** Patient phone: claim the account code with this phone's token (idempotent). */
    fun ensureRegistered(s: AppSettings): Boolean {
        if (!enabled(s) || s.mode != AppMode.PATIENT || !current(s)) return false
        val key = key(s, REGISTERED)
        if (Repo.store.getString(key) == "registered") return true
        val ok = call("POST", "${s.backendUrl.trimEnd('/')}/v1/p/${s.accountCode}/register", null,
            JSONObject().put("token", s.ownerToken)) != null
        if (ok) Repo.withProfile(s) { Repo.store.putString(key, "registered") }
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
        if (!current(s) || s.backendUrl.isBlank()) return false
        val parsed = com.mm.smartcgm.core.Pairing.parseInvite(code) ?: return false
        val r = call("POST", "${s.backendUrl.trimEnd('/')}/v1/invites/redeem", null, JSONObject().put("code", parsed)) ?: return false
        val patient = r.optString("patient")
        val token = r.optString("token")
        val role = runCatching { FollowerRole.valueOf(r.optString("role")) }.getOrNull() ?: return false
        if (!patient.matches(Regex("[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{12,32}")) || token.length !in 32..128 || patient == s.accountCode) return false
        return Repo.withProfile(s) {
            Repo.updateSettings { it.copy(mode = AppMode.FOLLOWER, followAccountCode = patient, followToken = token, followRole = role) }
            true
        } ?: false
    }

    /**
     * Offline queue (AGENTS.md P1-6): uploads everything newer than the last confirmed upload (persisted cursor), in
     * batches. The cursor only moves after the server confirms the write.
     */
    @Synchronized
    fun pushLatest(s: AppSettings) {
        if (s.mode != AppMode.PATIENT || !current(s) || !enabled(s) || !ensureRegistered(s)) return
        val now = System.currentTimeMillis()
        val cursorKey = key(s, PUSH_CURSOR)
        val pending = Repo.withProfile(s) {
            val since = maxOf(Repo.store.getLong(cursorKey) + 1, now - MAX_BACKFILL_MS)
            Repo.readings(since, now)
        } ?: return
        for (chunk in pending.chunked(BATCH)) {
            if (!current(s)) return
            val arr = JSONArray()
            chunk.forEach { arr.put(JSONArray().put(it.timeMs).put(Math.round(it.mgdl))) }
            val ack = call("POST", "${base(s)}/readings", s.ownerToken, JSONObject().put("readings", arr)) ?: return
            if (ack.optInt("rejected", -1) != 0 || ack.optInt("stored", -1) != chunk.size) return
            Repo.withProfile(s) { Repo.store.putLong(cursorKey, chunk.last().timeMs) } ?: return
        }
        // Retry status even with no new samples, e.g. the previous upload succeeded but status failed.
        val status = Repo.withProfile(s) {
            val last = Repo.latest(now) ?: return@withProfile null
            JSONObject().put("time", last.timeMs).put("mgdl", Math.round(last.mgdl))
                .put("iob", Repo.iob(now)).put("cob", Repo.cob(now)).put("sensorStart", Repo.settings.value.sensorStartMs)
        } ?: return
        if (current(s)) call("PUT", "${base(s)}/status", s.ownerToken, status)
    }

    /** Every response is applied only to the profile that issued it. Metadata cannot be laundered into cloud trust. */
    @Synchronized
    fun pullAsFollower(s: AppSettings) {
        if (s.mode != AppMode.FOLLOWER || !enabled(s) || !current(s)) return
        val cursorKey = key(s, PULL_CURSOR)
        val cursor = Repo.withProfile(s) { Repo.store.getLong(cursorKey) } ?: return
        val r = call("GET", "${base(s)}/readings?format=full&after=$cursor", s.followToken) ?: return
        val arr = r.optJSONArray("readings") ?: return
        val now = System.currentTimeMillis()
        Repo.withProfile(s) {
            var maxKey = cursor
            var added = false
            for (i in 0 until arr.length()) {
                val item = arr.opt(i)
                val time = when (item) { is JSONArray -> item.optLong(0); is JSONObject -> item.optLong("t"); else -> 0 }
                val value = when (item) { is JSONArray -> item.optDouble(1, Double.NaN); is JSONObject -> item.optDouble("mgdl", Double.NaN); else -> Double.NaN }
                if (time <= 0 || time > now || now - time > 30L * com.mm.smartcgm.core.DAY_MS || !value.isFinite() || value !in 20.0..600.0) continue
                val experimental = item is JSONObject && (item.optString("source") == "linx_ble" || item.optDouble("ageDays", 0.0) >= 15)
                val trust = if (experimental) ReadingTrust.EXPERIMENTAL else ReadingTrust.TRUSTED
                if (Repo.db.insertReading(GlucoseReading(time, value, "cloud", trust = trust))) added = true
                maxKey = maxOf(maxKey, time)
            }
            if (maxKey > cursor) Repo.store.putLong(cursorKey, maxKey)
            if (added) Repo.touch()
        } ?: return
        val st = call("GET", "${base(s)}/status", s.followToken)?.optJSONObject("status") ?: return
        Repo.withProfile(s) {
            val start = st.optLong("sensorStart", 0)
            if (start in 1..now && start != Repo.settings.value.sensorStartMs)
                Repo.updateSettings { it.copy(sensorStartMs = start) }
        }
    }

    /** Primary follower logs a dose or carbs for the patient. */
    fun sendEvent(s: AppSettings, type: String, value: Double): Boolean {
        if (!current(s) || s.mode != AppMode.FOLLOWER || s.followRole != FollowerRole.ADMIN || !enabled(s)) return false
        if (type == "dose" && !SafeLimits.validDose(value) || type == "carbs" && !SafeLimits.validCarbs(value) || type !in listOf("dose", "carbs")) return false
        return call("POST", "${base(s)}/events", s.followToken, JSONObject()
            .put("type", type).put("value", value).put("time", System.currentTimeMillis())) != null
    }

    /** P2-6: ADMIN follower proposes alert thresholds; the server range-checks; the patient must approve. */
    fun proposeThresholds(s: AppSettings, lowMgdl: Double, highMgdl: Double): Boolean {
        if (!current(s) || !enabled(s) || s.mode != AppMode.FOLLOWER || s.followRole != FollowerRole.ADMIN) return false
        if (com.mm.smartcgm.core.ThresholdProposal(lowMgdl, highMgdl, System.currentTimeMillis()).problem() != null) return false
        return call("PUT", "${base(s)}/proposal", s.followToken, JSONObject().put("low", lowMgdl).put("high", highMgdl)) != null
    }

    /** Patient phone: persist a proposal only in its originating profile, then acknowledge it. */
    fun pullThresholdProposal(s: AppSettings) {
        if (s.mode != AppMode.PATIENT || !enabled(s) || !current(s)) return
        val o = call("GET", "${base(s)}/proposal", s.ownerToken)?.optJSONObject("proposal") ?: return
        Repo.withProfile(s) {
            Repo.receiveProposal(com.mm.smartcgm.core.ThresholdProposal(o.optDouble("low", Double.NaN), o.optDouble("high", Double.NaN),
                o.optLong("time", 0)))
        } ?: return
        if (current(s)) call("DELETE", "${base(s)}/proposal", s.ownerToken)
    }

    /** Patient phone: import events written by primary followers, then delete them. */
    @Synchronized
    fun pullAdminEvents(s: AppSettings) {
        if (s.mode != AppMode.PATIENT || !current(s) || !enabled(s)) return
        val arr = call("GET", "${base(s)}/events", s.ownerToken)?.optJSONArray("events") ?: return
        val now = System.currentTimeMillis()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val id = e.optLong("id", -1)
            val t = e.optLong("time", 0)
            if (id < 0 || t <= 0 || t > now || now - t > 24 * 60 * 60_000L) continue
            val type = e.optString("type")
            val value = e.optDouble("value", Double.NaN)
            val stored = Repo.withProfile(s) {
                val added = Repo.db.importRemoteEvent(key(s, "event:$id", credentials = false), type, value, t)
                if (added) Repo.touch()
                true
            } ?: return
            // Receipt + dose are durable before ACK. A lost ACK can only retry the receipt, never add another dose.
            if (stored && current(s)) call("DELETE", "${base(s)}/events/$id", s.ownerToken)
        }
    }
}
