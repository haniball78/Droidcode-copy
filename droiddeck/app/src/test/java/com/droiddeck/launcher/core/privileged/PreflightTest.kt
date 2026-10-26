package com.droiddeck.launcher.core.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreflightTest {
    @Test
    fun cleanBackgroundLimitRecordedAsIs() {
        val resolved = Preflight.resolveBackgroundLimitOriginal("  8\n")
        assertEquals("8", resolved.original)
        assertFalse(resolved.leftoverDetected)
        val zero = Preflight.resolveBackgroundLimitOriginal("0")
        assertEquals("0", zero.original)
        assertFalse(zero.leftoverDetected)
    }

    @Test
    fun governorAndGameModeValuesWithEmptyJournalAreLeftover() {
        val two = Preflight.resolveBackgroundLimitOriginal("2")
        assertEquals(null, two.original)
        assertTrue(two.leftoverDetected)
        val four = Preflight.resolveBackgroundLimitOriginal("4")
        assertEquals(null, four.original)
        assertTrue(four.leftoverDetected)
    }

    @Test
    fun absentBackgroundLimitIsNotLeftover() {
        val nullValue = Preflight.resolveBackgroundLimitOriginal("null")
        assertEquals(null, nullValue.original)
        assertFalse(nullValue.leftoverDetected)
        val empty = Preflight.resolveBackgroundLimitOriginal("")
        assertEquals(null, empty.original)
        assertFalse(empty.leftoverDetected)
    }

    @Test
    fun turboOriginalsCapturedVerbatim() {
        assertEquals("1", Preflight.resolveSettingOriginal("1"))
        assertEquals("0,0", Preflight.resolveSettingOriginal("0,0"))
        assertEquals("0", Preflight.resolveSettingOriginal("0"))
        assertEquals(null, Preflight.resolveSettingOriginal("null"))
    }

    @Test
    fun journalMergeKeepsExistingKeyOriginal() {
        val current = listOf(
            JournalItem("background_process_limit", "8", "settings put global background_process_limit 8 2>/dev/null", "settings get global background_process_limit", JournalVerifyMode.VALUE, true, 1),
            JournalItem("app-freezer", null, "cmd deviceidle unforce 2>/dev/null", null, JournalVerifyMode.UNVERIFIED, true, 1),
        )
        val incoming = listOf(
            JournalItem("background_process_limit", "4", "settings put global background_process_limit 4 2>/dev/null", "settings get global background_process_limit", JournalVerifyMode.VALUE, true, 2),
            JournalItem("fixed-performance", "false", "cmd power set-fixed-performance-mode-enabled false 2>/dev/null", null, JournalVerifyMode.UNVERIFIED, true, 2),
        )
        val merged = JournalMerge.merge(current, incoming)
        assertEquals("8", merged.first { it.key == "background_process_limit" }.original)
        assertEquals(3, merged.size)
        assertEquals("fixed-performance", merged.last().key)
    }

    @Test
    fun recoveryRetryScheduleIsBoundedAndIncreasing() {
        assertEquals(4, RecoveryRetry.delaysMs.size)
        assertEquals(0L, RecoveryRetry.delaysMs.first())
        assertTrue(RecoveryRetry.isIncreasing())
        assertTrue(RecoveryRetry.delaysMs.sum() <= 20000L)
    }
}
