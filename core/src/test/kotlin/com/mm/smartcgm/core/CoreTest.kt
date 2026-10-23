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
        // 65 / 18.0182 = 3.607 → "3.6"; header names the unit.
        val mmol = Logbook.toCsv(rows, GlucoseUnit.MMOLL)
        assertTrue(mmol.contains("الخميس,22/10/26,12 ص,3.6"), mmol)
        assertTrue(mmol.contains("(mmol/L)"))
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

    /** AGENTS.md acceptance test #8, worked by hand. ICR 10 g/u, ISF 50, target 100. */
    @Test fun bolusAcceptanceNumbers() {
        val s = TherapySettings(icr = 10.0, isf = 50.0, targetMgdl = 100.0)
        // 60 g / 10 = 6.0 u meal, BG at target → no correction → 6.0 u.
        assertEquals(6.0, BolusAdvisor.suggest(60.0, 100.0, 0.0, s).totalUnits)
        // 63 g / 10 = 6.3 u → rounded DOWN to the 0.5 step → 6.0 u (never up).
        assertEquals(6.0, BolusAdvisor.suggest(63.0, 100.0, 0.0, s).totalUnits)
        // BG 65 (below the 70 low line) → 0 u even with 60 g of carbs.
        assertEquals(0.0, BolusAdvisor.suggest(60.0, 65.0, 0.0, s).totalUnits)
        // IOB 2 u and BG 250 → correction blocked (stacking): 0 g → 0 u.
        assertEquals(0.0, BolusAdvisor.suggest(0.0, 250.0, 2.0, s).totalUnits)
    }

    /** 1 mmol/L = 18.0182 mg/dL. 100/18.0182 = 5.5499 → "5.5"; 180/18.0182 = 9.990 → "10.0"; 5.0 mmol = 90.091 mg. */
    @Test fun unitConversion() {
        assertEquals("120", GlucoseUnit.MGDL.format(120.4))
        assertEquals("5.5", GlucoseUnit.MMOLL.format(100.0))
        assertEquals("10.0", GlucoseUnit.MMOLL.format(180.0))
        assertEquals(90.091, GlucoseUnit.MMOLL.toMgdl(5.0), 1e-9)
        assertEquals(100.0, GlucoseUnit.MMOLL.toMgdl(GlucoseUnit.MMOLL.fromMgdl(100.0)), 1e-9)
        assertEquals("3.0 mmol/L", GlucoseUnit.MMOLL.formatWithUnit(54.0))
    }

    @Test fun alertTextUsesUnitButThresholdStaysMgdl() {
        val cfg = AlertConfig(unit = GlucoseUnit.MMOLL)
        val (a, _) = AlertEngine.evaluate(GlucoseReading(t0, 50.0), null, null, cfg, AlertState(), t0)
        val urgent = a.first { it.type == AlertType.URGENT_LOW }
        assertTrue(urgent.critical)
        assertTrue("2.8 mmol/L" in urgent.body, urgent.body) // 50/18.0182 = 2.775 → 2.8
    }

    @Test fun alertStateSurvivesRestart() {
        val st = AlertState(mapOf(AlertType.LOW to t0), mapOf(AlertType.HIGH to t0 + 5, AlertType.LOW to t0 + 9))
        assertEquals(st, AlertStateCodec.decode(AlertStateCodec.encode(st)))
        assertEquals(AlertState(), AlertStateCodec.decode(null))
        assertEquals(AlertState(), AlertStateCodec.decode("garbage|NOPE=1;LOW=x"))
        // Urgent low still fires while "snoozed" after a restored state.
        val snoozed = AlertStateCodec.decode(AlertStateCodec.encode(AlertState(snoozedUntil = mapOf(AlertType.URGENT_LOW to t0 + 3_600_000))))
        val (a, _) = AlertEngine.evaluate(GlucoseReading(t0, 40.0), null, null, AlertConfig(), snoozed, t0)
        assertTrue(a.any { it.type == AlertType.URGENT_LOW })
    }

    // ---- P2 additions (each number worked by hand) ----

    @Test fun stickDisagreementWarnsAbove40Percent() {
        // sensor 100: meter 145 → |45|/100 = 0.45 > 0.40 warn; meter 135 → 0.35 ok; meter 55 → 0.45 warn; exactly 140 → 0.40 not > 0.40.
        assertTrue(Calibration.isSuspicious(100.0, 145.0))
        assertTrue(!Calibration.isSuspicious(100.0, 135.0))
        assertTrue(Calibration.isSuspicious(100.0, 55.0))
        assertTrue(!Calibration.isSuspicious(100.0, 140.0))
        assertTrue(!Calibration.isSuspicious(null, 300.0)) // no sensor value → nothing to compare
        assertEquals(0.45, Calibration.disagreement(100.0, 145.0), 1e-9)
    }

    @Test fun logbookEventsLandInOneRow() {
        val z = java.time.ZoneId.of("Africa/Cairo")
        val noon = java.time.ZonedDateTime.of(2026, 10, 22, 12, 0, 0, 0, z).toInstant().toEpochMilli()
        val ev = Logbook.events(
            listOf(InsulinDose(noon + 10 * MINUTE_MS, 4.0)),
            listOf(CarbEntry(noon - 5 * MINUTE_MS, 45.0)),
            listOf(MedicationEntry(noon + 30 * MINUTE_MS, "Metformin")), // exactly +30 → belongs to 1 PM, not noon
        )
        val rows = Logbook.hourly(listOf(GlucoseReading(noon, 120.0)), noon, noon + HOUR_MS, zone = z, events = ev)
        val r12 = rows.first { it.time == "12 م" }
        val r13 = rows.first { it.time == "1 م" }
        assertEquals("🍞 45 جم • 💉 4 وحدة", r12.events)
        assertEquals("💊 Metformin", r13.events)
        val csv = Logbook.toCsv(rows)
        assertTrue(csv.contains(",120,🍞 45 جم • 💉 4 وحدة"), csv)
        assertTrue(csv.contains("الأحداث"))
        // A comma inside an event must be quoted so Excel keeps the columns.
        val q = Logbook.toCsv(listOf(r12.copy(events = "a,b")))
        assertTrue(q.contains(",\"a,b\""), q)
    }

    @Test fun coverageAndConsensus() {
        val from = 0L
        val day = DAY_MS
        // One day = 288 five-minute slots. A reading every 10 min fills 144 → 50%.
        val half = (0 until 144).map { GlucoseReading(it * 10 * MINUTE_MS, 100.0) }
        assertEquals(50.0, Stats.coveragePercent(half, from, day), 1e-9)
        // 1-minute data (1440 readings) fills all 288 slots → 100%, same as 5-minute data.
        val perMinute = (0 until 1440).map { GlucoseReading(it * MINUTE_MS, 100.0) }
        assertEquals(100.0, Stats.coveragePercent(perMinute, from, day), 1e-9)
        // 14 days with data on 10 full days → 10/14 = 71.4% (passes); 9 days → 64.3% (fails).
        val tenDays = (0 until 10 * 288).map { GlucoseReading(it * 5 * MINUTE_MS, 100.0) }
        val c10 = Stats.coveragePercent(tenDays, 0, 14 * day)
        assertEquals(1000.0 / 14, c10, 1e-9)
        assertTrue(Stats.meetsConsensus(c10, 14))
        val c9 = Stats.coveragePercent(tenDays.take(9 * 288), 0, 14 * day)
        assertTrue(!Stats.meetsConsensus(c9, 14))
        assertTrue(!Stats.meetsConsensus(100.0, 7)) // a 7-day report never meets the 14-day standard
    }

    @Test fun dailyOverlaysSplitByDay() {
        val z = java.time.ZoneId.of("UTC")
        val rs = listOf(GlucoseReading(0, 100.0), GlucoseReading(90 * MINUTE_MS, 110.0), GlucoseReading(DAY_MS + 60 * MINUTE_MS, 120.0))
        val o = Stats.dailyOverlays(rs, z)
        assertEquals(2, o.size)
        assertEquals(listOf(0 to 100.0, 90 to 110.0), o[0].second)
        assertEquals(listOf(60 to 120.0), o[1].second)
    }

    @Test fun newSensorByGap() {
        val start = 1L // 0 means "no sensor registered"
        val t = 5 * DAY_MS
        // gap exactly 2 h → no; 2 h + 1 min → yes; sensor only 3 h old (its own warm-up) → no; no previous → no.
        assertTrue(!SensorLife.newSensorSuspected(t - 2 * HOUR_MS, t, start))
        assertTrue(SensorLife.newSensorSuspected(t - 2 * HOUR_MS - MINUTE_MS, t, start))
        assertTrue(!SensorLife.newSensorSuspected(1, 3 * HOUR_MS, start))
        assertTrue(!SensorLife.newSensorSuspected(t - 3 * HOUR_MS, t, 0)) // no sensor start known
        assertTrue(!SensorLife.newSensorSuspected(null, t, start))
        // Ring: day 0 → 1.0, 7.5 days → 0.5, day 16 → 0.
        assertEquals(1.0f, SensorLife.ringFraction(SensorLife.status(0, 0)), 1e-6f)
        assertEquals(0.5f, SensorLife.ringFraction(SensorLife.status(0, (7.5 * DAY_MS).toLong())), 1e-6f)
        assertEquals(0f, SensorLife.ringFraction(SensorLife.status(0, 16 * DAY_MS)), 1e-6f)
    }

    @Test fun thresholdProposalLimits() {
        assertEquals(null, ThresholdProposal(70.0, 180.0, 0).problem())
        assertTrue(ThresholdProposal(50.0, 180.0, 0).problem() != null)   // below 60
        assertTrue(ThresholdProposal(60.0, 180.0, 0).problem(urgentLowMgdl = 60.0) != null) // not above urgent low
        assertTrue(ThresholdProposal(70.0, 350.0, 0).problem() != null)   // above 300
        assertTrue(ThresholdProposal(100.0, 125.0, 0).problem() != null)  // gap 25 < 30
        assertEquals(null, ThresholdProposal(100.0, 130.0, 0).problem())  // gap exactly 30 ok
    }
}
