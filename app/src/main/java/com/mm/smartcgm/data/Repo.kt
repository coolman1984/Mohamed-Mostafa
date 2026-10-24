package com.mm.smartcgm.data

import android.content.Context
import com.mm.smartcgm.core.ClinicalGate
import com.mm.smartcgm.core.ReadingTrust
import com.mm.smartcgm.core.SensorPhase
import com.mm.smartcgm.core.BleReadingValidator
import com.mm.smartcgm.core.Calibration
import com.mm.smartcgm.core.DAY_MS
import com.mm.smartcgm.core.GlucoseReading
import com.mm.smartcgm.core.HOUR_MS
import com.mm.smartcgm.core.InsulinMath
import com.mm.smartcgm.core.SafeLimits
import com.mm.smartcgm.core.SensorLife
import com.mm.smartcgm.core.SensorStatus
import com.mm.smartcgm.engine.Pipeline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Single entry point for data. Every source calls [ingest]; the UI watches [changes]. */
object Repo {
    lateinit var db: Db
        private set
    lateinit var store: SettingsStore
        private set
    private lateinit var app: Context

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> get() = _settings

    /** Bumped on every data change so screens recompute. */
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> get() = _changes

    val profile: String get() = profileOf(_settings.value)
    private fun profileOf(s: AppSettings): String = if (s.mode == AppMode.PATIENT) "patient" else
        "follow-" + digest("${s.backendUrl.trimEnd('/')}|${s.followAccountCode}")
    private fun digest(s: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun database(s: AppSettings) = Db(app, if (s.mode == AppMode.PATIENT) "smartcgm.db" else "${profileOf(s)}.db",
        localOnly = s.mode == AppMode.PATIENT)

    fun isCurrent(s: AppSettings): Boolean = _settings.value.let {
        it.mode == s.mode && it.backendUrl == s.backendUrl && it.activeAccountCode == s.activeAccountCode && it.activeToken == s.activeToken
    }

    @Synchronized
    fun <T> withProfile(s: AppSettings, work: () -> T): T? = if (isCurrent(s)) work() else null

    @Synchronized
    fun init(ctx: Context) {
        app = ctx.applicationContext
        store = SettingsStore(app)
        // Clamp on load too, so a bad value saved by an older version can't reach the math.
        _settings.value = safe(store.load())
        db = database(_settings.value)
        sourceSeen.clear()
        invalidateCalibration()
        touch()
    }

    /** Every settings write goes through the safe limits (typos can't reach dosing or switch off urgent low). */
    private fun safe(s: AppSettings) =
        s.copy(therapy = SafeLimits.therapy(s.therapy), alerts = SafeLimits.alerts(s.alerts))

    @Synchronized
    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val old = _settings.value
        var s = safe(transform(old))
        if (profileOf(old) != profileOf(s)) {
            val key = "lifecycle:${profileOf(old)}:"
            store.putLong(key + "start", old.sensorStartMs)
            store.putString(key + "sensor", old.sensorId)
            store.putLong(key + "suspect", old.newSensorSuspectMs)
            store.putLong(key + "clear", old.clearSuspectMs)
            val next = "lifecycle:${profileOf(s)}:"
            s = s.copy(sensorStartMs = store.getLong(next + "start"), sensorId = store.getString(next + "sensor") ?: "",
                newSensorSuspectMs = store.getLong(next + "suspect"), clearSuspectMs = store.getLong(next + "clear"))
            db.close()
            db = database(s)
            invalidateCalibration()
            sourceSeen.clear()
        }
        store.save(s)
        _settings.value = s
        touch()
    }

    @Synchronized
    fun touch() { _changes.value = _changes.value + 1 }

    @Synchronized
    fun ingest(reading: GlucoseReading) {
        if (_settings.value.mode != AppMode.PATIENT) return
        if (!reading.mgdl.isFinite() || reading.mgdl < 20 || reading.mgdl > 600) return
        if (reading.timeMs <= 0) return
        if (reading.withDerivedTrust().trust == ReadingTrust.EXPERIMENTAL) {
            // Quarantine before lifecycle, calibration, source health, alarms or cloud side effects.
            if (reading.timeMs > System.currentTimeMillis()) return
            if (db.insertReading(reading)) touch()
            return
        }
        if (reading.timeMs > System.currentTimeMillis()) return
        val s = _settings.value
        // A new sensor serial means a new sensor: restart the 15/20-day clock.
        val id = reading.sensorId
        val retiredKey = "retiredSensors:$profile"
        val retired = (store.getString(retiredKey) ?: "").split(',').filter { it.isNotBlank() }.toSet()
        if (!id.isNullOrBlank() && id != s.sensorId && digest(id) in retired) return
        val prev = db.lastReadingTime()
        if (!db.insertReading(reading)) return
        invalidateCalibration()
        // Backfill is historical storage, never evidence of a newly installed sensor.
        if (prev != null && reading.timeMs <= prev) { touch(); return }
        if (s.mode == com.mm.smartcgm.data.AppMode.PATIENT && s.clearSuspectMs == 0L && SensorLife.suspectedClear(prev,
                reading.timeMs, s.sensorStartMs, sameSerial = id.isNullOrBlank() || id == s.sensorId)) {
            // Possible Juggluco "Clear" after day 15: keep the OLD start (no fresh 15 days) and warn loudly.
            updateSettings { it.copy(clearSuspectMs = reading.timeMs) }
            if (!id.isNullOrBlank() && id != s.sensorId) updateSettings { it.copy(sensorId = id) }
        } else if (!id.isNullOrBlank() && id != s.sensorId) {
            if (s.sensorId.isNotBlank()) store.putString(retiredKey, (retired + digest(s.sensorId)).joinToString(","))
            updateSettings { it.copy(sensorId = id, sensorStartMs = reading.timeMs, newSensorSuspectMs = 0) }
        } else if (s.mode == com.mm.smartcgm.data.AppMode.PATIENT &&
            SensorLife.newSensorSuspected(prev, reading.timeMs, s.sensorStartMs)) {
            // P2-5: no serial (notification / web sources) but a >2 h gap: ask the user, never restart the clock silently.
            updateSettings { it.copy(newSensorSuspectMs = reading.timeMs) }
        }
        sourceSeen[sourceGroup(reading.source)] = System.currentTimeMillis()
        touch()
        Pipeline.onNewReading(app)
    }

    /** Last time (wall clock) each kind of source delivered something; feeds the "data source health" card. */
    val sourceSeen = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun sourceGroup(source: String): String = when {
        source.startsWith("juggluco-web") -> "juggluco-web"
        source.startsWith("app:") -> "notification"
        else -> source
    }

    /** Last HTTP status of the Juggluco web poll (-1 = not tried, 0 = not reachable, 403 = wrong API secret). */
    @Volatile var lastJugglucoWebStatus: Int = -1

    private var calCache: Calibration? = null
    private var calKey: Long = Long.MIN_VALUE

    enum class BolusCommit { SAVED, CHANGED, UNAVAILABLE }

    /** One lock covers eligibility, latest IOB/reading, displayed proposal comparison, and the DB transaction. */
    @Synchronized
    fun confirmBolus(expected: AppSettings, shown: com.mm.smartcgm.core.BolusSuggestion, carbs: Double,
                     now: Long = System.currentTimeMillis()): BolusCommit {
        if (!isCurrent(expected) || (carbs != 0.0 && !SafeLimits.validCarbs(carbs))) return BolusCommit.UNAVAILABLE
        val recent = dosingReadings(now)
        if (recent.isEmpty()) return BolusCommit.UNAVAILABLE
        val latestSettings = _settings.value
        val next = com.mm.smartcgm.core.BolusAdvisor.suggest(carbs, recent.last().mgdl, iob(now), latestSettings.therapy,
            com.mm.smartcgm.core.Prediction.forecast(recent, latestSettings.alerts.low.thresholdMgdl)?.minutesToLow != null)
        if (next.totalUnits != shown.totalUnits || next.blockedForLow != shown.blockedForLow) return BolusCommit.CHANGED
        db.logBolus(now, carbs, next.totalUnits)
        touch()
        return BolusCommit.SAVED
    }

    @Synchronized
    fun logDose(expected: AppSettings, dose: com.mm.smartcgm.core.InsulinDose): Boolean =
        withProfile(expected) {
            if (expected.mode != AppMode.PATIENT || !SafeLimits.validDose(dose.units)) return@withProfile false
            db.addDose(dose); touch(); true
        } ?: false

    @Synchronized
    fun logCarbs(expected: AppSettings, carbs: com.mm.smartcgm.core.CarbEntry): Boolean =
        withProfile(expected) {
            if (expected.mode != AppMode.PATIENT || !SafeLimits.validCarbs(carbs.grams)) return@withProfile false
            db.addCarbs(carbs); touch(); true
        } ?: false

    @Synchronized
    fun addStick(s: com.mm.smartcgm.core.Fingerstick, expected: AppSettings = _settings.value) {
        if (!isCurrent(expected) || expected.mode != AppMode.PATIENT || !s.mgdl.isFinite() || s.mgdl !in 20.0..600.0) return
        db.addStick(s)
        invalidateCalibration()
        touch()
    }

    @Synchronized
    fun deleteStick(timeMs: Long) { db.deleteStick(timeMs); invalidateCalibration(); touch() }

    /** "Undo calibration": forget the finger-sticks of the 7-day calibration window. */
    @Synchronized
    fun undoCalibration(now: Long = System.currentTimeMillis()) {
        db.deleteSticksSince(now - 7 * DAY_MS); invalidateCalibration(); touch()
    }

    /** User read the "possible Juggluco Clear" warning. Does NOT restart the sensor clock. */
    fun dismissClearWarning() = updateSettings { it.copy(clearSuspectMs = 0) }

    /** Answer to the "new sensor?" question. Yes restarts the 15/20-day clock from the first reading after the gap. */
    fun answerNewSensor(yes: Boolean) = updateSettings {
        if (yes) it.copy(sensorStartMs = it.newSensorSuspectMs, sensorId = "", newSensorSuspectMs = 0) else it.copy(newSensorSuspectMs = 0)
    }

    // ---- P2-6: threshold proposals from an ADMIN follower, waiting for the patient's approval ----
    private val PROPOSAL_KEY: String get() = "pendingThresholdProposal:$profile"

    fun pendingProposal(): com.mm.smartcgm.core.ThresholdProposal? =
        store.getString(PROPOSAL_KEY)?.split(',')?.takeIf { it.size == 3 }?.let {
            runCatching { com.mm.smartcgm.core.ThresholdProposal(it[0].toDouble(), it[1].toDouble(), it[2].toLong()) }.getOrNull()
        }

    /** Stores a proposal only if it passes the safety limits; returns false otherwise. */
    fun receiveProposal(p: com.mm.smartcgm.core.ThresholdProposal): Boolean {
        if (_settings.value.mode != AppMode.PATIENT || p.problem(_settings.value.alerts.urgentLowMgdl) != null) return false
        val seenKey = "proposalSeen:$profile"
        if (p.timeMs <= store.getLong(seenKey)) return false
        store.putLong(seenKey, p.timeMs)
        store.putString(PROPOSAL_KEY, "${p.lowMgdl},${p.highMgdl},${p.timeMs}"); touch(); return true
    }

    /** Patient's decision. Approve re-validates, then changes only the low/high alert thresholds (never urgent low). */
    fun decideProposal(approve: Boolean) {
        val p = pendingProposal()
        store.putString(PROPOSAL_KEY, "")
        if (approve && p != null && p.problem(_settings.value.alerts.urgentLowMgdl) == null) updateSettings {
            it.copy(alerts = it.alerts.copy(low = it.alerts.low.copy(thresholdMgdl = p.lowMgdl),
                high = it.alerts.high.copy(thresholdMgdl = p.highMgdl)))
        } else touch()
    }

    /** P2-2: doses, carbs and meds as logbook events. */
    @Synchronized
    fun logEvents(fromMs: Long): List<com.mm.smartcgm.core.LogEvent> =
        com.mm.smartcgm.core.Logbook.events(db.doses(fromMs), db.carbs(fromMs), db.meds(fromMs))

    fun invalidateCalibration() = synchronized(this) { calCache = null }

    /** Cached: the fit only changes when a finger-stick is added, or every 10 minutes as the 7-day window slides. */
    fun calibration(now: Long = System.currentTimeMillis()): Calibration = synchronized(this) {
        if (_settings.value.mode == AppMode.FOLLOWER) return Calibration.IDENTITY
        val key = now / (10 * 60_000L)
        calCache?.takeIf { calKey == key }?.let { return it }
        val sticks = db.sticks(now - 7 * DAY_MS)
        val fit = if (sticks.isEmpty()) Calibration.IDENTITY
        else Calibration.fit(Calibration.pair(ClinicalGate.forDosing(db.readings(now - 7 * DAY_MS - HOUR_MS)), sticks))
        calCache = fit; calKey = key
        fit
    }

    fun sensorStatus(now: Long = System.currentTimeMillis()): SensorStatus? =
        _settings.value.sensorStartMs.takeIf { it > 0 }?.let { SensorLife.status(it, now) }

    /** Calibrated readings, hidden entirely once the sensor passed its hard limit. */
    @Synchronized
    fun readings(fromMs: Long, toMs: Long = Long.MAX_VALUE): List<GlucoseReading> {
        val st = sensorStatus()
        val start = _settings.value.sensorStartMs
        val cal = calibration()
        return ClinicalGate.forDosing(db.readings(fromMs, toMs))
            .filter { st == null || SensorLife.readingsAllowed(st) || it.timeMs < start }
            .map { it.copy(mgdl = cal.apply(it.mgdl)) }
    }

    /** Known expired/restarted sensor values are not a clinical alarm input. Existing urgent sound is never auto-cleared. */
    @Synchronized
    fun alarmReadings(now: Long = System.currentTimeMillis()): List<GlucoseReading> {
        val current = _settings.value
        val phase = sensorStatus(now)?.phase
        if (current.clearSuspectMs > 0 || phase == SensorPhase.EXTENDED || phase == SensorPhase.EXPIRED) return emptyList()
        return ClinicalGate.forDosing(readings(now - HOUR_MS, now))
    }

    /** Eligibility gate used by every dose entry point; never falls back to meal-only advice. */
    @Synchronized
    fun dosingReadings(now: Long = System.currentTimeMillis()): List<GlucoseReading> {
        val settings = _settings.value
        if (settings.mode != AppMode.PATIENT || settings.sensorStartMs <= 0 || settings.clearSuspectMs > 0 || settings.newSensorSuspectMs > 0) return emptyList()
        val phase = sensorStatus(now)?.phase
        if (phase != null && phase != SensorPhase.ACTIVE) return emptyList()
        val recent = ClinicalGate.forDosing(readings(now - HOUR_MS, now))
        if (BleReadingValidator.isStale(recent.lastOrNull(), now)) return emptyList()
        return recent
    }

    @Synchronized
    fun latest(now: Long = System.currentTimeMillis()): GlucoseReading? = readings(now - 3 * HOUR_MS).lastOrNull()

    @Synchronized
    fun iob(now: Long = System.currentTimeMillis()): Double {
        val t = _settings.value.therapy
        return InsulinMath.iob(db.doses(now - (t.diaHours * HOUR_MS).toLong()), now, t.diaHours)
    }

    @Synchronized
    fun cob(now: Long = System.currentTimeMillis()): Double {
        val t = _settings.value.therapy
        return InsulinMath.cob(db.carbs(now - (t.carbAbsorptionHours * HOUR_MS).toLong()), now, t.carbAbsorptionHours)
    }
}
