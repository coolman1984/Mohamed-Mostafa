package com.mm.smartcgm.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests for the clinical gate and the BLE reading validator. No device, no protocol — pure rules. */
class LinxGateTest {
    private val now = 1_800_000_000_000L
    private fun trusted(t: Long, g: Double) = GlucoseReading(t, g, "juggluco", trust = ReadingTrust.TRUSTED)
    private fun exp(t: Long, g: Double) = GlucoseReading(t, g, "linx-ble", trust = ReadingTrust.EXPERIMENTAL)

    @Test fun dosingExcludesExperimental() {
        val list = listOf(trusted(now, 120.0), exp(now, 118.0))
        assertEquals(listOf("juggluco"), ClinicalGate.forDosing(list).map { it.source })
    }

    @Test fun alarmPrefersTrustedEvenIfOlderButFresh() {
        val list = listOf(trusted(now - 2 * MINUTE_MS, 90.0), exp(now, 60.0))
        assertEquals(ReadingTrust.TRUSTED, ClinicalGate.forAlarm(list, now)!!.trust)
    }

    @Test fun alarmUsesExperimentalOnlyWhenNoFreshTrusted() {
        // trusted reading is stale (10 min old), experimental is fresh
        val list = listOf(trusted(now - 10 * MINUTE_MS, 90.0), exp(now, 55.0))
        val r = ClinicalGate.forAlarm(list, now)!!
        assertEquals(ReadingTrust.EXPERIMENTAL, r.trust)
    }

    @Test fun alarmReturnsNullWhenAllStale() {
        assertNull(ClinicalGate.forAlarm(listOf(trusted(now - 20 * MINUTE_MS, 90.0)), now))
    }

    @Test fun experimentalMayRaiseLowOnlyProtectivelyAndAlone() {
        assertTrue(ClinicalGate.experimentalMayRaiseLow(exp(now, 60.0), 70.0, hasFreshTrusted = false))
        // not when a trusted source is present
        assertFalse(ClinicalGate.experimentalMayRaiseLow(exp(now, 60.0), 70.0, hasFreshTrusted = true))
        // not when the value is in range (would not be protective)
        assertFalse(ClinicalGate.experimentalMayRaiseLow(exp(now, 120.0), 70.0, hasFreshTrusted = false))
        // a trusted reading is handled by the normal path, not this one
        assertFalse(ClinicalGate.experimentalMayRaiseLow(trusted(now, 60.0), 70.0, hasFreshTrusted = false))
    }

    @Test fun experimentalNeverSuppresses() {
        assertFalse(ClinicalGate.experimentalMaySuppress())
    }

    @Test fun validatorAcceptsPlausibleAndTagsExperimental() {
        val r = BleReadingValidator.validate(110.0, now, now, "SN-X", null)
        assertTrue(r is BleReadingValidator.Result.Ok)
        val ok = (r as BleReadingValidator.Result.Ok).reading
        assertEquals(ReadingTrust.EXPERIMENTAL, ok.trust)
        assertEquals(110.0, ok.mgdl)
    }

    @Test fun validatorRejectsBadValues() {
        assertTrue(BleReadingValidator.validate(Double.NaN, now, now, null, null) is BleReadingValidator.Result.Rejected)
        assertTrue(BleReadingValidator.validate(5.0, now, now, null, null) is BleReadingValidator.Result.Rejected)
        assertTrue(BleReadingValidator.validate(700.0, now, now, null, null) is BleReadingValidator.Result.Rejected)
        assertTrue(BleReadingValidator.validate(100.0, now + 10 * MINUTE_MS, now, null, null) is BleReadingValidator.Result.Rejected)
        assertTrue(BleReadingValidator.validate(100.0, now - 3 * HOUR_MS, now, null, null) is BleReadingValidator.Result.Rejected)
    }

    @Test fun validatorRejectsDuplicateOrOutOfOrder() {
        assertTrue(BleReadingValidator.validate(100.0, now, now, "SN", now) is BleReadingValidator.Result.Rejected)
        assertTrue(BleReadingValidator.validate(100.0, now - MINUTE_MS, now, "SN", now) is BleReadingValidator.Result.Rejected)
        assertTrue(BleReadingValidator.validate(100.0, now + MINUTE_MS, now + MINUTE_MS, "SN", now) is BleReadingValidator.Result.Ok)
    }

    @Test fun staleAfterFiveMinutes() {
        assertFalse(BleReadingValidator.isStale(exp(now - 4 * MINUTE_MS, 100.0), now))
        assertTrue(BleReadingValidator.isStale(exp(now - 6 * MINUTE_MS, 100.0), now))
        assertTrue(BleReadingValidator.isStale(null, now))
    }
}
