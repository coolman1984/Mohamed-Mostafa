package com.mm.smartcgm.sources.linx

import android.content.Context
import com.mm.smartcgm.core.BleReadingValidator
import com.mm.smartcgm.core.GlucoseReading
import com.mm.smartcgm.core.ReadingTrust

/**
 * Optional, experimental, read-only direct-BLE source for the owner's own LinX GX-01S sensor.
 *
 * STATE: disabled by default and BLOCKED_PENDING_EVIDENCE. It cannot deliver readings until a verified
 * frame codec and pairing flow exist (LinxFrameCodec/LinxPairingCoordinator), which require the owner's
 * own authorized on-device capture. See docs/linx-protocol.md and docs/linx-test-matrix.md.
 *
 * Safety contract (MASTER_MISSION Phase 3), enforced regardless of implementation progress:
 *  - Every reading it produces is tagged ReadingTrust.EXPERIMENTAL (source "linx-ble").
 *  - It reuses the shared Repo.ingest path — no private storage, no duplicate alarms.
 *  - ClinicalGate keeps experimental readings out of bolus math and out of being the sole alarm source.
 *  - It never force-stops Juggluco or the LinX app; the user is told to free the single BLE owner first.
 *  - A failure here must never suppress alerts from the working Juggluco / notification / web sources.
 */
class LinxBleSource(
    private val appContext: Context,
    private val ingest: (GlucoseReading) -> Unit,
    private val codec: LinxFrameCodec = LinxFrameCodec.UNEVIDENCED,
) {
    private val lastAcceptedBySensor = mutableMapOf<String, Long>()

    /** True only when a verified codec is wired in. Until then the source stays off. */
    fun isAvailable(): Boolean = codec !== LinxFrameCodec.UNEVIDENCED

    /**
     * Convert one raw BLE frame into a validated, experimental-tagged reading and hand it to Repo.
     * Returns the reason a frame was dropped (or null on success) so the UI can show source health.
     * Any decode failure is swallowed into a reason string — a codec bug can never crash the service
     * nor masquerade as a glucose value.
     */
    @Synchronized
    fun onFrame(frame: ByteArray, nowMs: Long = System.currentTimeMillis(), sensorId: String?): String? {
        if (sensorId.isNullOrBlank()) return "sensor-identity-unavailable"
        val raw = try {
            codec.decodeReading(frame)
        } catch (e: ProtocolNotEvidenced) {
            return "codec-unavailable"
        } catch (e: Exception) {
            return "decode-error"
        }
        // Arrival time cannot establish freshness or deduplication for replayed/history frames.
        val timeMs = raw.timeMs ?: return "sensor-clock-unmapped"
        return when (val r = BleReadingValidator.validate(raw.mgdl, timeMs, nowMs, sensorId, lastAcceptedBySensor[sensorId], "linx-ble")) {
            is BleReadingValidator.Result.Ok -> {
                check(r.reading.trust == ReadingTrust.EXPERIMENTAL) { "linx-ble reading must be experimental" }
                lastAcceptedBySensor[sensorId] = r.reading.timeMs
                ingest(r.reading)
                null
            }
            is BleReadingValidator.Result.Rejected -> r.reason
        }
    }
}
