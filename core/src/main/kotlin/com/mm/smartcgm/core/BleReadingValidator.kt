package com.mm.smartcgm.core

/**
 * Protocol-independent sanity checks every decoded sensor reading must pass before it is ingested.
 * This does NOT decode the LinX wire format (that is device-evidence-gated and lives behind
 * LinxFrameCodec in the app module). It only rejects values that cannot be real glucose, so a
 * decode bug or a corrupt/replayed frame can never become a glucose number the app acts on.
 */
object BleReadingValidator {
    const val MIN_MGDL = 20.0
    const val MAX_MGDL = 600.0

    sealed interface Result {
        data class Ok(val reading: GlucoseReading) : Result
        data class Rejected(val reason: String) : Result
    }

    /**
     * @param lastAcceptedMs newest timestamp already stored for this sensor (for monotonic/dedup checks), or null.
     */
    fun validate(
        mgdl: Double,
        timeMs: Long,
        nowMs: Long,
        sensorId: String?,
        lastAcceptedMs: Long?,
        source: String = "linx-ble",
    ): Result {
        if (mgdl.isNaN() || mgdl.isInfinite()) return Result.Rejected("not-a-number")
        if (mgdl < MIN_MGDL || mgdl > MAX_MGDL) return Result.Rejected("out-of-range:$mgdl")
        if (timeMs > nowMs + 5 * MINUTE_MS) return Result.Rejected("future-timestamp")
        if (nowMs - timeMs > 2 * HOUR_MS) return Result.Rejected("too-old-for-live")
        if (lastAcceptedMs != null && timeMs <= lastAcceptedMs) return Result.Rejected("duplicate-or-out-of-order")
        return Result.Ok(GlucoseReading(timeMs, mgdl, source, sensorId, ReadingTrust.EXPERIMENTAL))
    }

    /** A reading is stale (must be shown as "last known", not live) after this long with nothing newer. */
    const val STALE_MS = 5 * MINUTE_MS

    fun isStale(reading: GlucoseReading?, nowMs: Long): Boolean =
        reading == null || nowMs - reading.timeMs > STALE_MS
}
