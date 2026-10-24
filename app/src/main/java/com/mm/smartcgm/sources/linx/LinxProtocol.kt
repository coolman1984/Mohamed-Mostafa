package com.mm.smartcgm.sources.linx

/**
 * LinX GX-01S direct-BLE protocol boundary.
 *
 * IMPORTANT — EVIDENCE STATUS: BLOCKED_PENDING_EVIDENCE.
 * As of this commit NO authenticated frame, key-derivation, pairing byte sequence or CRC vector for the
 * owner's exact model/firmware has been captured or confirmed. This file therefore defines ONLY the
 * boundary (types + interfaces). The codec/pairing implementations deliberately refuse to run with a
 * clear error instead of guessing — a wrong guess could turn a corrupt frame into a false glucose value.
 *
 * Nothing here decrypts, defeats or redistributes any proprietary protection. Any future implementation
 * must come from the owner's own authorized on-device capture and open-source reference study
 * (see docs/linx-protocol.md), reviewed for licensing, and must pass BleReadingValidator before ingest.
 */

/** Raised when a protocol operation is invoked before real device evidence has been supplied. */
class ProtocolNotEvidenced(what: String) :
    IllegalStateException("LinX $what is not implemented: no verified device evidence yet (BLOCKED_PENDING_EVIDENCE)")

/** A decoded live reading from the sensor, in sensor-relative terms before validation. */
data class LinxRawReading(
    val mgdl: Double,
    /** Sensor clock in ms (relative to activation), to be mapped to wall-clock by the caller. */
    val sensorTimeMs: Long,
    /** Raw trend/rate code if the frame carries one; null when absent. */
    val trendCode: Int? = null,
)

/** Decodes raw BLE frames into readings. Implementation gated on captured, authorized evidence. */
interface LinxFrameCodec {
    /** @throws ProtocolNotEvidenced until a verified decoder exists. */
    fun decodeReading(frame: ByteArray): LinxRawReading

    companion object {
        /** The only instance shipped today: refuses to decode so nothing silently guesses. */
        val UNEVIDENCED: LinxFrameCodec = object : LinxFrameCodec {
            override fun decodeReading(frame: ByteArray): LinxRawReading = throw ProtocolNotEvidenced("frame codec")
        }
    }
}

/** Pairing variants. LEGACY and F005 are named by prior reports but are UNVERIFIED for this unit. */
enum class LinxPairingVariant { LEGACY, F005 }

interface LinxPairingCoordinator {
    /** @throws ProtocolNotEvidenced until a verified pairing flow exists. */
    fun start(variant: LinxPairingVariant, activationInput: String): Nothing
}
