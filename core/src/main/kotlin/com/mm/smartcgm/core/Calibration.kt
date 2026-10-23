package com.mm.smartcgm.core

import kotlin.math.abs

/**
 * CGM_calibrated = CGM_raw * slope + offset.
 *
 * With one finger-stick we keep slope = 1 and only shift the offset.
 * With two or more we fit a least-squares line, then clamp the slope
 * to a safe band so one bad finger-stick can't wreck every reading.
 */
data class Calibration(val slope: Double = 1.0, val offset: Double = 0.0) {

    fun apply(rawMgdl: Double): Double = (rawMgdl * slope + offset).coerceIn(20.0, 600.0)

    companion object {
        const val MIN_SLOPE = 0.7
        const val MAX_SLOPE = 1.3
        /** A finger-stick is paired with the sensor value closest in time, within this window. */
        const val PAIR_WINDOW_MS = 10 * MINUTE_MS

        val IDENTITY = Calibration()

        /** P2-1: warn when a finger-stick differs from the sensor by more than this fraction of the sensor value. */
        const val WARN_DISAGREEMENT = 0.40

        /** |meter − sensor| / sensor, e.g. sensor 100, meter 145 → 0.45. */
        fun disagreement(sensorMgdl: Double, meterMgdl: Double): Double =
            if (sensorMgdl <= 0) 0.0 else abs(meterMgdl - sensorMgdl) / sensorMgdl

        /** True when the stick looks wrong (dirty finger, bad strip) or the sensor is failing: ask before saving. */
        fun isSuspicious(sensorMgdl: Double?, meterMgdl: Double): Boolean =
            sensorMgdl != null && disagreement(sensorMgdl, meterMgdl) > WARN_DISAGREEMENT

        /** Build pairs (raw sensor, meter) by matching each finger-stick to the nearest reading. */
        fun pair(readings: List<GlucoseReading>, sticks: List<Fingerstick>): List<Pair<Double, Double>> =
            sticks.mapNotNull { s ->
                readings.minByOrNull { abs(it.timeMs - s.timeMs) }
                    ?.takeIf { abs(it.timeMs - s.timeMs) <= PAIR_WINDOW_MS }
                    ?.let { it.mgdl to s.mgdl }
            }

        fun fit(pairs: List<Pair<Double, Double>>): Calibration {
            if (pairs.isEmpty()) return IDENTITY
            if (pairs.size == 1) {
                val (raw, meter) = pairs[0]
                return Calibration(1.0, meter - raw)
            }
            val n = pairs.size
            val mx = pairs.sumOf { it.first } / n
            val my = pairs.sumOf { it.second } / n
            val sxx = pairs.sumOf { (it.first - mx) * (it.first - mx) }
            // All raw values (almost) equal: a slope can't be estimated, fall back to offset only.
            if (sxx < 1e-6) return Calibration(1.0, my - mx)
            val sxy = pairs.sumOf { (it.first - mx) * (it.second - my) }
            val slope = (sxy / sxx).coerceIn(MIN_SLOPE, MAX_SLOPE)
            return Calibration(slope, my - slope * mx)
        }
    }
}
