package com.mm.smartcgm

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.mm.smartcgm.core.Fingerstick
import com.mm.smartcgm.core.GlucoseReading
import com.mm.smartcgm.data.AppMode
import com.mm.smartcgm.data.Repo
import com.mm.smartcgm.data.SettingsStore
import com.mm.smartcgm.sources.GlucoseBroadcastReceiver
import com.mm.smartcgm.sources.JugglucoWebSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IngestTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val now = System.currentTimeMillis()

    @Before fun fresh() {
        ctx.databaseList().forEach { ctx.deleteDatabase(it) }
        ctx.getSharedPreferences("settings", 0).edit().clear().commit()
        Repo.init(ctx)
        Repo.updateSettings { it.copy(backendUrl = "") }
    }

    /** Broadcast ingest runs on the pipeline coroutine scope; wait (max 10 s) for it to land. */
    private fun awaitReadings(n: Int) {
        val end = System.currentTimeMillis() + 10_000
        while (Repo.db.readings(0).size < n && System.currentTimeMillis() < end) Thread.sleep(50)
    }

    @Test fun ingestDedupesWithin30Seconds() {
        Repo.ingest(GlucoseReading(now - 120_000, 120.0, "juggluco"))
        Repo.ingest(GlucoseReading(now - 100_000, 121.0, "app:com.microtech.aidexx.mgdl")) // 20 s later → duplicate
        Repo.ingest(GlucoseReading(now - 60_000, 125.0, "juggluco"))                      // 60 s later → new
        assertEquals(2, Repo.db.readings(0).size)
    }

    @Test fun ingestRejectsImpossibleValuesAndFuture() {
        Repo.ingest(GlucoseReading(now, 10.0, "x"))
        Repo.ingest(GlucoseReading(now, 700.0, "x"))
        Repo.ingest(GlucoseReading(now + 10 * 60_000, 100.0, "x"))
        assertTrue(Repo.db.readings(0).isEmpty())
    }

    @Test fun newSensorSerialRestartsSensorClock() {
        Repo.ingest(GlucoseReading(now - 60_000, 110.0, "juggluco", "SER-A"))
        assertEquals("SER-A", Repo.settings.value.sensorId)
        assertEquals(now - 60_000, Repo.settings.value.sensorStartMs)
        Repo.ingest(GlucoseReading(now, 112.0, "juggluco", "SER-B"))
        assertEquals("SER-B", Repo.settings.value.sensorId)
        assertEquals(now, Repo.settings.value.sensorStartMs)
    }

    @Test fun jugglucoBroadcastUsesVerifiedExtras() {
        val t = now - 30_000
        GlucoseBroadcastReceiver().onReceive(ctx, Intent("glucodata.Minute")
            .putExtra("glucodata.Minute.SerialNumber", "3MH00ABC")
            .putExtra("glucodata.Minute.mgdl", 143)
            .putExtra("glucodata.Minute.glucose", 7.9f)
            .putExtra("glucodata.Minute.Rate", -1.2f)
            .putExtra("glucodata.Minute.Alarm", 0)
            .putExtra("glucodata.Minute.Time", t))
        awaitReadings(1)
        val r = Repo.db.readings(0).single()
        assertEquals(t, r.timeMs); assertEquals(143.0, r.mgdl, 0.0); assertEquals("3MH00ABC", r.sensorId)
        assertEquals("juggluco", r.source)
    }

    @Test fun xdripBroadcastUsesVerifiedExtras() {
        val t = now - 30_000
        GlucoseBroadcastReceiver().onReceive(ctx, Intent("com.eveningoutpost.dexdrip.BgEstimate")
            .putExtra("com.eveningoutpost.dexdrip.Extras.BgEstimate", 98.0)
            .putExtra("com.eveningoutpost.dexdrip.Extras.Time", t))
        awaitReadings(1)
        val r = Repo.db.readings(0).single()
        assertEquals(98.0, r.mgdl, 0.0); assertEquals(t, r.timeMs)
    }

    @Test fun followerModeIgnoresLocalSources() {
        Repo.updateSettings { it.copy(mode = AppMode.FOLLOWER, followAccountCode = "ABCDEFGHJKLMNPQR") }
        GlucoseBroadcastReceiver().onReceive(ctx, Intent("glucodata.Minute").putExtra("glucodata.Minute.mgdl", 150)
            .putExtra("glucodata.Minute.Time", now))
        Thread.sleep(300)
        assertTrue(Repo.db.readings(0).isEmpty())
        Repo.updateSettings { it.copy(mode = AppMode.PATIENT) }
    }

    @Test fun calibrationCacheIsInvalidatedByFingerstick() {
        Repo.ingest(GlucoseReading(now - 120_000, 100.0, "juggluco"))
        assertEquals(100.0, Repo.latest()!!.mgdl, 1e-6)
        Repo.addStick(Fingerstick(now - 120_000, 120.0)) // one stick → offset +20
        assertEquals(120.0, Repo.latest()!!.mgdl, 1e-6)
    }

    @Test fun ownCodeSurvivesFollowingAndComingBack() {
        val own = Repo.settings.value.accountCode
        Repo.updateSettings { it.copy(mode = AppMode.FOLLOWER, followAccountCode = "ZZZZZZZZZZZZZZZZ") }
        assertEquals("ZZZZZZZZZZZZZZZZ", Repo.settings.value.activeAccountCode)
        Repo.updateSettings { it.copy(mode = AppMode.PATIENT) }
        assertEquals(own, Repo.settings.value.accountCode)
        assertEquals(own, SettingsStore(ctx).load().accountCode)
    }

    @Test fun oldFollowerInstallIsMigrated() {
        val p = ctx.getSharedPreferences("settings", 0)
        p.edit().clear().putString("mode", "FOLLOWER").putString("accountCode", "OLDFOLLOWEDCODE1").commit()
        val s = SettingsStore(ctx).load()
        assertEquals("OLDFOLLOWEDCODE1", s.followAccountCode)
        assertNotEquals("OLDFOLLOWEDCODE1", s.accountCode)
        assertEquals(16, s.accountCode.length)
    }

    @Test fun jugglucoSecretHashIsSha1Hex() {
        // sha1("abc") is the classic FIPS-180 test vector.
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", JugglucoWebSource.sha1Hex("abc"))
        assertFalse(JugglucoWebSource.sha1Hex("x").contains(" "))
    }
}
