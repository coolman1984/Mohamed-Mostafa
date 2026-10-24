package com.mm.smartcgm

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.mm.smartcgm.cloud.CloudSync
import com.mm.smartcgm.core.*
import com.mm.smartcgm.data.*
import com.mm.smartcgm.engine.Alarms
import com.mm.smartcgm.engine.SnoozeReceiver
import com.mm.smartcgm.sources.GlucoseBroadcastReceiver
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeepReviewIntegrationTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val now get() = System.currentTimeMillis()
    private val real = CloudSync.transport
    private val pidA = "ZZZZZZZZZZZZZZZZ"
    private val pidB = "YYYYYYYYYYYYYYYY"

    @Before fun fresh() {
        ctx.databaseList().forEach { ctx.deleteDatabase(it) }
        ctx.getSharedPreferences("settings", 0).edit().clear().commit()
        Repo.init(ctx)
        Repo.updateSettings { it.copy(backendUrl = "https://example.invalid", sensorStartMs = now - 2 * DAY_MS) }
        CloudSync.transport = { _, _, _, _ -> null }
    }
    @After fun restore() { CloudSync.transport = real; Alarms.acknowledge(AlertType.URGENT_LOW); Alarms.stopSound() }

    private fun follow(pid: String, role: FollowerRole = FollowerRole.ADMIN) {
        Repo.updateSettings { it.copy(mode = AppMode.FOLLOWER, followAccountCode = pid, followToken = "f".repeat(64), followRole = role) }
    }

    @Test fun ownerAndEachFollowedPatientHaveIndependentTimelinesCalibrationAndLifecycle() {
        val t = now - MINUTE_MS
        val start = Repo.settings.value.sensorStartMs
        Repo.db.insertReading(GlucoseReading(t, 100.0, "juggluco"))
        Repo.addStick(Fingerstick(t, 120.0))
        Repo.db.addDose(InsulinDose(t, 4.0))
        follow(pidA)
        assertTrue(Repo.readings(0).isEmpty())
        assertEquals(0.0, Repo.iob(), 0.0)
        assertEquals(0L, Repo.settings.value.sensorStartMs)
        Repo.db.insertReading(GlucoseReading(t, 90.0, "cloud"))
        assertEquals(90.0, Repo.readings(0).single().mgdl, 0.0)
        follow(pidB)
        assertTrue(Repo.readings(0).isEmpty())
        follow(pidA)
        assertEquals(90.0, Repo.readings(0).single().mgdl, 0.0)
        Repo.updateSettings { it.copy(mode = AppMode.PATIENT) }
        assertEquals(start, Repo.settings.value.sensorStartMs)
        assertEquals(120.0, Repo.readings(0).single().mgdl, 0.0)
        assertEquals(listOf(4.0), Repo.db.doses(0).map { it.units })
    }

    @Test fun responseFromPreviousAccountCannotWriteToTheNewOne() {
        follow(pidA)
        val old = Repo.settings.value
        CloudSync.transport = { _, url, _, _ ->
            if (url.contains("/readings")) {
                follow(pidB)
                200 to """{"readings":[[${now - MINUTE_MS},100]]}"""
            } else 200 to "{}"
        }
        CloudSync.pullAsFollower(old)
        assertEquals(pidB, Repo.settings.value.followAccountCode)
        assertTrue(Repo.db.readings(0).isEmpty())
    }

    @Test fun eventSurvivesLostAckAndProcessRestartWithoutDuplicateInsulin() {
        val t = now - MINUTE_MS
        val event = """{"events":[{"id":7,"type":"dose","value":4,"time":$t}]}"""
        CloudSync.transport = { method, _, _, _ -> if (method == "GET") 200 to event else null }
        CloudSync.pullAdminEvents(Repo.settings.value)
        assertEquals(listOf(4.0), Repo.db.doses(0).map { it.units })
        Repo.db.close()
        Repo.init(ctx)
        CloudSync.pullAdminEvents(Repo.settings.value)
        assertEquals(listOf(4.0), Repo.db.doses(0).map { it.units })
    }

    @Test fun partialUploadIsRetriedAndFailedStatusIsRetriedWithoutNewReading() {
        Repo.db.insertReading(GlucoseReading(now - MINUTE_MS, 120.0, "juggluco"))
        var uploads = 0
        var statuses = 0
        var reject = true
        CloudSync.transport = { method, url, _, body ->
            when {
                method == "POST" && url.endsWith("/readings") -> {
                    uploads++
                    val n = JSONObject(body!!).getJSONArray("readings").length()
                    200 to if (reject) """{"stored":0,"rejected":1}""" else """{"stored":$n,"rejected":0}"""
                }
                method == "PUT" && url.endsWith("/status") -> { statuses++; null }
                else -> 200 to "{}"
            }
        }
        val s = Repo.settings.value
        CloudSync.pushLatest(s)
        reject = false
        CloudSync.pushLatest(s)
        CloudSync.pushLatest(s)
        assertEquals(2, uploads)
        assertEquals(2, statuses)
    }

    @Test fun followerRejectsInvalidFramesAndPreservesExperimentalMetadata() {
        follow(pidA)
        val t = now - 5 * MINUTE_MS
        CloudSync.transport = { _, url, _, _ ->
            if (url.contains("/readings")) 200 to """{"readings":[
              {"t":$t,"mgdl":100,"source":"juggluco"},
              {"t":${t + MINUTE_MS},"mgdl":300,"source":"linx_ble"},
              {"t":${t + 2 * MINUTE_MS},"mgdl":200,"source":"juggluco","ageDays":16},
              {"t":${now + HOUR_MS},"mgdl":500},
              {"t":${t + 3 * MINUTE_MS},"mgdl":999},null]}""" else 200 to "{}"
        }
        CloudSync.pullAsFollower(Repo.settings.value)
        assertEquals(listOf(100.0), Repo.db.readings(0).map { it.mgdl })
        assertEquals(2, Repo.db.experimentalReadings(0).size)
        assertTrue(Repo.dosingReadings().isEmpty()) // follower never uses own therapy for another patient
    }

    @Test fun oldSensorBackfillAndDuplicateCannotResetCurrentSensorClock() {
        val t = now - 2 * MINUTE_MS
        Repo.ingest(GlucoseReading(t, 100.0, "juggluco", "A"))
        Repo.ingest(GlucoseReading(t + MINUTE_MS, 110.0, "juggluco", "B"))
        val start = Repo.settings.value.sensorStartMs
        Repo.ingest(GlucoseReading(t - HOUR_MS, 90.0, "juggluco", "OLDER"))
        Repo.ingest(GlucoseReading(t + 2 * MINUTE_MS, 120.0, "juggluco", "A"))
        assertEquals("B", Repo.settings.value.sensorId)
        assertEquals(start, Repo.settings.value.sensorStartMs)
        assertEquals(start, Repo.db.lastReadingTime())
    }

    @Test fun lateSensorDataInvalidatesAnEmptyCalibrationFit() {
        val t = now - MINUTE_MS
        Repo.addStick(Fingerstick(t, 120.0))
        assertEquals(Calibration.IDENTITY, Repo.calibration())
        Repo.ingest(GlucoseReading(t, 100.0, "juggluco"))
        assertEquals(120.0, Repo.latest()!!.mgdl, 0.0)
    }

    @Test fun pendingSensorRestartAndUnknownAgeBlockDosing() {
        Repo.db.insertReading(GlucoseReading(now - MINUTE_MS, 120.0, "juggluco"))
        assertFalse(Repo.dosingReadings().isEmpty())
        Repo.updateSettings { it.copy(clearSuspectMs = now) }
        assertTrue(Repo.dosingReadings().isEmpty())
        Repo.updateSettings { it.copy(clearSuspectMs = 0, sensorStartMs = 0) }
        assertTrue(Repo.dosingReadings().isEmpty())
    }

    @Test fun urgentSoundCannotBeAcknowledgedByOtherAlertOrOrdinaryAppOpening() {
        Alarms.show(ctx, Alert(AlertType.URGENT_LOW, true, "test", "synthetic"))
        SnoozeReceiver().onReceive(ctx, Intent().putExtra("type", AlertType.HIGH.name).putExtra("minutes", 15))
        assertEquals(AlertType.URGENT_LOW, Alarms.activeSoundType)
        Alarms.stopSound()
        assertEquals(AlertType.URGENT_LOW, Alarms.activeSoundType)
        SnoozeReceiver().onReceive(ctx, Intent().putExtra("type", AlertType.URGENT_LOW.name).putExtra("minutes", 15))
        assertEquals(AlertType.URGENT_LOW, Alarms.activeSoundType)
        SnoozeReceiver().onReceive(ctx, Intent().putExtra("type", AlertType.URGENT_LOW.name).putExtra("ack", true))
        assertNull(Alarms.activeSoundType)
    }

    @Test fun malformedEnumsAndNonFiniteSavedSettingsDoNotCrashOrDisableUrgentLow() {
        ctx.getSharedPreferences("settings", 0).edit().putString("mode", "corrupt").putString("followRole", "corrupt")
            .putFloat("icr", Float.NaN).putFloat("urgentLow", Float.NaN).commit()
        Repo.init(ctx)
        assertEquals(AppMode.PATIENT, Repo.settings.value.mode)
        assertEquals(FollowerRole.VIEWER, Repo.settings.value.followRole)
        assertEquals(55.0, Repo.settings.value.alerts.urgentLowMgdl, 0.0)
        assertTrue(Repo.settings.value.therapy.icr.isFinite())
    }

    @Test fun viewerCannotSendEventsEvenIfTransportWouldAllowIt() {
        follow(pidA, FollowerRole.VIEWER)
        CloudSync.transport = { _, _, _, _ -> fail("No network request expected"); 200 to "{}" }
        assertFalse(CloudSync.sendEvent(Repo.settings.value, "dose", 4.0))
        assertFalse(CloudSync.proposeThresholds(Repo.settings.value, 70.0, 180.0))
    }

    @Test fun noUnitGuessOrMissingMeasurementTimeIsAcceptedFromBroadcast() {
        val receiver = GlucoseBroadcastReceiver()
        receiver.onReceive(ctx, Intent(GlucoseBroadcastReceiver.JUGGLUCO).putExtra("glucodata.Minute.glucose", 30f)
            .putExtra("glucodata.Minute.Time", now))
        receiver.onReceive(ctx, Intent(GlucoseBroadcastReceiver.JUGGLUCO).putExtra("glucodata.Minute.mgdl", 120))
        assertTrue(Repo.db.readings(0).isEmpty())
    }
    @Test fun doseConfirmationRechecksCurrentDataInsideTheStorageBoundary() {
        val t = now - MINUTE_MS
        Repo.db.insertReading(GlucoseReading(t, 250.0, "juggluco"))
        val expected = Repo.settings.value
        // 60/10 + (250-110)/50 = 8.8, rounded down to 8.5.
        val shown = BolusAdvisor.suggest(60.0, 250.0, 0.0, expected.therapy)
        assertEquals(8.5, shown.totalUnits, 0.0)
        Repo.db.insertReading(GlucoseReading(now, 50.0, "juggluco"))
        assertEquals(Repo.BolusCommit.CHANGED, Repo.confirmBolus(expected, shown, 60.0))
        assertTrue(Repo.db.doses(0).isEmpty())
        assertTrue(Repo.db.carbs(0).isEmpty())
    }

    @Test fun staleManualEntryCannotWriteToANewProfile() {
        val expected = Repo.settings.value
        follow(pidA)
        assertFalse(Repo.logDose(expected, InsulinDose(now, 4.0)))
        assertFalse(Repo.logCarbs(expected, CarbEntry(now, 45.0)))
        assertTrue(Repo.db.doses(0).isEmpty())
        assertTrue(Repo.db.carbs(0).isEmpty())
    }

    @Test fun bolusDatabaseTransactionRollsBackMealWhenDoseInsertFails() {
        Repo.db.writableDatabase.execSQL("CREATE TRIGGER reject_dose BEFORE INSERT ON doses BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        try { Repo.db.logBolus(now, 60.0, 4.0); fail("Expected an insert failure") } catch (_: android.database.sqlite.SQLiteException) { }
        assertTrue(Repo.db.carbs(0).isEmpty())
        assertTrue(Repo.db.doses(0).isEmpty())
    }
    @Test fun extendedOrRestartedSensorCannotBecomeTheOnlyClinicalAlarmInput() {
        Repo.db.insertReading(GlucoseReading(now - MINUTE_MS, 50.0, "juggluco"))
        assertFalse(Repo.alarmReadings().isEmpty())
        Repo.updateSettings { it.copy(sensorStartMs = now - 16 * DAY_MS) }
        assertTrue(Repo.alarmReadings().isEmpty())
        Repo.updateSettings { it.copy(sensorStartMs = now - 2 * DAY_MS, clearSuspectMs = now) }
        assertTrue(Repo.alarmReadings().isEmpty())
    }
}
