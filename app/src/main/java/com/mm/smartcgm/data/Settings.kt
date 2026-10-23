package com.mm.smartcgm.data

import android.content.Context
import com.mm.smartcgm.core.AlertConfig
import com.mm.smartcgm.core.AlertRule
import com.mm.smartcgm.core.FollowerRole
import com.mm.smartcgm.core.GlucoseUnit
import com.mm.smartcgm.core.Pairing
import com.mm.smartcgm.core.TherapySettings

enum class AppMode { PATIENT, FOLLOWER }

data class AppSettings(
    val therapy: TherapySettings = TherapySettings(),
    val alerts: AlertConfig = AlertConfig(),
    val sensorStartMs: Long = 0,
    val sensorId: String = "",
    val geminiKey: String = "",
    val geminiModel: String = "gemini-2.5-flash",
    /** Family-sync backend (Cloudflare Worker in backend/). Blank turns sharing off. */
    val backendUrl: String = DEFAULT_BACKEND_URL,
    /** This phone's own random token as patient (owner). Never shown, never sent anywhere except the backend. */
    val ownerToken: String = "",
    /** Token this phone got when it redeemed a follower invite. */
    val followToken: String = "",
    /** The patient's OWN account code. Never overwritten when this phone follows someone else. */
    val accountCode: String = "",
    /** The code of the patient this phone follows (follower mode only). */
    val followAccountCode: String = "",
    val mode: AppMode = AppMode.PATIENT,
    val followRole: FollowerRole = FollowerRole.VIEWER,
    val pollJuggluco: Boolean = true,
    /** Optional Juggluco web-server API secret (only needed if the user set one inside Juggluco). */
    val jugglucoSecret: String = "",
    val unit: GlucoseUnit = GlucoseUnit.MGDL,
    val setupDone: Boolean = false,
    /** P2-5: >0 when readings came back after a >2 h silence; Home asks "new sensor?" until answered. */
    val newSensorSuspectMs: Long = 0,
    /** >0 when the sensor looked restarted soon after day 15 (e.g. Juggluco "Clear"): readings may be badly wrong. */
    val clearSuspectMs: Long = 0,
) {
    companion object { const val DEFAULT_BACKEND_URL = "https://smartcgm-sync.mflma2040.workers.dev" }
    /** Token for the active account: own as patient, the follower token as follower. */
    val activeToken: String get() = if (mode == AppMode.FOLLOWER) followToken else ownerToken
    /** The cloud account this phone reads/writes: its own as patient, the followed one as follower. */
    val activeAccountCode: String get() = if (mode == AppMode.FOLLOWER) followAccountCode else accountCode
}

/** Plain SharedPreferences: small, synchronous, readable from receivers. */
class SettingsStore(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val d = AppSettings()
        val t = d.therapy
        val a = d.alerts
        var code = p.getString("accountCode", "") ?: ""
        // Migration from v0.1: follower phones stored the FOLLOWED code in accountCode. Move it, give the phone its own.
        if (p.getString("mode", "") == AppMode.FOLLOWER.name && p.getString("followAccountCode", "").isNullOrEmpty() && code.isNotEmpty()) {
            p.edit().putString("followAccountCode", code).putString("accountCode", "").apply()
            code = ""
        }
        var owner = p.getString("ownerToken", "") ?: ""
        if (owner.isEmpty()) {
            owner = Pairing.newToken()
            p.edit().putString("ownerToken", owner).apply()
        }
        if (code.isEmpty()) {
            code = Pairing.newAccountCode()
            p.edit().putString("accountCode", code).apply()
        }
        return AppSettings(
            therapy = TherapySettings(
                icr = p.getFloat("icr", t.icr.toFloat()).toDouble(),
                isf = p.getFloat("isf", t.isf.toFloat()).toDouble(),
                targetMgdl = p.getFloat("target", t.targetMgdl.toFloat()).toDouble(),
                diaHours = p.getFloat("dia", t.diaHours.toFloat()).toDouble(),
                carbAbsorptionHours = p.getFloat("absorb", t.carbAbsorptionHours.toFloat()).toDouble(),
                maxBolusUnits = p.getFloat("maxBolus", t.maxBolusUnits.toFloat()).toDouble(),
                lowMgdl = p.getFloat("rangeLow", t.lowMgdl.toFloat()).toDouble(),
                highMgdl = p.getFloat("rangeHigh", t.highMgdl.toFloat()).toDouble(),
            ),
            alerts = AlertConfig(
                low = AlertRule(p.getBoolean("lowOn", a.low.enabled), p.getFloat("lowAt", a.low.thresholdMgdl.toFloat()).toDouble(),
                    p.getInt("lowRepeat", a.low.repeatMinutes)),
                high = AlertRule(p.getBoolean("highOn", a.high.enabled), p.getFloat("highAt", a.high.thresholdMgdl.toFloat()).toDouble(),
                    p.getInt("highRepeat", a.high.repeatMinutes)),
                urgentLowMgdl = p.getFloat("urgentLow", a.urgentLowMgdl.toFloat()).toDouble(),
                predictiveLow = p.getBoolean("predictive", a.predictiveLow),
                staleMinutes = p.getInt("stale", a.staleMinutes),
                criticalOverride = p.getBoolean("critical", a.criticalOverride),
            ),
            sensorStartMs = p.getLong("sensorStart", 0),
            sensorId = p.getString("sensorId", "") ?: "",
            geminiKey = p.getString("geminiKey", "") ?: "",
            geminiModel = p.getString("geminiModel", d.geminiModel) ?: d.geminiModel,
            // Old Firebase URLs are ignored on purpose: sharing moved to the Cloudflare backend.
            backendUrl = p.getString("backendUrl", d.backendUrl) ?: d.backendUrl,
            ownerToken = owner,
            followToken = p.getString("followToken", "") ?: "",
            accountCode = code,
            followAccountCode = p.getString("followAccountCode", "") ?: "",
            mode = AppMode.valueOf(p.getString("mode", d.mode.name) ?: d.mode.name),
            followRole = FollowerRole.valueOf(p.getString("followRole", d.followRole.name) ?: d.followRole.name),
            pollJuggluco = p.getBoolean("pollJuggluco", d.pollJuggluco),
            jugglucoSecret = p.getString("jugglucoSecret", "") ?: "",
            unit = runCatching { GlucoseUnit.valueOf(p.getString("unit", d.unit.name)!!) }.getOrDefault(d.unit),
            setupDone = p.getBoolean("setupDone", false),
            newSensorSuspectMs = p.getLong("newSensorSuspect", 0),
            clearSuspectMs = p.getLong("clearSuspect", 0),
        )
    }

    /** Small key/value slots for engine state (alert memory, cloud cursors) that are not user settings. */
    fun getLong(key: String, def: Long = 0) = p.getLong(key, def)
    fun putLong(key: String, v: Long) = p.edit().putLong(key, v).apply()
    fun getString(key: String): String? = p.getString(key, null)
    fun putString(key: String, v: String) = p.edit().putString(key, v).apply()

    fun save(s: AppSettings) {
        val t = s.therapy
        val a = s.alerts
        p.edit()
            .putFloat("icr", t.icr.toFloat()).putFloat("isf", t.isf.toFloat()).putFloat("target", t.targetMgdl.toFloat())
            .putFloat("dia", t.diaHours.toFloat()).putFloat("absorb", t.carbAbsorptionHours.toFloat())
            .putFloat("maxBolus", t.maxBolusUnits.toFloat())
            .putFloat("rangeLow", t.lowMgdl.toFloat()).putFloat("rangeHigh", t.highMgdl.toFloat())
            .putBoolean("lowOn", a.low.enabled).putFloat("lowAt", a.low.thresholdMgdl.toFloat()).putInt("lowRepeat", a.low.repeatMinutes)
            .putBoolean("highOn", a.high.enabled).putFloat("highAt", a.high.thresholdMgdl.toFloat()).putInt("highRepeat", a.high.repeatMinutes)
            .putFloat("urgentLow", a.urgentLowMgdl.toFloat()).putBoolean("predictive", a.predictiveLow)
            .putInt("stale", a.staleMinutes).putBoolean("critical", a.criticalOverride)
            .putLong("sensorStart", s.sensorStartMs).putString("sensorId", s.sensorId)
            .putString("geminiKey", s.geminiKey).putString("geminiModel", s.geminiModel)
            .putString("backendUrl", s.backendUrl.trimEnd('/'))
            .putString("ownerToken", s.ownerToken).putString("followToken", s.followToken).remove("firebaseUrl").putString("accountCode", s.accountCode)
            .putString("mode", s.mode.name).putString("followRole", s.followRole.name)
            .putBoolean("pollJuggluco", s.pollJuggluco)
            .putString("followAccountCode", s.followAccountCode)
            .putString("jugglucoSecret", s.jugglucoSecret).putString("unit", s.unit.name)
            .putBoolean("setupDone", s.setupDone).putLong("newSensorSuspect", s.newSensorSuspectMs)
            .putLong("clearSuspect", s.clearSuspectMs)
            .apply()
    }
}
