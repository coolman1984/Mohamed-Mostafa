package com.mm.smartcgm.core

/**
 * Early hypo warning: projects the current trend [horizonMinutes] ahead.
 * Rate is damped a little (rates rarely hold constant for 20 min).
 */
data class Forecast(
    val predictedMgdl: Double,
    val horizonMinutes: Int,
    val ratePerMinute: Double,
    /** Minutes until crossing the low threshold, null if not heading there within the horizon. */
    val minutesToLow: Int?,
)

object Prediction {
    const val DAMPING = 0.85

    fun forecast(
        readings: List<GlucoseReading>,
        lowMgdl: Double,
        horizonMinutes: Int = 20,
    ): Forecast? {
        val latest = readings.maxByOrNull { it.timeMs } ?: return null
        val rate = Trend.ratePerMinute(readings) ?: return null
        val effective = rate * DAMPING
        val predicted = (latest.mgdl + effective * horizonMinutes).coerceIn(20.0, 600.0)
        val toLow = when {
            latest.mgdl <= lowMgdl -> 0
            effective >= 0 -> null
            else -> ((lowMgdl - latest.mgdl) / effective).toInt().takeIf { it <= horizonMinutes }
        }
        return Forecast(predicted, horizonMinutes, rate, toLow)
    }
}
