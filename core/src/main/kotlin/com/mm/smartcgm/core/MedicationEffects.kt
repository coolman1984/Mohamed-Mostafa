package com.mm.smartcgm.core

/**
 * Small offline knowledge base: medicines known to move blood glucose.
 * Used after the camera reads a medicine box, so the warning works even without internet.
 */
object MedicationEffects {

    data class Rule(val keywords: List<String>, val effect: GlucoseEffect, val warning: String)

    val RULES = listOf(
        Rule(listOf("prednisolone", "prednisone", "dexamethasone", "hydrocortisone", "methylprednisolone",
            "betamethasone", "cortisone", "كورتيزون", "ديكساميثازون", "بريدنيزولون"),
            GlucoseEffect.RAISES, "كورتيزون: بيرفع السكر بقوة لساعات أو أيام — راقب كل ساعة وكلّم الدكتور لو استمر."),
        Rule(listOf("syrup", "sirop", "شراب", "cough", "كحة"),
            GlucoseEffect.RAISES, "شراب الكحة غالباً فيه سكر — ممكن يرفع السكر مؤقتاً. دوّر على نوع خالي من السكر."),
        Rule(listOf("pseudoephedrine", "phenylephrine", "ephedrine"),
            GlucoseEffect.RAISES, "مزيل الاحتقان ممكن يرفع السكر شوية."),
        Rule(listOf("salbutamol", "albuterol", "ventolin", "فنتولين"),
            GlucoseEffect.RAISES, "بخاخ الربو (سالبيوتامول) ممكن يرفع السكر مؤقتاً."),
        Rule(listOf("tacrolimus", "cyclosporine"),
            GlucoseEffect.RAISES, "دوا مناعة: ممكن يرفع السكر على المدى الطويل."),
        Rule(listOf("glucose", "dextrose", "جلوكوز"),
            GlucoseEffect.RAISES, "فيه جلوكوز: هيرفع السكر بسرعة."),
        Rule(listOf("insulin", "انسولين", "أنسولين", "glargine", "lispro", "aspart", "degludec"),
            GlucoseEffect.LOWERS, "أنسولين: سجّل الجرعة عشان حساب الأنسولين الفعال."),
        Rule(listOf("metformin", "glimepiride", "gliclazide", "sitagliptin", "empagliflozin", "dapagliflozin"),
            GlucoseEffect.LOWERS, "دوا سكر: بيقلّل السكر."),
        Rule(listOf("propranolol", "atenolol", "bisoprolol"),
            GlucoseEffect.NONE, "حاصرات بيتا ممكن تخفي أعراض الهبوط — اعتمد على الإنذارات."),
    )

    fun lookup(text: String): Rule? {
        val t = text.lowercase()
        return RULES.firstOrNull { r -> r.keywords.any { t.contains(it) } }
    }
}
