package com.mm.smartcgm

import android.app.NotificationManager
import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.mm.smartcgm.core.Alert
import com.mm.smartcgm.core.AlertType
import com.mm.smartcgm.core.Calibration
import com.mm.smartcgm.core.CarbEntry
import com.mm.smartcgm.core.DAY_MS
import com.mm.smartcgm.core.Fingerstick
import com.mm.smartcgm.core.GlucoseReading
import com.mm.smartcgm.core.HOUR_MS
import com.mm.smartcgm.core.InsulinDose
import com.mm.smartcgm.core.Logbook
import com.mm.smartcgm.core.Stats
import com.mm.smartcgm.core.ThresholdProposal
import com.mm.smartcgm.data.Repo
import com.mm.smartcgm.engine.Alarms
import com.mm.smartcgm.export.Exporter
import com.mm.smartcgm.ui.AlarmActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** P1-2 alarm screen and the P2 features, end to end through Repo / DB / UI. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h900dp-xhdpi")
class CareFeaturesTest {
    @get:Rule val rule = createAndroidComposeRule<AlarmActivity>()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val now = System.currentTimeMillis()

    @Before fun fresh() {
        ctx.databaseList().forEach { ctx.deleteDatabase(it) }
        ctx.getSharedPreferences("settings", 0).edit().clear().commit()
        Repo.init(ctx)
        Repo.updateSettings { it.copy(backendUrl = "") }
        AlarmActivity.acknowledged = false
    }

    // ---- P1-2 ----

    @Test fun urgentLowNotificationOpensAlarmScreen() {
        Alarms.createChannels(ctx)
        Alarms.show(ctx, Alert(AlertType.URGENT_LOW, true, "🚨 هبوط خطير", "السكر 50"))
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val n = shadowOf(nm).allNotifications.single()
        val target = shadowOf(n.fullScreenIntent).savedIntent
        assertEquals(AlarmActivity::class.java.name, target.component!!.className)
        assertEquals("السكر 50", target.getStringExtra(AlarmActivity.EXTRA_BODY))
        // No snooze action on urgent low — only "I took sugar".
        assertEquals(listOf("✅ أخدت سكر"), n.actions.map { it.title.toString() })
        Alarms.stopSound()
    }

    @Test fun alarmScreenAckStopsAndCloses() {
        rule.waitForIdle()
        val v = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(bmp))
        File("build/screens").apply { mkdirs() }.let { File(it, "07-urgent-low-alarm.png") }
            .outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // Back must NOT dismiss it.
        rule.activity.onBackPressedDispatcher.onBackPressed()
        assertFalse(rule.activity.isFinishing)
        rule.onNodeWithText("✅ أخدت سكر").performClick()
        rule.waitForIdle()
        assertTrue(AlarmActivity.acknowledged)
        assertTrue(rule.activity.isFinishing)
    }

    // ---- P2-1 ----

    @Test fun deleteStickAndUndoCalibration() {
        Repo.db.insertReading(GlucoseReading(now - 20 * 60_000, 100.0, "juggluco"))
        Repo.db.insertReading(GlucoseReading(now - 5 * 60_000, 150.0, "juggluco"))
        Repo.addStick(Fingerstick(now - 20 * 60_000, 110.0))
        Repo.addStick(Fingerstick(now - 5 * 60_000, 160.0))
        assertTrue(Repo.calibration(now) != Calibration.IDENTITY)
        Repo.deleteStick(now - 5 * 60_000)
        assertEquals(Calibration(1.0, 10.0), Repo.calibration(now)) // one stick left: offset +10
        Repo.undoCalibration(now)
        assertEquals(Calibration.IDENTITY, Repo.calibration(now))
        assertTrue(Repo.db.sticks(0).isEmpty())
    }

    // ---- P2-2 ----

    @Test fun logbookShowsEvents() {
        val hour = now - now % HOUR_MS
        Repo.db.insertReading(GlucoseReading(hour, 130.0, "juggluco"))
        Repo.db.addDose(InsulinDose(hour + 5 * 60_000, 3.5))
        Repo.db.addCarbs(CarbEntry(hour - 10 * 60_000, 40.0))
        val rows = Logbook.hourly(Repo.readings(hour - HOUR_MS), hour, hour, events = Repo.logEvents(hour - HOUR_MS))
        assertEquals("🍞 40 جم • 💉 3.5 وحدة", rows.single().events)
        val f = Exporter.csv(ctx, rows)
        assertTrue(f.readText().contains("🍞 40 جم • 💉 3.5 وحدة"))
    }

    // ---- P2-4 ----

    @Test fun agpDoctorPdfWithChart() {
        for (i in 0 until 14 * 288) {
            Repo.db.insertReading(GlucoseReading(now - i * 5 * 60_000L, 140 + 50 * Math.sin(i / 20.0), "juggluco"))
        }
        val rs = Repo.readings(now - 14 * DAY_MS)
        val st = Stats.compute(rs)!!
        val cov = Stats.coveragePercent(rs, now - 14 * DAY_MS, now)
        assertTrue("coverage $cov", cov > 99)
        val agp = Stats.agp(rs)
        val bmp = Exporter.agpBitmap(agp, 70.0, 180.0)
        File("build/screens").apply { mkdirs() }.let { File(it, "10-agp-pdf-chart.png") }
            .outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // The chart really has the blue median line in it (not a blank image).
        var blue = 0
        for (x in 0 until bmp.width step 4) for (y in 0 until bmp.height step 4) if (bmp.getPixel(x, y) == android.graphics.Color.rgb(13, 71, 161)) blue++
        assertTrue("median pixels $blue", blue > 20)
        // The PDF page itself (Robolectric has no PdfDocument, so draw the same page onto an A4-sized bitmap at 2×).
        val page = Bitmap.createBitmap(1190, 1684, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(page).apply { drawColor(android.graphics.Color.WHITE); scale(2f, 2f) }
        Exporter.drawAgpReport(c, st, agp, cov, 14, 70.0, 180.0)
        File("build/screens", "10b-agp-pdf-page.png").outputStream().use { page.compress(Bitmap.CompressFormat.PNG, 100, it) }
        var ink = 0
        for (x in 0 until page.width step 6) for (y in 0 until 200 step 6) if (page.getPixel(x, y) != android.graphics.Color.WHITE) ink++
        assertTrue("header text drawn", ink > 20)
    }

    // ---- P2-5 ----

    @Test fun gapSuggestsNewSensorButAsksFirst() {
        val start = now - 5 * DAY_MS
        Repo.updateSettings { it.copy(sensorStartMs = start) }
        Repo.ingest(GlucoseReading(now - 3 * HOUR_MS, 120.0, "app:com.microtech.aidexx"))
        assertEquals(0L, Repo.settings.value.newSensorSuspectMs)
        Repo.ingest(GlucoseReading(now - 60_000, 118.0, "app:com.microtech.aidexx"))
        assertEquals(now - 60_000, Repo.settings.value.newSensorSuspectMs)
        assertEquals(start, Repo.settings.value.sensorStartMs) // clock NOT restarted silently
        Repo.answerNewSensor(false)
        assertEquals(start, Repo.settings.value.sensorStartMs)
        assertEquals(0L, Repo.settings.value.newSensorSuspectMs)
        // Ask again → yes restarts from the first reading after the gap.
        Repo.updateSettings { it.copy(newSensorSuspectMs = now - 60_000) }
        Repo.answerNewSensor(true)
        assertEquals(now - 60_000, Repo.settings.value.sensorStartMs)
    }

    // ---- P2-6 ----

    @Test fun thresholdProposalNeedsPatientApproval() {
        val before = Repo.settings.value.alerts
        assertFalse(Repo.receiveProposal(ThresholdProposal(40.0, 180.0, now))) // unsafe → dropped
        assertNull(Repo.pendingProposal())
        assertTrue(Repo.receiveProposal(ThresholdProposal(80.0, 200.0, now)))
        assertEquals(before, Repo.settings.value.alerts) // nothing changed yet
        Repo.decideProposal(false)
        assertNull(Repo.pendingProposal())
        assertEquals(before, Repo.settings.value.alerts)
        Repo.receiveProposal(ThresholdProposal(80.0, 200.0, now))
        Repo.decideProposal(true)
        val a = Repo.settings.value.alerts
        assertEquals(80.0, a.low.thresholdMgdl, 0.0)
        assertEquals(200.0, a.high.thresholdMgdl, 0.0)
        assertEquals(before.urgentLowMgdl, a.urgentLowMgdl, 0.0) // urgent low untouched
        // Survives a restart (stored settings).
        Repo.init(ctx)
        assertEquals(80.0, Repo.settings.value.alerts.low.thresholdMgdl, 0.0)
    }
}
