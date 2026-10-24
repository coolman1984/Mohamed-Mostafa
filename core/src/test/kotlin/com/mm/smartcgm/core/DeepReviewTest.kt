package com.mm.smartcgm.core

import kotlin.test.*

class DeepReviewTest {
    @Test fun corruptFiniteSettingsAndUrgentLimitAreContained() {
        val t = SafeLimits.therapy(TherapySettings(icr = Double.NaN, isf = Double.POSITIVE_INFINITY,
            diaHours = Double.NaN, carbAbsorptionHours = Double.NaN, maxBolusUnits = Double.NaN))
        assertEquals(TherapySettings(), t)
        val a = SafeLimits.alerts(AlertConfig(urgentLowMgdl = Double.NaN, low = AlertRule(true, Double.NaN, 0),
            high = AlertRule(true, Double.NaN, 0)))
        assertEquals(55.0, a.urgentLowMgdl)
        assertEquals(70.0, a.low.thresholdMgdl)
        assertTrue(a.high.thresholdMgdl.isFinite())
        assertEquals(55.0, SafeLimits.alerts(AlertConfig(urgentLowMgdl = 65.0)).urgentLowMgdl)
        val gap = SafeLimits.alerts(AlertConfig(low = AlertRule(true, 120.0, 5), high = AlertRule(true, 120.0, 5)))
        assertEquals(150.0, gap.high.thresholdMgdl) // 120 + required 30 gap
    }

    @Test fun invalidInputsAndPredictionWithoutCurrentCannotProduceDose() {
        val t = TherapySettings(icr = 10.0, isf = 50.0, targetMgdl = 100.0)
        for (iob in listOf(-2.0, Double.NaN, Double.POSITIVE_INFINITY))
            assertEquals(0.0, BolusAdvisor.suggest(60.0, 150.0, iob, t).totalUnits)
        assertEquals(0.0, BolusAdvisor.suggest(60.0, null, 0.0, t, predictedLow = true).totalUnits)
        assertEquals(0.0, BolusAdvisor.suggest(60.0, 54.5, 0.0, t.copy(lowMgdl = 54.0)).totalUnits)
        // Normal input remains 60/10 + (150-100)/50 = 7 units.
        assertEquals(7.0, BolusAdvisor.suggest(60.0, 150.0, 0.0, t).totalUnits)
        for (bad in listOf(t.copy(icr = Double.POSITIVE_INFINITY), t.copy(targetMgdl = Double.NaN), t.copy(maxBolusUnits = Double.POSITIVE_INFINITY)))
            assertEquals(0.0, BolusAdvisor.suggest(60.0, 150.0, 0.0, bad).totalUnits)
    }

    @Test fun localizedNumbersAndInvalidValues() {
        assertEquals(6.5, UserNumber.parse(" ٦٫٥ "))
        assertEquals(123.0, UserNumber.parse("۱۲۳"))
        assertEquals(6.5, UserNumber.parse("6,5"))
        for (v in listOf("NaN", "Infinity", "١٢٫٣٫٤", "abc")) assertNull(UserNumber.parse(v))
    }

    @Test fun notificationUnitsBindToValueRatherThanBatteryOrOtherUnits() {
        assertEquals(110.0, NotificationParser.parse("Battery 80% • 110 mg/dL"))
        assertEquals(30.0, NotificationParser.parse("30 mg/dL")) // urgent lows must not be dropped
        assertEquals(600.0, NotificationParser.parse("600 mg/dL"))
        assertNull(NotificationParser.parse("Battery 80%"))
        assertNull(NotificationParser.parse("0.0 mmol/L • battery 80%"))
        assertNull(NotificationParser.parse("110 mg/dL • 150 mg/dL"))
    }

    @Test fun changingAgeDoesNotRefreshFrozenSampleAndReconnectResetsGuard() {
        val g = FrozenNotificationGuard()
        assertTrue(g.accept("linx", "120 mg/dL • updated 1 min ago", 1_000L))
        assertFalse(g.accept("linx", "120 mg/dL • updated 12 min ago", 2_000L))
        assertTrue(g.accept("linx", "121 mg/dL • updated 1 min ago", 3_000L))
        assertFalse(g.accept("linx", "121 mg/dL • updated 1 min ago", 2_000L)) // clock rollback
        assertFalse(g.accept("linx", "signal lost", 4_000L))
        assertTrue(g.accept("linx", "121 mg/dL • updated 1 min ago", 5_000L))
    }
    @Test fun clockRollbackCannotSilenceUrgentOrLowAlertsForHours() {
        val previous = 100 * MINUTE_MS
        val now = 10 * MINUTE_MS
        val state = AlertState(lastFired = mapOf(AlertType.LOW to previous), snoozedUntil = mapOf(AlertType.LOW to previous + 15 * MINUTE_MS))
        val low = AlertEngine.evaluate(GlucoseReading(now, 60.0), null, null, AlertConfig(), state, now).first
        assertTrue(low.any { it.type == AlertType.LOW })
    }

    @Test fun partialCoverageSlotNeverProducesMoreThanOneHundredPercent() {
        val r = listOf(GlucoseReading(1, 100.0), GlucoseReading(6 * MINUTE_MS, 100.0))
        assertEquals(100.0, Stats.coveragePercent(r, 0, 9 * MINUTE_MS)) // two occupied slots / two possible
        assertEquals(0.0, Stats.coveragePercent(r, 0, MINUTE_MS, slotMs = 0))
    }

    @Test fun corruptLegacyDoseAndCalibrationRecordsCannotPoisonValidValues() {
        val now = 1_000_000L
        assertEquals(4.0, InsulinMath.iob(listOf(InsulinDose(now, 4.0), InsulinDose(now, -4.0), InsulinDose(now, Double.NaN)), now, 4.0))
        assertEquals(Calibration(1.0, 20.0), Calibration.fit(listOf(100.0 to 120.0, Double.NaN to 120.0)))
    }
    @Test fun customRangeCategoriesAreExclusiveAndCoverExactlyOneHundredPercent() {
        val readings = listOf(50.0, 60.0, 100.0, 200.0, 275.0, 350.0).map { GlucoseReading(1000, it) }
        val st = Stats.compute(readings, 80.0, 300.0)!!
        assertEquals(50.0, st.inRangePct) // 100, 200, 275: 3 of 6
        assertEquals(100.0, st.veryLowPct + st.lowPct + st.inRangePct + st.highPct + st.veryHighPct, 1e-9)
    }

    @Test fun csvCarriageReturnsAndFormulaPrefixesAreContained() {
        val row = LogRow(1000, "Monday", "01/01/26", "1", 120, null, "=1+1\rnext")
        val csv = Logbook.toCsv(listOf(row))
        assertTrue(csv.contains("\"'=1+1\rnext\""))
    }
}
