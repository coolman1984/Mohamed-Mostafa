package com.mm.smartcgm

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.mm.smartcgm.core.CarbEntry
import com.mm.smartcgm.core.GlucoseReading
import com.mm.smartcgm.core.GlucoseUnit
import com.mm.smartcgm.core.InsulinDose
import com.mm.smartcgm.data.Repo
import com.mm.smartcgm.ui.AnalyticsScreen
import com.mm.smartcgm.ui.BolusDialog
import com.mm.smartcgm.ui.HomeScreen
import com.mm.smartcgm.ui.LogbookScreen
import com.mm.smartcgm.ui.SettingsScreen
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.sin

/** Renders the screens with realistic data and saves PNGs to app/build/screens (visual proof, no device needed). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h1400dp-xhdpi")
class ScreensTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val out = File("build/screens").apply { mkdirs() }
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before fun seed() {
        ctx.deleteDatabase("smartcgm.db")
        Repo.init(ctx)
        Repo.sourceSeen.clear()
        val now = System.currentTimeMillis()
        // 3 days of 5-minute readings: a daily curve, then a steady fall in the last 20 minutes.
        for (i in (3 * 288) downTo 0) {
            val t = now - i * 5 * 60_000L
            var g = 140 + 60 * sin(i / 30.0) + 25 * sin(i / 7.0)
            if (i <= 4) g = 120.0 - (4 - i) * 10.0 // 2 mg/dL/min → about 5 min to 70
            Repo.db.insertReading(GlucoseReading(t, g, "juggluco"))
        }
        Repo.sourceSeen["juggluco"] = now - 60_000
        Repo.db.addDose(InsulinDose(now - 90 * 60_000, 4.0))
        Repo.db.addCarbs(CarbEntry(now - 95 * 60_000, 45.0))
        Repo.updateSettings { it.copy(sensorStartMs = now - (16.2 * 86_400_000).toLong(), sensorId = "LX-3F2A", setupDone = true, unit = GlucoseUnit.MGDL) }
    }

    private fun shot(name: String, content: @Composable () -> Unit) {
        rule.setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF2E7D32), secondary = Color(0xFF00796B))) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(Modifier.fillMaxSize()) { content() }
                }
            }
        }
        rule.waitForIdle()
        // Draw the real view hierarchy (PixelCopy isn't available under Robolectric).
        val v = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun has(text: String) = rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun home() {
        // A clinical forecast is shown only while the sensor remains within its specified life.
        Repo.updateSettings { it.copy(sensorStartMs = System.currentTimeMillis() - 3 * 86_400_000L) }
        shot("01-home") { HomeScreen() }
        assertTrue(has("متوقع هبوط"))
        assertTrue(has("البيانات بتوصل كويس"))
        assertTrue(has("فاضل"))
    }

    @Test fun extendedSensorShowsNonActionableWarningInsteadOfClinicalForecast() {
        shot("01b-home-extended") { HomeScreen() }
        assertTrue(has("غير معتمدة للجرعات"))
        assertTrue(has("غيّر") || has("15"))
        assertFalse(has("متوقع هبوط"))
    }

    @Test fun doseDialogRefusesExtendedSensorReadings() {
        shot("01c-dose-blocked") { BolusDialog {} }
        assertTrue(has("حساب الجرعة متوقف"))
        assertFalse(has("المقترح:"))
    }

    @Test fun homeMmol() {
        Repo.updateSettings { it.copy(unit = GlucoseUnit.MMOLL) }
        shot("02-home-mmol") { HomeScreen() }
        assertTrue(has("mmol/L"))
    }

    @Test fun firstLaunchWizardAndNoData() {
        ctx.deleteDatabase("smartcgm.db"); Repo.init(ctx)
        Repo.sourceSeen.clear()
        Repo.updateSettings { it.copy(setupDone = false) }
        shot("03-first-launch-wizard") { HomeScreen() }
        assertTrue(has("خطوات التشغيل"))
        assertTrue(has("مفيش قراءات وصلت"))
    }

    @Test fun logbook() {
        shot("04-logbook") { LogbookScreen() }
        assertTrue(has("الأحداث"))
        assertTrue(has("💉 4 وحدة"))
    }
    @Test fun logbookMmol() {
        Repo.updateSettings { it.copy(unit = GlucoseUnit.MMOLL) }
        shot("04b-logbook-mmol") { LogbookScreen() }
    }
    @Test fun analytics() {
        shot("05-reports") { AnalyticsScreen() }
        assertTrue(has("البيانات المتاحة"))
        assertTrue(has("الأيام فوق بعض"))
        assertTrue(has("تقرير AGP"))
    }
    @Test fun homeWithNewSensorPromptAndProposal() {
        Repo.updateSettings { it.copy(newSensorSuspectMs = System.currentTimeMillis() - 60_000,
            sensorStartMs = System.currentTimeMillis() - (12.4 * 86_400_000).toLong()) }
        Repo.receiveProposal(com.mm.smartcgm.core.ThresholdProposal(80.0, 200.0, System.currentTimeMillis()))
        shot("08-home-new-sensor-and-proposal") { HomeScreen() }
        assertTrue(has("ركّبت حساس جديد"))
        assertTrue(has("بيقترح تغيير حدود"))
        assertTrue(has("يوم"))
    }

    @Test fun settingsCalibration() {
        val n = System.currentTimeMillis()
        Repo.addStick(com.mm.smartcgm.core.Fingerstick(n - 30 * 60_000, 128.0))
        Repo.addStick(com.mm.smartcgm.core.Fingerstick(n - 26 * 3_600_000L, 96.0))
        shot("09-settings-calibration") { SettingsScreen() }
        assertTrue(has("المعايرة بوخز الإصبع"))
        assertTrue(has("إلغاء المعايرة"))
    }

    @Test fun followerAdminCanProposeThresholds() {
        Repo.updateSettings { it.copy(mode = com.mm.smartcgm.data.AppMode.FOLLOWER, followAccountCode = "ABCDEFGHJKMNPQRS",
            followRole = com.mm.smartcgm.core.FollowerRole.ADMIN) }
        shot("11-follower-admin-propose") { SettingsScreen() }
        assertTrue(has("اقترح حدود تنبيه"))
    }

    @Test fun settings() {
        shot("06-settings") { SettingsScreen() }
        assertTrue(has("وحدة القياس"))
    }
}
