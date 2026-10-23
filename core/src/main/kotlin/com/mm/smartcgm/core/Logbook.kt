package com.mm.smartcgm.core

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs

enum class RangeColor { GREEN, RED, ORANGE }

/** One row of the hourly medical table: day | date | time | glucose. */
data class LogRow(
    val hourStartMs: Long,
    val dayName: String,
    /** DD/MM/YY */
    val date: String,
    /** e.g. "12 ص", "2 م" */
    val time: String,
    val mgdl: Int?,
    val color: RangeColor?,
)

object Logbook {
    private val DAYS = mapOf(
        1 to "الاثنين", 2 to "الثلاثاء", 3 to "الأربعاء", 4 to "الخميس",
        5 to "الجمعة", 6 to "السبت", 7 to "الأحد",
    )

    fun arabicDay(z: ZonedDateTime) = DAYS.getValue(z.dayOfWeek.value)

    fun date(z: ZonedDateTime) = "%02d/%02d/%02d".format(z.dayOfMonth, z.monthValue, z.year % 100)

    fun hour12(z: ZonedDateTime): String {
        val h = z.hour % 12
        return "${if (h == 0) 12 else h} ${if (z.hour < 12) "ص" else "م"}"
    }

    fun color(mgdl: Double, low: Double, high: Double) = when {
        mgdl < low -> RangeColor.RED
        mgdl > high -> RangeColor.ORANGE
        else -> RangeColor.GREEN
    }

    /**
     * Hour-by-hour rows, newest first. For each hour we take the reading closest
     * to the top of the hour (within ±30 min), like a nurse writing it in the sheet.
     */
    fun hourly(
        readings: List<GlucoseReading>,
        fromMs: Long,
        toMs: Long,
        low: Double = 70.0,
        high: Double = 180.0,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<LogRow> {
        val sorted = readings.sortedBy { it.timeMs }
        var hour = Instant.ofEpochMilli(fromMs).atZone(zone).truncatedTo(ChronoUnit.HOURS)
        val end = Instant.ofEpochMilli(toMs).atZone(zone)
        val rows = mutableListOf<LogRow>()
        while (!hour.isAfter(end)) {
            val t = hour.toInstant().toEpochMilli()
            val best = sorted.filter { abs(it.timeMs - t) <= 30 * MINUTE_MS }.minByOrNull { abs(it.timeMs - t) }
            rows += LogRow(t, arabicDay(hour), date(hour), hour12(hour),
                best?.mgdl?.let { Math.round(it).toInt() }, best?.let { color(it.mgdl, low, high) })
            hour = hour.plusHours(1)
        }
        return rows.reversed()
    }

    /** CSV with BOM so Excel opens Arabic correctly. */
    fun toCsv(rows: List<LogRow>): String = buildString {
        append('﻿')
        append("اليوم,التاريخ,التوقيت,نسبة السكر (mg/dL)\n")
        rows.forEach { append("${it.dayName},${it.date},${it.time},${it.mgdl ?: ""}\n") }
    }
}
