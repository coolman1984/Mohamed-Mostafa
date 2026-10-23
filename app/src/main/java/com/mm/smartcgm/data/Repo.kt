package com.mm.smartcgm.data

import android.content.Context
import com.mm.smartcgm.core.Calibration
import com.mm.smartcgm.core.DAY_MS
import com.mm.smartcgm.core.GlucoseReading
import com.mm.smartcgm.core.HOUR_MS
import com.mm.smartcgm.core.InsulinMath
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

    fun init(ctx: Context) {
        app = ctx.applicationContext
        db = Db(app)
        store = SettingsStore(app)
        _settings.value = store.load()
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val s = transform(_settings.value)
        store.save(s)
        _settings.value = s
        touch()
    }

    fun touch() { _changes.value = System.currentTimeMillis() }

    @Synchronized
    fun ingest(reading: GlucoseReading) {
        if (reading.mgdl < 20 || reading.mgdl > 600) return
        if (reading.timeMs > System.currentTimeMillis() + 5 * 60_000) return
        val s = _settings.value
        // A new sensor serial means a new sensor: restart the 15/20-day clock.
        val id = reading.sensorId
        if (!id.isNullOrBlank() && id != s.sensorId) {
            updateSettings { it.copy(sensorId = id, sensorStartMs = reading.timeMs) }
        }
        if (db.insertReading(reading)) {
            touch()
            Pipeline.onNewReading(app)
        }
    }

    fun calibration(now: Long = System.currentTimeMillis()): Calibration {
        val sticks = db.sticks(now - 7 * DAY_MS)
        if (sticks.isEmpty()) return Calibration.IDENTITY
        val raw = db.readings(now - 7 * DAY_MS - HOUR_MS)
        return Calibration.fit(Calibration.pair(raw, sticks))
    }

    fun sensorStatus(now: Long = System.currentTimeMillis()): SensorStatus? =
        _settings.value.sensorStartMs.takeIf { it > 0 }?.let { SensorLife.status(it, now) }

    /** Calibrated readings, hidden entirely once the sensor passed its hard limit. */
    fun readings(fromMs: Long, toMs: Long = Long.MAX_VALUE): List<GlucoseReading> {
        val st = sensorStatus()
        val start = _settings.value.sensorStartMs
        val cal = calibration()
        return db.readings(fromMs, toMs)
            .filter { st == null || SensorLife.readingsAllowed(st) || it.timeMs < start }
            .map { it.copy(mgdl = cal.apply(it.mgdl)) }
    }

    fun latest(now: Long = System.currentTimeMillis()): GlucoseReading? = readings(now - 3 * HOUR_MS).lastOrNull()

    fun iob(now: Long = System.currentTimeMillis()): Double {
        val t = _settings.value.therapy
        return InsulinMath.iob(db.doses(now - (t.diaHours * HOUR_MS).toLong()), now, t.diaHours)
    }

    fun cob(now: Long = System.currentTimeMillis()): Double {
        val t = _settings.value.therapy
        return InsulinMath.cob(db.carbs(now - (t.carbAbsorptionHours * HOUR_MS).toLong()), now, t.carbAbsorptionHours)
    }
}
