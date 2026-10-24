package com.mm.smartcgm.core

/**
 * Pulls a glucose value out of another CGM app's notification text
 * (LinX / AiDEX / Sibionics / Libre apps show the live value in an ongoing notification).
 * Handles mg/dL and mmol/L, Arabic-Indic digits and comma decimals.
 */
object NotificationParser {

    /** Known CGM apps whose notification carries the live value. */
    val CGM_PACKAGES = setOf(
        "com.microtech.aidexx.mgdl",       // LinX (EU)
        "com.microtech.aidexx",            // AiDEX X
        "com.microtech.aidexx.grx1.mmoll", // Vixxa
        "com.microtechmd.cgms",            // older AiDEX
        "com.sinocare.cgm.ce",
        "com.ottai.seas",
        "com.sibionics.sibionicscgm",      // Sibionics GS1 (reported id; verify on device)
        "com.freestylelibre.app",
        "com.freestylelibre.app.de",
        "com.freestylelibre3.app",
        "com.freestylelibre3.app.de",
        "tk.glucodata",                    // Juggluco
    )

    private val ARABIC_DIGITS = mapOf(
        '٠' to '0', '١' to '1', '٢' to '2', '٣' to '3', '٤' to '4',
        '٥' to '5', '٦' to '6', '٧' to '7', '٨' to '8', '٩' to '9', '٫' to '.',
    )

    // Not part of a longer number: "2.6.1" (a version) or "12.34" must not read as 2.6 / 12.3 mmol/L.
    private val MMOL = Regex("""(?<![\d.,])(\d{1,2}[.,]\d)(?![\d]|[.,]\d)\s*(mmol)?""", RegexOption.IGNORE_CASE)
    private val MGDL = Regex("""(?<![\d.,])(\d{2,3})(?![\d.,])""")

    /** Returns mg/dL or null. */
    fun parse(text: String?): Double? {
        if (text.isNullOrBlank()) return null
        val t = text.map { ARABIC_DIGITS[it] ?: it }.joinToString("")
        val lower = t.lowercase()
        // Ignore times like 12:45 so they aren't mistaken for glucose.
        val clean = lower.replace(Regex("""\d{1,2}:\d{2}"""), " ")
        val isMmol = "mmol" in clean
        if (isMmol || !clean.contains("mg")) {
            MMOL.find(clean)?.let {
                val v = it.groupValues[1].replace(',', '.').toDouble()
                if (v in 1.0..35.0) return v * 18.0182
            }
        }
        MGDL.findAll(clean).map { it.groupValues[1].toDouble() }
            .firstOrNull { it in 39.0..500.0 }?.let { return it }
        return null
    }
}

/**
 * Stops a frozen notification from looking like live data. When the official app loses the sensor it often keeps
 * showing the LAST value (sometimes with "lost"/"ago"). Re-recording that value as new every minute would hide
 * "signal lost" and could hide a real low. A value is accepted only when its text changed, or when it is identical
 * for less than [maxSameMs] (a truly flat glucose still updates the arrow/time text in practice).
 */
class FrozenNotificationGuard(private val maxSameMs: Long = 20 * MINUTE_MS) {
    private val firstSeen = HashMap<String, Pair<String, Long>>()

    private val STALE_WORDS = listOf(
        // Words that only appear when there is NO live value. ("ago" is deliberately absent: live notifications
        // often say "updated 1 min ago".)
        "signal lost", "sensor lost", "disconnected", "no signal", "signal loss", "expired", "searching", "not connected",
        "انقطع", "مفقود", "منتهي",
    )

    @Synchronized
    fun accept(pkg: String, text: String, nowMs: Long): Boolean {
        val lower = text.lowercase()
        if (STALE_WORDS.any { it in lower }) return false
        val prev = firstSeen[pkg]
        if (prev == null || prev.first != text) {
            firstSeen[pkg] = text to nowMs
            return true
        }
        return nowMs - prev.second < maxSameMs
    }
}
