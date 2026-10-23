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
    /** e.g. https://my-project-default-rtdb.firebaseio.com */
    val firebaseUrl: String = "",
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
) {
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
            firebaseUrl = p.getString("firebaseUrl", "") ?: "",
            accountCode = code,
            followAccountCode = p.getString("followAccountCode", "") ?: "",
            mode = AppMode.valueOf(p.getString("mode", d.mode.name) ?: d.mode.name),
            followRole = FollowerRole.valueOf(p.getString("followRole", d.followRole.name) ?: d.followRole.name),
            pollJuggluco = p.getBoolean("pollJuggluco", d.pollJuggluco),
            jugglucoSecret = p.getString("jugglucoSecret", "") ?: "",
            unit = runCatching { GlucoseUnit.valueOf(p.getString("unit", d.unit.name)!!) }.getOrDefault(d.unit),
            setupDone = p.getBoolean("setupDone", false),
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
            .putString("firebaseUrl", s.firebaseUrl.trimEnd('/')).putString("accountCode", s.accountCode)
            .putString("mode", s.mode.name).putString("followRole", s.followRole.name)
            .putBoolean("pollJuggluco", s.pollJuggluco)
            .putString("followAccountCode", s.followAccountCode)
            .putString("jugglucoSecret", s.jugglucoSecret).putString("unit", s.unit.name)
            .putBoolean("setupDone", s.setupDone)
            .apply()
    }
}
