package com.mm.smartcgm

import androidx.test.core.app.ApplicationProvider
import com.mm.smartcgm.cloud.CloudSync
import com.mm.smartcgm.core.DAY_MS
import com.mm.smartcgm.core.FollowerRole
import com.mm.smartcgm.core.GlucoseReading
import com.mm.smartcgm.data.AppMode
import com.mm.smartcgm.data.AppSettings
import com.mm.smartcgm.data.Repo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Cloudflare backend client (fake transport) + Juggluco-Clear detection. */
@RunWith(RobolectricTestRunner::class)
class CloudSyncTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val calls = mutableListOf<Triple<String, String, String?>>()
    private val real = CloudSync.transport

    @Before fun fresh() {
        ctx.deleteDatabase("smartcgm.db")
        ctx.getSharedPreferences("settings", 0).edit().clear().commit()
        Repo.init(ctx)
        calls.clear()
    }
    @After fun restore() { CloudSync.transport = real }

    @Test fun defaultsPointAtWorkerAndEachPhoneHasOwnToken() {
        val s = Repo.settings.value
        assertEquals(AppSettings.DEFAULT_BACKEND_URL, s.backendUrl)
        assertTrue(s.backendUrl.startsWith("https://") && "firebase" !in s.backendUrl)
        assertEquals(64, s.ownerToken.length)
        assertTrue(CloudSync.enabled(s))
    }

    @Test fun patientRegistersThenUploadsWithBearerToken() {
        CloudSync.transport = { m, u, t, b -> calls += Triple("$m $u", t ?: "", b); 200 to "{}" }
        val now = System.currentTimeMillis()
        Repo.ingest(GlucoseReading(now - 60_000, 101.0, "juggluco"))
        val s = Repo.settings.value
        Thread.sleep(300) // let the pipeline's own background push settle, then push explicitly from a clean cursor
        Repo.store.putLong("cloudLastPushedMs", 0)
        CloudSync.pushLatest(s)
        val reg = calls.first { it.first.endsWith("/register") && it.third!!.contains(s.ownerToken) }
        assertTrue(reg.first.endsWith("/v1/p/${s.accountCode}/register"))
        assertTrue(reg.third!!.contains(s.ownerToken))
        // Background pushes from the pipeline may interleave; the upload must carry THIS phone's token.
        assertTrue(calls.any { it.first.startsWith("POST") && it.first.endsWith("/readings") &&
            it.second == s.ownerToken && it.third!!.contains("[${now - 60_000},101]") })
    }

    @Test fun followerEventIsImportedOnlyAfterTheServerDeletedIt() {
        val now = System.currentTimeMillis()
        val events = """{"events":[{"id":1,"type":"dose","value":4,"time":${now - 60_000}},""" +
            """{"id":2,"type":"dose","value":-4,"time":$now},{"id":3,"type":"carbs","value":900,"time":$now}]}"""
        // 1st minute: the DELETE fails (offline/500) → nothing may be imported, or it would be imported again next minute.
        CloudSync.transport = { m, u, _, _ -> if (m == "GET" && u.endsWith("/events")) 200 to events else if (m == "DELETE") 500 to "" else 200 to "{}" }
        CloudSync.pullAdminEvents(Repo.settings.value)
        assertEquals(0, Repo.db.doses(0).size)
        // 2nd minute: delete works → the valid 4 u dose is imported once; -4 u and 900 g are rejected.
        CloudSync.transport = { m, u, _, _ -> if (m == "GET" && u.endsWith("/events")) 200 to events else 200 to "{}" }
        CloudSync.pullAdminEvents(Repo.settings.value)
        assertEquals(listOf(4.0), Repo.db.doses(0).map { it.units })
        assertEquals(0, Repo.db.carbs(0).size)
    }

    @Test fun redeemSwitchesToFollowerAndKeepsOwnAccount() {
        val own = Repo.settings.value.accountCode
        CloudSync.transport = { _, u, _, _ ->
            if (u.endsWith("/v1/invites/redeem")) 200 to """{"patient":"ZZZZZZZZZZZZZZZZ","role":"ADMIN","token":"${"f".repeat(64)}"}""" else 404 to "" }
        assertTrue(CloudSync.redeemInvite(Repo.settings.value, "ABCDEFGH23"))
        val s = Repo.settings.value
        assertEquals(AppMode.FOLLOWER, s.mode); assertEquals(FollowerRole.ADMIN, s.followRole)
        assertEquals("ZZZZZZZZZZZZZZZZ", s.activeAccountCode); assertEquals("f".repeat(64), s.activeToken)
        assertEquals(own, s.accountCode)
    }

    @Test fun badInviteOrOfflineFails() {
        CloudSync.transport = { _, _, _, _ -> 404 to """{"error":"x"}""" }
        assertFalse(CloudSync.redeemInvite(Repo.settings.value, "ABCDEFGH23"))
        CloudSync.transport = { _, _, _, _ -> null }
        assertNull(CloudSync.createInvite(Repo.settings.value, FollowerRole.VIEWER))
        assertEquals(AppMode.PATIENT, Repo.settings.value.mode)
    }

    @Test fun followerPullsReadingsIncrementally() {
        val now = System.currentTimeMillis()
        Repo.updateSettings { it.copy(mode = AppMode.FOLLOWER, followAccountCode = "ZZZZZZZZZZZZZZZZ", followToken = "f".repeat(64)) }
        CloudSync.transport = { m, u, _, _ -> calls += Triple("$m $u", "", null)
            if (u.contains("/readings")) 200 to """{"readings":[[${now - 120_000},90],[${now - 60_000},95]]}""" else 200 to """{"status":null}""" }
        CloudSync.pullAsFollower(Repo.settings.value)
        assertEquals(2, Repo.db.readings(0).size)
        CloudSync.pullAsFollower(Repo.settings.value)
        assertTrue(calls.last { it.first.contains("/readings") }.first.endsWith("after=${now - 60_000}"))
    }

    @Test fun sameSerialRestartAfterDay15FlagsClearAndKeepsClock() {
        val now = System.currentTimeMillis()
        val start = now - 15 * DAY_MS - 3_600_000
        Repo.ingest(GlucoseReading(start, 110.0, "juggluco", "LX-1"))
        assertEquals(start, Repo.settings.value.sensorStartMs)
        Repo.ingest(GlucoseReading(now - 30 * 60_000, 100.0, "juggluco", "LX-1"))
        Repo.ingest(GlucoseReading(now, 60.0, "juggluco", "LX-1")) // after a 30-min pause
        assertTrue(Repo.settings.value.clearSuspectMs > 0)
        assertEquals(start, Repo.settings.value.sensorStartMs)
        Repo.dismissClearWarning()
        assertEquals(0L, Repo.settings.value.clearSuspectMs)
    }
}
