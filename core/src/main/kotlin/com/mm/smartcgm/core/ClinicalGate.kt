package com.mm.smartcgm.core

/** Source eligibility only. Medical equations and the fixed urgent-low rule are unchanged. */
object ClinicalGate {
    /** Neither a source alias nor an explicitly untrusted sample may enter clinical consumers. */
    fun forDosing(readings: List<GlucoseReading>): List<GlucoseReading> =
        readings.filter { it.withDerivedTrust().trust == ReadingTrust.TRUSTED &&
            it.mgdl.isFinite() && it.mgdl in 20.0..600.0 }

    /** Experimental readings never replace a trusted alarm input or conceal loss of its signal. */
    fun forAlarm(readings: List<GlucoseReading>, nowMs: Long, freshMs: Long = 5 * MINUTE_MS): GlucoseReading? =
        forDosing(readings).filter { it.timeMs <= nowMs && nowMs - it.timeMs <= freshMs }
            .maxByOrNull { it.timeMs }

    /** Experimental samples are displayed separately, never used as a sole clinical alarm source. */
    fun experimentalMayRaiseLow(r: GlucoseReading, lowMgdl: Double, hasFreshTrusted: Boolean): Boolean = false
    fun experimentalMaySuppress(): Boolean = false
}
