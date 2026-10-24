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

    fun clamp(v: Double, r: ClosedFloatingPointRange<Double>): Double = v.coerceIn(r.start, r.endInclusive)

    /** A dose a person or follower may log: positive, finite and not absurd. */
    fun validDose(u: Double) = u.isFinite() && u > 0 && u <= MAX_DOSE_U

    fun validCarbs(g: Double) = g.isFinite() && g > 0 && g <= MAX_CARBS_G

    /** Settings after clamping every therapy number into its safe range (high always ≥ low + 30). */
    fun therapy(t: TherapySettings): TherapySettings {
        val low = clamp(t.lowMgdl, RANGE_LOW)
        return t.copy(
            icr = clamp(t.icr, ICR), isf = clamp(t.isf, ISF), targetMgdl = clamp(t.targetMgdl, TARGET),
            diaHours = clamp(t.diaHours, DIA_H), carbAbsorptionHours = clamp(t.carbAbsorptionHours, CARB_ABSORPTION_H),
            maxBolusUnits = clamp(t.maxBolusUnits, MAX_BOLUS),
            lowMgdl = low, highMgdl = clamp(t.highMgdl, RANGE_HIGH).coerceAtLeast(low + 30),
        )
    }

    /** Alert config after clamping. The low alert always stays above urgent low. */
    fun alerts(a: AlertConfig): AlertConfig {
        val urgent = clamp(a.urgentLowMgdl, URGENT_LOW)
        return a.copy(
            urgentLowMgdl = urgent,
            low = a.low.copy(thresholdMgdl = clamp(a.low.thresholdMgdl, LOW_ALERT).coerceAtLeast(urgent + 1),
                repeatMinutes = a.low.repeatMinutes.coerceIn(1, 240)),
            high = a.high.copy(thresholdMgdl = clamp(a.high.thresholdMgdl, HIGH_ALERT),
                repeatMinutes = a.high.repeatMinutes.coerceIn(1, 240)),
            staleMinutes = a.staleMinutes.coerceIn(5, 60),
        )
    }
}
