package com.mm.smartcgm.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Regression tests for the code-review fixes. Numbers are worked out by hand in the comments. */
class ReviewFixesTest {
    private val s = TherapySettings(icr = 10.0, isf = 50.0, targetMgdl = 100.0, maxBolusUnits = 10.0)

    @Test fun bolusRefusesCorruptSettingsInsteadOfInfiniteDose() {
        // ICR 0 → 60/0 = Infinity → used to be "capped" to max bolus. Must be 0 and blocked.
        for (bad in listOf(s.copy(icr = 0.0), s.copy(isf = 0.0), s.copy(icr = -5.0), s.copy(maxBolusUnits = 0.0), s.copy(icr = Double.NaN))) {
            val r = BolusAdvisor.suggest(60.0, 150.0, 0.0, bad)
            assertEquals(0.0, r.totalUnits, "settings=$bad")
            assertTrue(r.blockedForLow)
        }
        assertEquals(0.0, BolusAdvisor.suggest(Double.NaN, 150.0, 0.0, s).totalUnits)
        assertEquals(0.0, BolusAdvisor.suggest(60.0, Double.NaN, 0.0, s).totalUnits)
        // Normal path unchanged: 60 g / ICR 10 = 6 u, (150-100)/50 = 1 u → 7 u.
        assertEquals(7.0, BolusAdvisor.suggest(60.0, 150.0, 0.0, s).totalUnits)
    }

    @Test fun safeLimitsClampTherapyTypos() {
        val t = SafeLimits.therapy(TherapySettings(icr = 0.1, isf = 1.0, targetMgdl = 20.0, maxBolusUnits = 500.0,
            diaHours = 0.5, carbAbsorptionHours = 30.0, lowMgdl = 10.0, highMgdl = 60.0))
        assertEquals(1.0, t.icr); assertEquals(5.0, t.isf); assertEquals(70.0, t.targetMgdl)
        assertEquals(50.0, t.maxBolusUnits); assertEquals(2.0, t.diaHours); assertEquals(8.0, t.carbAbsorptionHours)
        assertEquals(54.0, t.lowMgdl)
        // high 60 → clamped to 120; still ≥ low + 30 = 84.
        assertEquals(120.0, t.highMgdl)
        // Sane values pass through untouched.
        val ok = TherapySettings()
        assertEquals(ok, SafeLimits.therapy(ok))
    }

    @Test fun urgentLowCanNeverBeSwitchedOffOrLowered() {
        val a = SafeLimits.alerts(AlertConfig(urgentLowMgdl = 1.0, low = AlertRule(true, 40.0, 0), high = AlertRule(true, 900.0, 999)))
        assertEquals(55.0, a.urgentLowMgdl)
        // Low alert must sit above urgent low: 40 → 55 (range) → at least 56.
        assertEquals(56.0, a.low.thresholdMgdl)
        assertEquals(1, a.low.repeatMinutes)
        assertEquals(400.0, a.high.thresholdMgdl)
        assertEquals(240, a.high.repeatMinutes)
        // The urgent alarm still fires at 50 with the clamped config.
        val (fired, _) = AlertEngine.evaluate(GlucoseReading(1000L, 50.0), null, null, a, AlertState(), 1000L)
        assertEquals(AlertType.URGENT_LOW, fired.first().type)
        // Default config is unchanged.
        assertEquals(AlertConfig(), SafeLimits.alerts(AlertConfig()))
    }

    @Test fun doseAndCarbValidation() {
        assertTrue(SafeLimits.validDose(4.0)); assertTrue(SafeLimits.validDose(50.0))
        assertFalse(SafeLimits.validDose(0.0)); assertFalse(SafeLimits.validDose(-3.0))
        assertFalse(SafeLimits.validDose(51.0)); assertFalse(SafeLimits.validDose(Double.NaN))
        assertTrue(SafeLimits.validCarbs(45.0)); assertFalse(SafeLimits.validCarbs(-10.0)); assertFalse(SafeLimits.validCarbs(301.0))
    }

    @Test fun negativeDoseWouldHaveRaisedTheSuggestion() {
        // Why validDose matters: a logged -4 u makes IOB negative-ish and the next suggestion bigger.
        val t0 = 1_000_000L
        val iobWithNegative = InsulinMath.iob(listOf(InsulinDose(t0, 4.0), InsulinDose(t0, -4.0)), t0 + 60 * MINUTE_MS, 4.0)
        val iobClean = InsulinMath.iob(listOf(InsulinDose(t0, 4.0)), t0 + 60 * MINUTE_MS, 4.0)
        assertEquals(iobClean, iobWithNegative) // legacy invalid records are now excluded at the math boundary
    }

    @Test fun frozenNotificationStopsCountingAsLive() {
        val g = FrozenNotificationGuard(maxSameMs = 20 * MINUTE_MS)
        val t0 = 0L
        assertTrue(g.accept("linx", "120 mg/dL →", t0))
        assertTrue(g.accept("linx", "120 mg/dL →", t0 + 10 * MINUTE_MS)) // flat glucose, still fine
        assertFalse(g.accept("linx", "120 mg/dL →", t0 + 21 * MINUTE_MS)) // frozen for > 20 min
        assertTrue(g.accept("linx", "118 mg/dL ↘", t0 + 22 * MINUTE_MS)) // changed → live again
        assertFalse(g.accept("linx", "Signal lost • 118 mg/dL", t0 + 23 * MINUTE_MS))
        assertTrue(g.accept("linx", "121 mg/dL · updated 1 min ago", t0 + 24 * MINUTE_MS)) // "ago" alone is normal
        // Per package: another app's text doesn't reset this one.
        assertTrue(g.accept("libre", "120 mg/dL →", t0 + 30 * MINUTE_MS))
    }

    @Test fun versionNumbersAreNotGlucose() {
        assertNull(NotificationParser.parse("LinX 2.6.1 running"))
        assertNull(NotificationParser.parse("build 12.34.5"))
        // Real values still parse: 6.5 mmol/L × 18.0182 = 117.1 mg/dL.
        assertEquals(6.5 * 18.0182, NotificationParser.parse("6.5 mmol/L")!!, 1e-6)
        assertEquals(6.5 * 18.0182, NotificationParser.parse("6,5 ↗")!!, 1e-6)
        assertEquals(98.0, NotificationParser.parse("98 mg/dL"))
    }
}
