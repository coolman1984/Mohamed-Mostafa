package com.mm.smartcgm.core

/**
 * One place for the bounds every user- or follower-entered number must respect.
 * A typo (ICR 0.1, max bolus 500, a negative dose, urgent low 5) must never reach the medical math or the alarms.
 * The dose/carb limits mirror backend/src/worker.js (MAX_DOSE_U, MAX_CARBS_G).
 */
object SafeLimits {
    const val MAX_DOSE_U = 50.0
    const val MAX_CARBS_G = 300.0

    /** Urgent low can be raised but never lowered below 55 or switched off (AGENTS.md safety rule 3). */
    val URGENT_LOW = 55.0..70.0
    val LOW_ALERT = 55.0..120.0
    val HIGH_ALERT = 120.0..400.0
    val ICR = 1.0..150.0
    val ISF = 5.0..500.0
    val TARGET = 70.0..200.0
    val MAX_BOLUS = 0.5..MAX_DOSE_U
    val CARB_ABSORPTION_H = 1.0..8.0
    val DIA_H = 2.0..8.0
    val RANGE_LOW = 54.0..120.0
    val RANGE_HIGH = 120.0..400.0

    fun clamp(v: Double, r: ClosedFloatingPointRange<Double>, fallback: Double = r.start): Double =
        if (v.isFinite()) v.coerceIn(r.start, r.endInclusive) else fallback

    /** A dose a person or follower may log: positive, finite and not absurd. */
    fun validDose(u: Double) = u.isFinite() && u > 0 && u <= MAX_DOSE_U

    fun validCarbs(g: Double) = g.isFinite() && g > 0 && g <= MAX_CARBS_G

    /** Settings after clamping every therapy number into its safe range (high always ≥ low + 30). */
    fun therapy(t: TherapySettings): TherapySettings {
        val d = TherapySettings()
        val low = clamp(t.lowMgdl, RANGE_LOW, d.lowMgdl)
        return t.copy(
            icr = clamp(t.icr, ICR, d.icr), isf = clamp(t.isf, ISF, d.isf), targetMgdl = clamp(t.targetMgdl, TARGET, d.targetMgdl),
            diaHours = clamp(t.diaHours, DIA_H, d.diaHours), carbAbsorptionHours = clamp(t.carbAbsorptionHours, CARB_ABSORPTION_H, d.carbAbsorptionHours),
            maxBolusUnits = clamp(t.maxBolusUnits, MAX_BOLUS, d.maxBolusUnits),
            lowMgdl = low, highMgdl = clamp(t.highMgdl, RANGE_HIGH, d.highMgdl).coerceAtLeast(low + 30),
        )
    }

    /** Alert config after clamping. The low alert always stays above urgent low. */
    fun alerts(a: AlertConfig): AlertConfig {
        val urgent = 55.0
        val low = clamp(a.low.thresholdMgdl, LOW_ALERT, 70.0).coerceAtLeast(urgent + 1)
        return a.copy(
            urgentLowMgdl = urgent,
            low = a.low.copy(thresholdMgdl = low,
                repeatMinutes = a.low.repeatMinutes.coerceIn(1, 240)),
            high = a.high.copy(thresholdMgdl = clamp(a.high.thresholdMgdl, HIGH_ALERT, 250.0).coerceAtLeast(low + 30),
                repeatMinutes = a.high.repeatMinutes.coerceIn(1, 240)),
            staleMinutes = a.staleMinutes.coerceIn(5, 60),
        )
    }
}
