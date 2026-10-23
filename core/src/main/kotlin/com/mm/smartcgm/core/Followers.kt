package com.mm.smartcgm.core

import java.security.SecureRandom

enum class FollowerRole(val arabic: String, val receivesCritical: Boolean, val canEdit: Boolean) {
    ADMIN("مراقب رئيسي", true, true),
    VIEWER("متابع للعرض فقط", false, false),
}

data class Follower(val id: String, val name: String, val role: FollowerRole, val joinedMs: Long)

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
