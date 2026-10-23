package com.mm.smartcgm.core

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreTest {
    private val t0 = 1_760_000_000_000L
    private fun series(vararg v: Double, stepMin: Int = 1) =
        v.mapIndexed { i, g -> GlucoseReading(t0 + i * stepMin * MINUTE_MS, g) }

    @Test fun calibrationOnePointShiftsOffset() {
        val c = Calibration.fit(listOf(100.0 to 120.0))
        assertEquals(1.0, c.slope); assertEquals(140.0, c.apply(120.0))
    }

    @Test fun calibrationTwoPointsFitsAndClampsSlope() {
        val c = Calibration.fit(listOf(100.0 to 110.0, 200.0 to 220.0))
        assertEquals(1.1, c.slope, 1e-9); assertEquals(0.0, c.offset, 1e-9)
        val wild = Calibration.fit(listOf(100.0 to 100.0, 110.0 to 300.0))
        assertEquals(Calibration.MAX_SLOPE, wild.slope)
    }

    @Test fun calibrationPairsNearestWithinWindow() {
        val r = series(100.0, 105.0, 110.0, stepMin = 5)
        val p = Calibration.pair(r, listOf(Fingerstick(t0 + 6 * MINUTE_MS, 120.0), Fingerstick(t0 + 60 * MINUTE_MS, 90.0)))
        assertEquals(listOf(105.0 to 120.0), p)
    }

    @Test fun trendArrowFalling() {
        val r = series(150.0, 147.0, 144.0, 141.0, 138.0, 135.0, 132.0) // -3/min
        assertEquals(-3.0, Trend.ratePerMinute(r)!!, 1e-9)
        assertEquals(TrendArrow.FALLING_FAST, Trend.arrow(r))
        assertEquals(TrendArrow.UNKNOWN, Trend.arrow(series(100.0, 101.0)))
    }

    @Test fun predictionWarnsBeforeLow() {
        val r = series(110.0, 108.0, 106.0, 104.0, 102.0, 100.0, 98.0) // -2/min
        val f = Prediction.forecast(r, 70.0)!!
        assertNotNull(f.minutesToLow); assertTrue(f.minutesToLow!! in 15..20)
        assertNull(Prediction.forecast(series(110.0, 111.0, 112.0, 113.0, 114.0, 115.0), 70.0)!!.minutesToLow)
    }

    @Test fun iobCurveShape() {
        assertEquals(1.0, InsulinMath.iobFraction(0.0, 4.0))
        assertEquals(0.0, InsulinMath.iobFraction(240.0, 4.0))
        val mid = InsulinMath.iobFraction(120.0, 4.0)
        assertTrue(mid in 0.2..0.6, "mid=$mid")
        val iob = InsulinMath.iob(listOf(InsulinDose(t0, 4.0), InsulinDose(t0, 20.0, InsulinKind.LONG)), t0 + 60 * MINUTE_MS, 4.0)
        assertTrue(iob in 2.5..4.0, "iob=$iob")
    }

    @Test fun cobLinear() {
        assertEquals(30.0, InsulinMath.cob(listOf(CarbEntry(t0, 60.0)), t0 + 90 * MINUTE_MS, 3.0), 1e-9)
    }

    @Test fun bolusStackingBlocksCorrection() {
        val s = TherapySettings(icr = 10.0, isf = 50.0, targetMgdl = 100.0)
        val clean = BolusAdvisor.suggest(60.0, 200.0, 0.0, s)
        assertEquals(8.0, clean.totalUnits) // 6 meal + 2 correction
        val stacked = BolusAdvisor.suggest(0.0, 250.0, 2.0, s)
        assertTrue(stacked.stackingBlocked); assertEquals(0.0, stacked.totalUnits)
        val low = BolusAdvisor.suggest(60.0, 60.0, 0.0, s)
        assertTrue(low.blockedForLow); assertEquals(0.0, low.totalUnits)
        val capped = BolusAdvisor.suggest(300.0, 100.0, 0.0, s.copy(maxBolusUnits = 10.0))
        assertTrue(capped.cappedAtMax); assertEquals(10.0, capped.totalUnits)
    }

    @Test fun statsTirAndGmi() {
        val r = series(50.0, 65.0, 100.0, 150.0, 200.0, 300.0, 120.0, 130.0, 140.0, 110.0)
        val s = Stats.compute(r)!!
        assertEquals(60.0, s.inRangePct, 1e-9)
        assertEquals(10.0, s.veryLowPct, 1e-9)
        assertEquals(3.31 + 0.02392 * 136.5, s.gmiPercent, 1e-9)
    }

    @Test fun sensorLifePhases() {
        assertEquals(SensorPhase.WARMUP, SensorLife.status(t0, t0 + 10 * MINUTE_MS).phase)
        assertEquals(SensorPhase.ACTIVE, SensorLife.status(t0, t0 + 14 * DAY_MS).phase)
        val ext = SensorLife.status(t0, t0 + 16 * DAY_MS)
        assertEquals(SensorPhase.EXTENDED, ext.phase); assertTrue(SensorLife.readingsAllowed(ext))
        val dead = SensorLife.status(t0, t0 + 20 * DAY_MS + 1)
        assertEquals(SensorPhase.EXPIRED, dead.phase); assertTrue(!SensorLife.readingsAllowed(dead))
    }

    @Test fun alertsRepeatAndSnooze() {
        val cfg = AlertConfig(low = AlertRule(true, 70.0, 5))
        val r = GlucoseReading(t0, 65.0)
        val (a1, s1) = AlertEngine.evaluate(r, null, null, cfg, AlertState(), t0)
        assertEquals(listOf(AlertType.LOW), a1.map { it.type })
        val (a2, s2) = AlertEngine.evaluate(r.copy(timeMs = t0 + 2 * MINUTE_MS), null, null, cfg, s1, t0 + 2 * MINUTE_MS)
        assertTrue(a2.isEmpty())
        val (a3, _) = AlertEngine.evaluate(r.copy(timeMs = t0 + 5 * MINUTE_MS), null, null, cfg, s2, t0 + 5 * MINUTE_MS)
        assertEquals(1, a3.size)
        val snoozed = AlertEngine.snooze(s1, AlertType.LOW, 30, t0)
        val (a4, _) = AlertEngine.evaluate(r.copy(timeMs = t0 + 10 * MINUTE_MS), null, null, cfg, snoozed, t0 + 10 * MINUTE_MS)
        assertTrue(a4.isEmpty())
        // urgent low ignores snooze and is critical
        val (a5, _) = AlertEngine.evaluate(GlucoseReading(t0 + 10 * MINUTE_MS, 45.0), null, null, cfg,
            AlertEngine.snooze(snoozed, AlertType.URGENT_LOW, 30, t0), t0 + 10 * MINUTE_MS)
        assertTrue(a5.single().critical)
        // low disabled -> silent, high separate
        val (a6, _) = AlertEngine.evaluate(r, null, null, cfg.copy(low = cfg.low.copy(enabled = false)), AlertState(), t0)
        assertTrue(a6.isEmpty())
    }

    @Test fun logbookFormatting() {
        val z = ZoneId.of("Africa/Cairo")
        val thu = ZonedDateTime.of(2026, 10, 22, 0, 0, 0, 0, z)
        assertEquals("الخميس", Logbook.arabicDay(thu))
        assertEquals("22/10/26", Logbook.date(thu))
        assertEquals("12 ص", Logbook.hour12(thu))
        assertEquals("2 م", Logbook.hour12(thu.withHour(14)))
        val start = thu.toInstant().toEpochMilli()
        val rows = Logbook.hourly(listOf(GlucoseReading(start + 5 * MINUTE_MS, 65.0), GlucoseReading(start + HOUR_MS, 170.0)),
            start, start + 2 * HOUR_MS, zone = z)
        assertEquals(3, rows.size)
        assertEquals(65, rows.last().mgdl); assertEquals(RangeColor.RED, rows.last().color)
        assertNull(rows.first().mgdl)
        assertTrue(Logbook.toCsv(rows).contains("الخميس,22/10/26,12 ص,65"))
    }

    @Test fun notificationParsing() {
        assertEquals(123.0, NotificationParser.parse("123 mg/dL →"))
        assertEquals(7.2 * 18.0182, NotificationParser.parse("7,2 mmol/L ↑")!!, 1e-6)
        assertEquals(98.0, NotificationParser.parse("١٢:٤٥ — ٩٨ mg/dL"))
        assertNull(NotificationParser.parse("Sensor warming up"))
    }

    @Test fun medicationLookup() {
        assertEquals(GlucoseEffect.RAISES, MedicationEffects.lookup("Dexamethasone 8mg")!!.effect)
        assertNull(MedicationEffects.lookup("Paracetamol"))
    }

    @Test fun pairingRoundTrip() {
        val acc = Pairing.newAccountCode()
        assertEquals(acc to FollowerRole.VIEWER, Pairing.parseInvite(Pairing.inviteCode(acc, FollowerRole.VIEWER)))
        assertNull(Pairing.parseInvite("bad"))
    }
}
