package com.mm.smartcgm.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.mm.smartcgm.core.CarbEntry
import com.mm.smartcgm.core.Fingerstick
import com.mm.smartcgm.core.GlucoseEffect
import com.mm.smartcgm.core.GlucoseReading
import com.mm.smartcgm.core.InsulinDose
import com.mm.smartcgm.core.InsulinKind
import com.mm.smartcgm.core.MedicationEntry
import com.mm.smartcgm.core.ReadingTrust

/** Local store. Readings keep the RAW sensor value; calibration is applied on read. */
class Db(ctx: Context, name: String = "smartcgm.db", private val localOnly: Boolean = true) : SQLiteOpenHelper(ctx, name, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE readings(time INTEGER PRIMARY KEY, mgdl REAL, source TEXT, sensor TEXT)")
        db.execSQL("CREATE TABLE sticks(time INTEGER PRIMARY KEY, mgdl REAL)")
        db.execSQL("CREATE TABLE doses(time INTEGER, units REAL, kind TEXT)")
        db.execSQL("CREATE TABLE carbs(time INTEGER, grams REAL, note TEXT)")
        db.execSQL("CREATE TABLE meds(time INTEGER, name TEXT, ingredient TEXT, effect TEXT)")
        createIndexes(db)
        createExperimentalTable(db)
        createEventReceipts(db)
    }

    /**
     * Schema plan: v1 = initial tables. v2 = time indexes on doses/carbs/meds (readings/sticks are already keyed by
     * time). v3 = separate experimental timeline. Every future change gets its own `if (oldVersion < N)` block; never drop user data in a migration.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createIndexes(db)
        if (oldVersion < 4) createEventReceipts(db)
        if (oldVersion < 3) {
            createExperimentalTable(db)
            // Relocate earlier scaffold samples atomically; preserve every original value.
            val experimental = "(lower(replace(source, '_', '-')) = 'linx-ble' OR lower(replace(source, '_', '-')) LIKE 'linx-ble:%')"
            db.execSQL("INSERT OR IGNORE INTO experimental_readings SELECT time, mgdl, source, sensor FROM readings WHERE $experimental")
            db.execSQL("DELETE FROM readings WHERE $experimental")
        }
    }

    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS doses_time ON doses(time)")
        db.execSQL("CREATE INDEX IF NOT EXISTS carbs_time ON carbs(time)")
        db.execSQL("CREATE INDEX IF NOT EXISTS meds_time ON meds(time)")
    }

    private fun createExperimentalTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS experimental_readings(time INTEGER PRIMARY KEY, mgdl REAL, source TEXT, sensor TEXT)")
    }

    private fun createEventReceipts(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS imported_events(id TEXT PRIMARY KEY)")
    }

    companion object { const val DB_VERSION = 4 }

    /** Commit receipt and medical log together, before acknowledging the remote event. */
    @Synchronized
    fun importRemoteEvent(key: String, type: String, value: Double, timeMs: Long): Boolean {
        if (timeMs <= 0 || (type != "dose" && type != "carbs")) return false
        if (type == "dose" && !com.mm.smartcgm.core.SafeLimits.validDose(value)) return false
        if (type == "carbs" && !com.mm.smartcgm.core.SafeLimits.validCarbs(value)) return false
        val db = writableDatabase
        db.beginTransaction()
        try {
            val receipt = ContentValues().apply { put("id", key) }
            val added = db.insertWithOnConflict("imported_events", null, receipt, SQLiteDatabase.CONFLICT_IGNORE) != -1L
            if (added) {
                if (type == "dose") addDose(InsulinDose(timeMs, value))
                else addCarbs(CarbEntry(timeMs, value, "من المتابع"))
            }
            db.setTransactionSuccessful()
            return added
        } finally { db.endTransaction() }
    }

    /** Returns false if a reading already exists within ±30 s (same value from two sources). */
    @Synchronized
    fun insertReading(r: GlucoseReading): Boolean {
        val db = writableDatabase
        val table = if (r.withDerivedTrust().trust == ReadingTrust.EXPERIMENTAL) "experimental_readings" else "readings"
        db.rawQuery("SELECT 1 FROM $table WHERE time BETWEEN ? AND ? LIMIT 1",
            arrayOf((r.timeMs - 30_000).toString(), (r.timeMs + 30_000).toString())).use {
            if (it.moveToFirst()) return false
        }
        return db.insert(table, null, ContentValues().apply {
            put("time", r.timeMs); put("mgdl", r.mgdl); put("source", r.source); put("sensor", r.sensorId)
        }) != -1L
    }

    fun readings(fromMs: Long, toMs: Long = Long.MAX_VALUE): List<GlucoseReading> =
        readableDatabase.rawQuery("SELECT time, mgdl, source, sensor FROM readings WHERE time BETWEEN ? AND ? ${if (localOnly) "AND (source IS NULL OR source != 'cloud')" else ""} ORDER BY time",
            arrayOf(fromMs.toString(), toMs.toString())).use { c ->
            buildList { while (c.moveToNext()) add(GlucoseReading(c.getLong(0), c.getDouble(1), c.getString(2) ?: "", c.getString(3)).withDerivedTrust()) }
        }

    /** Isolated experiment timeline; never returned by readings() or lastReadingTime(). */
    fun experimentalReadings(fromMs: Long): List<GlucoseReading> =
        readableDatabase.rawQuery("SELECT time, mgdl, source, sensor FROM experimental_readings WHERE time >= ? ORDER BY time",
            arrayOf(fromMs.toString())).use { c ->
            buildList { while (c.moveToNext()) add(GlucoseReading(c.getLong(0), c.getDouble(1), c.getString(2) ?: "",
                c.getString(3), ReadingTrust.EXPERIMENTAL)) }
        }

    fun addStick(s: Fingerstick) {
        writableDatabase.insertWithOnConflict("sticks", null, ContentValues().apply {
            put("time", s.timeMs); put("mgdl", s.mgdl)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** P2-1: remove one finger-stick (wrong strip, typo). */
    fun deleteStick(timeMs: Long) { writableDatabase.delete("sticks", "time = ?", arrayOf(timeMs.toString())) }

    /** P2-1 "undo calibration": removes every finger-stick from [fromMs] on, so raw sensor values are shown again. */
    fun deleteSticksSince(fromMs: Long) { writableDatabase.delete("sticks", "time >= ?", arrayOf(fromMs.toString())) }

    fun lastReadingTime(): Long? = readableDatabase.rawQuery("SELECT MAX(time) FROM readings ${if (localOnly) "WHERE source IS NULL OR source != 'cloud'" else ""}", null).use { c ->
        if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
    }

    fun sticks(fromMs: Long): List<Fingerstick> =
        readableDatabase.rawQuery("SELECT time, mgdl FROM sticks WHERE time >= ? ORDER BY time", arrayOf(fromMs.toString())).use { c ->
            buildList { while (c.moveToNext()) add(Fingerstick(c.getLong(0), c.getDouble(1))) }
        }

    fun addDose(d: InsulinDose) {
        require(com.mm.smartcgm.core.SafeLimits.validDose(d.units) && d.timeMs > 0) { "Invalid dose" }
        writableDatabase.insertOrThrow("doses", null, ContentValues().apply {
            put("time", d.timeMs); put("units", d.units); put("kind", d.kind.name)
        })
    }

    fun doses(fromMs: Long): List<InsulinDose> =
        readableDatabase.rawQuery("SELECT time, units, kind FROM doses WHERE time >= ? ORDER BY time", arrayOf(fromMs.toString())).use { c ->
            buildList { while (c.moveToNext()) add(InsulinDose(c.getLong(0), c.getDouble(1), InsulinKind.valueOf(c.getString(2)))) }
        }

    fun addCarbs(e: CarbEntry) {
        require(com.mm.smartcgm.core.SafeLimits.validCarbs(e.grams) && e.timeMs > 0) { "Invalid carbs" }
        writableDatabase.insertOrThrow("carbs", null, ContentValues().apply {
            put("time", e.timeMs); put("grams", e.grams); put("note", e.note)
        })
    }

    fun carbs(fromMs: Long): List<CarbEntry> =
        readableDatabase.rawQuery("SELECT time, grams, note FROM carbs WHERE time >= ? ORDER BY time", arrayOf(fromMs.toString())).use { c ->
            buildList { while (c.moveToNext()) add(CarbEntry(c.getLong(0), c.getDouble(1), c.getString(2) ?: "")) }
        }

    fun addMed(m: MedicationEntry) {
        writableDatabase.insert("meds", null, ContentValues().apply {
            put("time", m.timeMs); put("name", m.name); put("ingredient", m.activeIngredient); put("effect", m.effect.name)
        })
    }

    fun meds(fromMs: Long): List<MedicationEntry> =
        readableDatabase.rawQuery("SELECT time, name, ingredient, effect FROM meds WHERE time >= ? ORDER BY time", arrayOf(fromMs.toString())).use { c ->
            buildList {
                while (c.moveToNext()) add(MedicationEntry(c.getLong(0), c.getString(1), c.getString(2) ?: "", GlucoseEffect.valueOf(c.getString(3))))
            }
        }
}
