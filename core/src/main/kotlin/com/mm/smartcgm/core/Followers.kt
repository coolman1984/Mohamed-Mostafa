package com.mm.smartcgm.core

import java.security.SecureRandom

enum class FollowerRole(val arabic: String, val receivesCritical: Boolean, val canEdit: Boolean) {
    ADMIN("مراقب رئيسي", true, true),
    VIEWER("متابع للعرض فقط", false, false),
}

data class Follower(val id: String, val name: String, val role: FollowerRole, val joinedMs: Long)

/**
 * P2-6: an ADMIN follower may *propose* new low/high alert thresholds. Nothing changes until the patient taps
 * "approve" on their own phone. The urgent-low limit (55) is never remotely editable (AGENTS.md safety rule 3).
 */
data class ThresholdProposal(val lowMgdl: Double, val highMgdl: Double, val timeMs: Long) {
    /** null when acceptable, otherwise the Arabic reason. */
    fun problem(urgentLowMgdl: Double = 55.0): String? = when {
        lowMgdl !in MIN_LOW..MAX_LOW -> "حد الانخفاض لازم يكون بين 60 و100 mg/dL"
        lowMgdl <= urgentLowMgdl -> "حد الانخفاض لازم يكون أعلى من حد الهبوط الخطير"
        highMgdl !in MIN_HIGH..MAX_HIGH -> "حد الارتفاع لازم يكون بين 120 و300 mg/dL"
        highMgdl - lowMgdl < MIN_GAP -> "لازم يكون في فرق 30 mg/dL على الأقل بين الحدين"
        else -> null
    }

    companion object {
        const val MIN_LOW = 60.0
        const val MAX_LOW = 100.0
        const val MIN_HIGH = 120.0
        const val MAX_HIGH = 300.0
        const val MIN_GAP = 30.0
    }
}

object Pairing {
    // No 0/O/1/I/L so it can be read over the phone.
    private const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    private val rnd = SecureRandom()

    /** Account code: long and random, it is the secret path of the patient's data. */
    fun newAccountCode(length: Int = 16): String =
        (1..length).map { ALPHABET[rnd.nextInt(ALPHABET.length)] }.joinToString("")

    /** Per-device bearer token for the sync backend (256 random bits, hex). */
    fun newToken(): String = ByteArray(32).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /**
     * One-time invite code made by the backend (10 chars, valid 24 h), optionally prefixed "SMARTCGM:" in the QR.
     * Returns the bare code or null.
     */
    fun parseInvite(code: String): String? {
        val c = code.trim().uppercase().removePrefix("SMARTCGM:").replace(" ", "").replace("-", "")
        return c.takeIf { it.length in 8..16 && it.all { ch -> ch in ALPHABET } }
    }
}
