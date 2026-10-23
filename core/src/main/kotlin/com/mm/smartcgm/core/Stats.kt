package com.mm.smartcgm.core

import java.time.Instant
import java.time.ZoneId
import kotlin.math.sqrt

data class GlucoseStats(
    val count: Int,
    val meanMgdl: Double,
    /** Glucose Management Indicator, % (Bergenstal 2018). */
    val gmiPercent: Double,
    /** Coefficient of variation, %. Target ≤ 36. */
    val cvPercent: Double,
    val veryLowPct: Double,  // < 54
    val lowPct: Double,      // 54..69
    val inRangePct: Double,  // 70..180
    val highPct: Double,     // 181..250
    val veryHighPct: Double, // > 250
)

/** AGP band for one hour of the day. */
data class AgpHour(val hour: Int, val p5: Double, val p25: Double, val p50: Double, val p75: Double, val p95: Double)

object Stats {

    fun compute(readings: List<GlucoseReading>, low: Double = 70.0, high: Double = 180.0): GlucoseStats? {
        if (readings.isEmpty()) return null
        val v = readings.map { it.mgdl }
        val n = v.size.toDouble()
        val mean = v.average()
        val sd = sqrt(v.sumOf { (it - mean) * (it - mean) } / n)
        fun pct(p: (Double) -> Boolean) = v.count(p) * 100.0 / n
        return GlucoseStats(
            count = v.size,
            meanMgdl = mean,
            gmiPercent = 3.31 + 0.02392 * mean,
            cvPercent = if (mean == 0.0) 0.0 else sd / mean * 100,
            veryLowPct = pct { it < 54 },
            lowPct = pct { it >= 54 && it < low },
            inRangePct = pct { it >= low && it <= high },
            highPct = pct { it > high && it <= 250 },
            veryHighPct = pct { it > 250 },
        )
    }

    fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        val idx = p / 100 * (sorted.size - 1)
        val lo = idx.toInt()
        val hi = (lo + 1).coerceAtMost(sorted.size - 1)
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (idx - lo)
    }

    fun agp(readings: List<GlucoseReading>, zone: ZoneId = ZoneId.systemDefault()): List<AgpHour> =
        readings.groupBy { Instant.ofEpochMilli(it.timeMs).atZone(zone).hour }
            .toSortedMap()
            .map { (h, rs) ->
                val s = rs.map { it.mgdl }.sorted()
                AgpHour(h, percentile(s, 5.0), percentile(s, 25.0), percentile(s, 50.0), percentile(s, 75.0), percentile(s, 95.0))
            }
}
