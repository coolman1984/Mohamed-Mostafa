package com.mm.smartcgm.core

enum class TrendArrow(val symbol: String, val arabic: String) {
    RISING_FAST("⇈", "ارتفاع سريع"),
    RISING("↑", "ارتفاع"),
    RISING_SLOW("↗", "ارتفاع بطيء"),
    FLAT("→", "مستقر"),
    FALLING_SLOW("↘", "انخفاض بطيء"),
    FALLING("↓", "انخفاض"),
    FALLING_FAST("⇊", "انخفاض سريع"),
    UNKNOWN("?", "غير معروف");

    companion object {
        /** Standard CGM thresholds, rate in mg/dL per minute. */
        fun fromRate(rate: Double?): TrendArrow = when {
            rate == null || rate.isNaN() -> UNKNOWN
            rate >= 3 -> RISING_FAST
            rate >= 2 -> RISING
            rate >= 1 -> RISING_SLOW
            rate > -1 -> FLAT
            rate > -2 -> FALLING_SLOW
            rate > -3 -> FALLING
            else -> FALLING_FAST
        }
    }
}

object Trend {
    const val WINDOW_MS = 15 * MINUTE_MS

    /**
     * Rate of change in mg/dL per minute from a least-squares line over the
     * last [windowMs]. Needs at least 3 points spread over 5+ minutes.
     */
    fun ratePerMinute(readings: List<GlucoseReading>, windowMs: Long = WINDOW_MS): Double? {
        if (readings.isEmpty()) return null
        val latest = readings.maxOf { it.timeMs }
        val pts = readings.filter { it.timeMs >= latest - windowMs }
        if (pts.size < 3 || latest - pts.minOf { it.timeMs } < 5 * MINUTE_MS) return null
        val xs = pts.map { (it.timeMs - latest) / MINUTE_MS.toDouble() }
        val ys = pts.map { it.mgdl }
        val mx = xs.average()
        val my = ys.average()
        val sxx = xs.sumOf { (it - mx) * (it - mx) }
        if (sxx == 0.0) return null
        return xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / sxx
    }

    fun arrow(readings: List<GlucoseReading>): TrendArrow = TrendArrow.fromRate(ratePerMinute(readings))
}
