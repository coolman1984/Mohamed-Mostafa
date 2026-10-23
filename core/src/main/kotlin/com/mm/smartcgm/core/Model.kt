package com.mm.smartcgm.core

/** One glucose value in mg/dL at an epoch-millis timestamp. */
data class GlucoseReading(
    val timeMs: Long,
    val mgdl: Double,
    /** Where it came from: "juggluco", "linx-app", "nightscout", "manual"... */
    val source: String = "unknown",
    /** Sensor serial when the source reports one. */
    val sensorId: String? = null,
)

/** A finger-stick (meter) value used for calibration. */
data class Fingerstick(val timeMs: Long, val mgdl: Double)

enum class InsulinKind { RAPID, LONG }

data class InsulinDose(val timeMs: Long, val units: Double, val kind: InsulinKind = InsulinKind.RAPID)

data class CarbEntry(val timeMs: Long, val grams: Double, val note: String = "")

data class MedicationEntry(
    val timeMs: Long,
    val name: String,
    val activeIngredient: String = "",
    val effect: GlucoseEffect = GlucoseEffect.NONE,
)

enum class GlucoseEffect { RAISES, LOWERS, NONE }

/** Patient therapy settings. */
data class TherapySettings(
    /** Insulin-to-carb ratio: grams of carbs covered by 1 unit. */
    val icr: Double = 10.0,
    /** Insulin sensitivity factor: mg/dL drop per 1 unit. */
    val isf: Double = 50.0,
    val targetMgdl: Double = 110.0,
    /** Duration of insulin action in hours (spec: 4 h). */
    val diaHours: Double = 4.0,
    /** Carb absorption time in hours. */
    val carbAbsorptionHours: Double = 3.0,
    /** Hard cap on any single suggested bolus. */
    val maxBolusUnits: Double = 10.0,
    val lowMgdl: Double = 70.0,
    val highMgdl: Double = 180.0,
)

const val MINUTE_MS = 60_000L
const val HOUR_MS = 60 * MINUTE_MS
const val DAY_MS = 24 * HOUR_MS
