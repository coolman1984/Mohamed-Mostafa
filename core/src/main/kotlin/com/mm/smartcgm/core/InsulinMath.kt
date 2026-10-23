package com.mm.smartcgm.core

import kotlin.math.exp
import kotlin.math.max

/**
 * Insulin-on-board and carbs-on-board.
 *
 * IOB uses the exponential insulin curve popularised by OpenAPS / Loop
 * (peak ~75 min for rapid insulin), scaled to the patient's DIA.
 * Long-acting (basal) insulin is excluded: it isn't counted against boluses.
 */
object InsulinMath {

    const val PEAK_MINUTES = 75.0

    /** Fraction of a dose still active [0..1] after [minutes]. */
    fun iobFraction(minutes: Double, diaHours: Double, peakMinutes: Double = PEAK_MINUTES): Double {
        val td = diaHours * 60
        if (minutes <= 0) return 1.0
        if (minutes >= td) return 0.0
        val tp = peakMinutes.coerceAtMost(td / 2.5)
        val tau = tp * (1 - tp / td) / (1 - 2 * tp / td)
        val a = 2 * tau / td
        val s = 1 / (1 - a + (1 + a) * exp(-td / tau))
        val t = minutes
        return 1 - s * (1 - a) *
            ((t * t / (tau * td * (1 - a)) - t / tau - 1) * exp(-t / tau) + 1)
    }

    fun iob(doses: List<InsulinDose>, nowMs: Long, diaHours: Double): Double =
        doses.filter { it.kind == InsulinKind.RAPID && it.timeMs <= nowMs }
            .sumOf { it.units * iobFraction((nowMs - it.timeMs) / MINUTE_MS.toDouble(), diaHours) }
            .let { max(0.0, it) }

    /** Linear absorption over [absorptionHours]. */
    fun cob(carbs: List<CarbEntry>, nowMs: Long, absorptionHours: Double): Double =
        carbs.filter { it.timeMs <= nowMs }.sumOf {
            val frac = (nowMs - it.timeMs) / (absorptionHours * HOUR_MS)
            it.grams * (1 - frac).coerceIn(0.0, 1.0)
        }
}
