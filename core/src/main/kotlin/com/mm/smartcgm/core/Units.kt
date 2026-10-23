package com.mm.smartcgm.core

import java.util.Locale

/**
 * Display unit only. Every value is STORED and COMPUTED in mg/dL; conversion happens at the edges (UI, exports,
 * notifications). 1 mmol/L glucose = 18.0182 mg/dL (molar mass 180.16 g/mol / 10).
 */
enum class GlucoseUnit(val label: String) {
    MGDL("mg/dL"),
    MMOLL("mmol/L");

    fun fromMgdl(mgdl: Double): Double = if (this == MGDL) mgdl else mgdl / MGDL_PER_MMOL
    fun toMgdl(v: Double): Double = if (this == MGDL) v else v * MGDL_PER_MMOL

    /** "120" for mg/dL, "6.7" for mmol/L (always with a dot so CSV/Excel parses it). */
    fun format(mgdl: Double): String =
        if (this == MGDL) String.format(Locale.US, "%.0f", mgdl) else String.format(Locale.US, "%.1f", fromMgdl(mgdl))

    fun formatWithUnit(mgdl: Double): String = "${format(mgdl)} $label"

    companion object { const val MGDL_PER_MMOL = 18.0182 }
}

/** Persists [AlertState] as plain text ("LOW=123;HIGH=456|LOW=789") so snoozes survive a process restart. */
object AlertStateCodec {
    private fun enc(m: Map<AlertType, Long>) = m.entries.joinToString(";") { "${it.key.name}=${it.value}" }
    private fun dec(s: String): Map<AlertType, Long> = s.split(';').mapNotNull { part ->
        val kv = part.split('=')
        if (kv.size != 2) return@mapNotNull null
        val type = AlertType.entries.firstOrNull { it.name == kv[0] } ?: return@mapNotNull null
        val t = kv[1].toLongOrNull() ?: return@mapNotNull null
        type to t
    }.toMap()

    fun encode(s: AlertState): String = enc(s.lastFired) + "|" + enc(s.snoozedUntil)

    fun decode(text: String?): AlertState {
        if (text.isNullOrBlank()) return AlertState()
        val parts = text.split('|')
        return AlertState(dec(parts[0]), if (parts.size > 1) dec(parts[1]) else emptyMap())
    }
}
