package com.mm.smartcgm

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.mm.smartcgm.core.*
import com.mm.smartcgm.cloud.CloudSync
import com.mm.smartcgm.data.Db
import com.mm.smartcgm.data.Repo
import com.mm.smartcgm.engine.Pipeline
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Tests actual storage, clinical consumers and the legacy cloud payload, not just gate helpers. */
@RunWith(RobolectricTestRunner::class)
class LinxSafetyIntegrationTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val now get() = System.currentTimeMillis()

    @Before fun fresh() {
        ctx.deleteDatabase("smartcgm.db")
        ctx.getSharedPreferences("settings", 0).edit().clear().commit()
        Repo.init(ctx)
        Repo.updateSettings { it.copy(backendUrl = "", sensorStartMs = 0) }
        Repo.sourceSeen.clear()
        Repo.invalidateCalibration()
    }

    @Test fun experimentDoesNotChangeSensorClockOrTrustedSourceHealth() {
        Repo.ingest(GlucoseReading(now, 250.0, "linx_ble", "UNVERIFIED-SENSOR"))
        assertEquals(0L, Repo.settings.value.sensorStartMs)
        assertTrue(Repo.sourceSeen.isEmpty())
        assertTrue(Repo.readings(0).isEmpty())
        assertNull(Repo.db.lastReadingTime())
        assertTrue(Repo.dosingReadings().isEmpty())
        assertEquals(ReadingTrust.EXPERIMENTAL, Repo.db.experimentalReadings(0).single().trust)
    }

    @Test fun trustedAndExperimentalReadingsCannotDeduplicateEachOtherInEitherOrder() {
        val t = now - 60_000
        Repo.ingest(GlucoseReading(t, 300.0, "linx-ble"))
        Repo.ingest(GlucoseReading(t, 50.0, "juggluco"))
        Repo.ingest(GlucoseReading(t + 60_000, 51.0, "juggluco"))
        Repo.ingest(GlucoseReading(t + 60_000, 301.0, "linx_ble"))
        assertEquals(listOf(50.0, 51.0), Repo.readings(0).map { it.mgdl })
        assertEquals(listOf(300.0, 301.0), Repo.db.experimentalReadings(0).map { it.mgdl })
        assertEquals(51.0, Repo.dosingReadings().last().mgdl, 0.0)
    }

    @Test fun experimentalDoesNotContaminateCalibration() {
        val t = now - 60_000
        Repo.ingest(GlucoseReading(t, 100.0, "juggluco"))
        Repo.ingest(GlucoseReading(t, 300.0, "linx-ble"))
        // Meter 120 - trusted raw 100 = +20 offset. Experiment 300 must not participate.
        Repo.addStick(Fingerstick(t, 120.0))
        assertEquals(120.0, Repo.readings(0).single().mgdl, 0.0001)
        assertEquals(300.0, Repo.db.experimentalReadings(0).single().mgdl, 0.0)
    }

    @Test fun freshnessAndOfficialLifeGateEveryDoseEntryPoint() {
        val t = now
        Repo.ingest(GlucoseReading(t - 6 * MINUTE_MS, 250.0, "juggluco"))
        assertTrue(Repo.dosingReadings(t).isEmpty())
        Repo.ingest(GlucoseReading(t, 240.0, "juggluco"))
        assertEquals(240.0, Repo.dosingReadings(t).last().mgdl, 0.0)
        Repo.updateSettings { it.copy(sensorStartMs = t - 16 * DAY_MS) }
        assertTrue(Repo.dosingReadings(t).isEmpty())
        // Existing historical visibility policy remains available.
        assertFalse(Repo.readings(0).isEmpty())
    }

    @Test fun experimentCannotSuppressTrustedUrgentLowOrLossOfSignal() {
        val t = now
        Repo.ingest(GlucoseReading(t - MINUTE_MS, 50.0, "juggluco"))
        Repo.ingest(GlucoseReading(t, 180.0, "linx-ble"))
        val trusted = Repo.readings(0)
        val cfg = AlertConfig()
        val latest = ClinicalGate.forAlarm(trusted, t, cfg.staleMinutes * MINUTE_MS)
        val alerts = AlertEngine.evaluate(latest, null, null, cfg, AlertState(), t).first
        assertTrue(alerts.any { it.type == AlertType.URGENT_LOW })
        // Past the normal trusted-source timeout, an experiment still cannot conceal signal loss.
        val later = t + 21 * MINUTE_MS
        val lost = AlertEngine.evaluate(ClinicalGate.forAlarm(trusted, later, cfg.staleMinutes * MINUTE_MS),
            null, null, cfg, AlertState(), later).first
        assertTrue(lost.any { it.type == AlertType.SIGNAL_LOST })
        Pipeline.evaluate(ctx) // exercise the real consumer too
    }

    @Test fun cloudUploadCannotLaunderExperimentIntoTrustedLegacyTuple() {
        val t = now - MINUTE_MS
        Repo.ingest(GlucoseReading(t, 120.0, "juggluco"))
        Repo.ingest(GlucoseReading(t + MINUTE_MS, 555.0, "linx-ble"))
        val real = CloudSync.transport
        val bodies = mutableListOf<String>()
        try {
            CloudSync.transport = { _, _, _, body -> body?.let { bodies += it }; 200 to "{}" }
            CloudSync.pushLatest(Repo.settings.value.copy(backendUrl = "https://example.invalid"))
        } finally { CloudSync.transport = real }
        assertTrue(bodies.any { it.contains("120") })
        assertFalse(bodies.any { it.contains("555") })
    }

    @Test fun explicitExperimentalFlagSurvivesReloadEvenWithAnotherSourceName() {
        Repo.ingest(GlucoseReading(now, 120.0, "custom-source", trust = ReadingTrust.EXPERIMENTAL))
        assertTrue(Repo.readings(0).isEmpty())
        assertEquals(ReadingTrust.EXPERIMENTAL, Repo.db.experimentalReadings(0).single().trust)
    }

    @Test fun v2MigrationPreservesDataAndMovesExperimentalAliasesOutOfClinicalTimeline() {
        Repo.db.close()
        ctx.deleteDatabase("smartcgm.db")
        val path = ctx.getDatabasePath("smartcgm.db")
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("CREATE TABLE readings(time INTEGER PRIMARY KEY, mgdl REAL, source TEXT, sensor TEXT)")
            db.execSQL("INSERT INTO readings VALUES(1800000000000,50,'juggluco','A')")
            db.execSQL("INSERT INTO readings VALUES(1800000060000,300,'LINX_BLE:legacy','B')")
            db.version = 2
        }
        Db(ctx).use { db ->
            assertEquals(50.0, db.readings(0).single().mgdl, 0.0)
            assertEquals(300.0, db.experimentalReadings(0).single().mgdl, 0.0)
            // The trusted source remains insertable at the exact timestamp formerly occupied by the experiment.
            assertTrue(db.insertReading(GlucoseReading(1_800_000_060_000L, 51.0, "juggluco")))
        }
    }

    @Test fun nonFiniteValuesNeverReachEitherTimeline() {
        Repo.ingest(GlucoseReading(now, Double.NaN, "juggluco"))
        Repo.ingest(GlucoseReading(now, Double.NaN, "linx-ble"))
        assertTrue(Repo.db.readings(0).isEmpty())
        assertTrue(Repo.db.experimentalReadings(0).isEmpty())
    }
}
