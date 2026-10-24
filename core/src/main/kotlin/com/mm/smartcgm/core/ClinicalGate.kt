package com.mm.smartcgm.core

/**
 * The clinical safety gate for an experimental reading source (the direct-BLE LinX source).
 *
 * MASTER_MISSION Phase 3: an unverified direct-BLE sample must never:
 *   - feed bolus/dose advice, or
 *   - be the SOLE source behind a life-critical alarm,
 * until it is verified on the owner's own phone+sensor against an accepted reference and approved.
 * A failing experimental source must also never SUPPRESS alerts from healthy trusted sources.
 *
 * These are pure rules with no Android or network dependency, so they are fully unit-tested.
 */
object ClinicalGate {

    /** Readings allowed to drive insulin/bolus math: trusted only. Experimental is always excluded. */
    fun forDosing(readings: List<GlucoseReading>): List<GlucoseReading> =
        readings.filter { it.trust == ReadingTrust.TRUSTED }

    /**
     * The reading an alarm may act on, given everything seen recently.
     * Prefers the newest TRUSTED reading. An EXPERIMENTAL reading is used for alarms only when there is
     * NO fresh trusted reading — and even then only to RAISE a low/urgent-low alarm (a protective action),
     * never to silence one. Returns null when nothing is usable.
     */
    fun forAlarm(readings: List<GlucoseReading>, nowMs: Long, freshMs: Long = 5 * MINUTE_MS): GlucoseReading? {
        val fresh = readings.filter { nowMs - it.timeMs <= freshMs }
        fresh.filter { it.trust == ReadingTrust.TRUSTED }.maxByOrNull { it.timeMs }?.let { return it }
        return fresh.filter { it.trust == ReadingTrust.EXPERIMENTAL }.maxByOrNull { it.timeMs }
    }

    /**
     * True when an experimental reading is allowed to TRIGGER (raise) a low-side alarm on its own.
     * Only when it is below the low threshold (protective) and no fresh trusted reading exists.
     */
    fun experimentalMayRaiseLow(r: GlucoseReading, lowMgdl: Double, hasFreshTrusted: Boolean): Boolean =
        r.trust == ReadingTrust.EXPERIMENTAL && !hasFreshTrusted && r.mgdl < lowMgdl

    /** An experimental reading may never, on its own, clear/suppress an alarm a trusted source would raise. */
    fun experimentalMaySuppress(): Boolean = false
}
