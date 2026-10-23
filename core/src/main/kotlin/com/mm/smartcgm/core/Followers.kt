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

    /** Code a follower types (or scans as QR): account code + role letter. */
    fun inviteCode(accountCode: String, role: FollowerRole): String =
        "$accountCode-${if (role == FollowerRole.ADMIN) 'A' else 'V'}"

    fun parseInvite(code: String): Pair<String, FollowerRole>? {
        val c = code.trim().uppercase().removePrefix("SMARTCGM:")
        val parts = c.split('-')
        if (parts.size != 2 || parts[0].length < 12 || parts[0].any { it !in ALPHABET }) return null
        val role = when (parts[1]) { "A" -> FollowerRole.ADMIN; "V" -> FollowerRole.VIEWER; else -> return null }
        return parts[0] to role
    }
}
