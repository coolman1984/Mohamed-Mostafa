package com.mm.smartcgm.core

/**
 * Low and high alerts are configured completely separately.
 * Each has its own on/off, threshold and repeat interval (5, 15 or custom minutes).
 * Urgent low (< 55 by default) is always critical: it bypasses silent / Do-Not-Disturb.
 */
data class AlertRule(
    val enabled: Boolean,
    val thresholdMgdl: Double,
    val repeatMinutes: Int,
)

data class AlertConfig(
    val low: AlertRule = AlertRule(true, 70.0, 5),
    val high: AlertRule = AlertRule(true, 250.0, 15),
    val urgentLowMgdl: Double = 55.0,
    val predictiveLow: Boolean = true,
    /** Raise "signal lost" after this many minutes without a reading. */
    val staleMinutes: Int = 20,
    val criticalOverride: Boolean = true,
    /** Display unit for alert texts only; thresholds above stay in mg/dL. */
    val unit: GlucoseUnit = GlucoseUnit.MGDL,
)

enum class AlertType { URGENT_LOW, LOW, PREDICTED_LOW, HIGH, SIGNAL_LOST, SENSOR_REPLACE }

data class Alert(val type: AlertType, val critical: Boolean, val title: String, val body: String)

/** Remembers when each alert type last fired and any snooze. Kept by the caller. */
data class AlertState(
    val lastFired: Map<AlertType, Long> = emptyMap(),
    val snoozedUntil: Map<AlertType, Long> = emptyMap(),
)

object AlertEngine {

    fun evaluate(
        latest: GlucoseReading?,
        forecast: Forecast?,
        sensor: SensorStatus?,
        cfg: AlertConfig,
        state: AlertState,
        nowMs: Long,
    ): Pair<List<Alert>, AlertState> {
        val candidates = mutableListOf<Pair<Alert, Int>>() // alert + repeat minutes

        if (latest == null || nowMs - latest.timeMs > cfg.staleMinutes * MINUTE_MS) {
            candidates += Alert(AlertType.SIGNAL_LOST, false, "📡 الإشارة مقطوعة",
                "مفيش قراءة من أكتر من ${cfg.staleMinutes} دقيقة. قرّب الموبايل من الحساس.") to 30
        } else {
            val g = latest.mgdl
            when {
                g < cfg.urgentLowMgdl -> candidates += Alert(AlertType.URGENT_LOW, true, "🚨 هبوط خطير",
                    "السكر ${cfg.unit.formatWithUnit(g)} — خد سكر سريع فوراً!") to 5
                cfg.low.enabled && g < cfg.low.thresholdMgdl -> candidates += Alert(AlertType.LOW, cfg.criticalOverride,
                    "🔻 السكر واطي", "السكر ${cfg.unit.formatWithUnit(g)}") to cfg.low.repeatMinutes
                cfg.high.enabled && g > cfg.high.thresholdMgdl -> candidates += Alert(AlertType.HIGH, false,
                    "🔺 السكر عالي", "السكر ${cfg.unit.formatWithUnit(g)}") to cfg.high.repeatMinutes
            }
            val toLow = forecast?.minutesToLow
            if (cfg.predictiveLow && cfg.low.enabled && toLow != null && toLow > 0 && g >= cfg.low.thresholdMgdl) {
                candidates += Alert(AlertType.PREDICTED_LOW, false, "⏱️ هبوط متوقع",
                    "السكر هيوصل للهبوط خلال حوالي $toLow دقيقة.") to cfg.low.repeatMinutes
            }
        }
        if (sensor != null && (sensor.phase == SensorPhase.EXTENDED || sensor.phase == SensorPhase.EXPIRED)) {
            candidates += Alert(AlertType.SENSOR_REPLACE, false, "🔄 غيّر الحساس", sensor.message) to 24 * 60
        }

        val fired = mutableListOf<Alert>()
        val last = state.lastFired.toMutableMap()
        for ((alert, repeat) in candidates) {
            val snooze = state.snoozedUntil[alert.type] ?: 0
            // Urgent low ignores snooze: it's life-threatening.
            if (alert.type != AlertType.URGENT_LOW && nowMs < snooze) continue
            val prev = last[alert.type]
            if (prev == null || nowMs - prev >= repeat * MINUTE_MS) {
                fired += alert
                last[alert.type] = nowMs
            }
        }
        // Clear memory for conditions that are over, so they fire immediately next time.
        val active = candidates.map { it.first.type }.toSet()
        last.keys.retainAll(active)
        return fired to state.copy(lastFired = last)
    }

    fun snooze(state: AlertState, type: AlertType, minutes: Int, nowMs: Long): AlertState =
        state.copy(snoozedUntil = state.snoozedUntil + (type to nowMs + minutes * MINUTE_MS))
}
