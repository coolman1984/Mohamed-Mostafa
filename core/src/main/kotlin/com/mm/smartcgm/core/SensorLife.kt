package com.mm.smartcgm.core

import kotlin.math.ceil

/**
 * Sensor wear policy requested by the owner:
 *  - official life 15 days,
 *  - the app keeps showing readings up to day 20 *if the sensor still sends*,
 *  - from day 15 on, a daily "replace your sensor" warning,
 *  - after day 20 readings are hidden (too unreliable).
 *
 * Note: the app cannot make the sensor transmit longer; many sensors stop by themselves at 14–15 days.
 */
enum class SensorPhase { WARMUP, ACTIVE, EXTENDED, EXPIRED }

data class SensorStatus(
    val phase: SensorPhase,
    val dayNumber: Int,
    val daysLeftOfficial: Double,
    val daysLeftHard: Double,
    val message: String,
)

object SensorLife {
    const val OFFICIAL_DAYS = 15
    const val HARD_LIMIT_DAYS = 20
    const val WARMUP_MINUTES = 60L

    fun status(startMs: Long, nowMs: Long, warmupMinutes: Long = WARMUP_MINUTES): SensorStatus {
        val ageMs = (nowMs - startMs).coerceAtLeast(0)
        val ageDays = ageMs.toDouble() / DAY_MS
        val day = ceil(ageDays).toInt().coerceAtLeast(1)
        val leftOfficial = OFFICIAL_DAYS - ageDays
        val leftHard = HARD_LIMIT_DAYS - ageDays
        return when {
            ageMs < warmupMinutes * MINUTE_MS ->
                SensorStatus(SensorPhase.WARMUP, day, leftOfficial, leftHard,
                    "⏳ الحساس بيسخّن — القراءات هتبدأ بعد %d دقيقة.".format(warmupMinutes - ageMs / MINUTE_MS))
            ageDays < OFFICIAL_DAYS ->
                SensorStatus(SensorPhase.ACTIVE, day, leftOfficial, leftHard,
                    "✅ اليوم %d — فاضل %.1f يوم.".format(day, leftOfficial))
            ageDays < HARD_LIMIT_DAYS ->
                SensorStatus(SensorPhase.EXTENDED, day, leftOfficial, leftHard,
                    "⚠️ الحساس عدّى 15 يوم — لازم تغيّره. هيقف نهائياً بعد %.1f يوم.".format(leftHard))
            else ->
                SensorStatus(SensorPhase.EXPIRED, day, leftOfficial, leftHard,
                    "⛔ الحساس خلص (20 يوم). القراءات متوقفة — ركّب حساس جديد.")
        }
    }

    /** P2-5: a silence this long followed by new readings usually means a new sensor (warm-up sends nothing). */
    const val NEW_SENSOR_GAP_MS = 2 * HOUR_MS

    /**
     * True when the gap between the previous and the new reading is > 2 h AND the current sensor is at least a day old
     * (a fresh sensor's own warm-up gap must not trigger it). Only a *suggestion*: the user confirms before the
     * 15/20-day clock restarts, because a phone that was off for 2 h looks the same.
     */
    fun newSensorSuspected(prevReadingMs: Long?, newReadingMs: Long, sensorStartMs: Long): Boolean {
        if (prevReadingMs == null || sensorStartMs <= 0) return false
        if (newReadingMs - prevReadingMs <= NEW_SENSOR_GAP_MS) return false
        return newReadingMs - sensorStartMs >= DAY_MS
    }

    /** Fraction of the official 15 days still left, for the Home ring (1 = new, 0 = due). */
    fun ringFraction(s: SensorStatus): Float = (s.daysLeftOfficial / OFFICIAL_DAYS).coerceIn(0.0, 1.0).toFloat()

    fun readingsAllowed(s: SensorStatus) = s.phase == SensorPhase.ACTIVE || s.phase == SensorPhase.EXTENDED
}
