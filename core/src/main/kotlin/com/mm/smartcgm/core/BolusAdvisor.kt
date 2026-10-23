package com.mm.smartcgm.core

import kotlin.math.floor
import kotlin.math.max

/**
 * Suggests (never injects) a bolus.
 *
 * meal part       = carbs / ICR
 * correction part = (BG - target) / ISF, only when BG is above target
 * total           = meal + correction - IOB  (IOB first eats the correction, then the meal part)
 *
 * Stacking protection: while meaningful insulin is still active, no correction is
 * suggested for a high reading; only the meal part (minus leftover IOB) is offered.
 */
data class BolusSuggestion(
    val mealUnits: Double,
    val correctionUnits: Double,
    val iobUnits: Double,
    val totalUnits: Double,
    val stackingBlocked: Boolean,
    val cappedAtMax: Boolean,
    val blockedForLow: Boolean,
    val warnings: List<String>,
)

object BolusAdvisor {
    /** IOB above this blocks correction doses. */
    const val STACKING_IOB_UNITS = 0.3
    /** Suggestions are rounded down to this step (most pens dose in 0.5 u). */
    const val DOSE_STEP = 0.5

    fun suggest(
        carbsGrams: Double,
        currentMgdl: Double?,
        iobUnits: Double,
        settings: TherapySettings,
        predictedLow: Boolean = false,
    ): BolusSuggestion {
        val warnings = mutableListOf<String>()
        val meal = max(0.0, carbsGrams) / settings.icr

        if (currentMgdl != null && (currentMgdl < settings.lowMgdl || predictedLow)) {
            warnings += "السكر منخفض أو متوقع ينخفض: ممنوع أي جرعة دلوقتي — عالج الهبوط الأول."
            return BolusSuggestion(meal, 0.0, iobUnits, 0.0, false, false, true, warnings)
        }

        var correction = 0.0
        var stacking = false
        if (currentMgdl != null && currentMgdl > settings.targetMgdl) {
            if (iobUnits > STACKING_IOB_UNITS) {
                stacking = true
                warnings += "فيه أنسولين لسه شغال (%.1f وحدة): مفيش جرعة تصحيح عشان ما يحصلش تراكم.".format(iobUnits)
            } else {
                correction = (currentMgdl - settings.targetMgdl) / settings.isf
            }
        }
        if (currentMgdl == null) warnings += "مفيش قراءة حديثة: الحساب على الأكل بس."

        var total = max(0.0, meal + correction - iobUnits)
        var capped = false
        if (total > settings.maxBolusUnits) {
            total = settings.maxBolusUnits
            capped = true
            warnings += "الجرعة اتقصّت للحد الأقصى المسموح (%.1f وحدة).".format(settings.maxBolusUnits)
        }
        total = floor(total / DOSE_STEP) * DOSE_STEP
        return BolusSuggestion(meal, correction, iobUnits, total, stacking, capped, false, warnings)
    }
}
