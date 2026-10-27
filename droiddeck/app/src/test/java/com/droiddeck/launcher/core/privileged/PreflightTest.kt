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

    @Test
    fun daemonErrorTextIsInvalidNotAnOriginal() {
        val resolved = Preflight.resolveBackgroundLimitOriginal("Daemon error: failed to connect to /127.0.0.1 (port 28751) from /127.0.0.1 (port 46564) after 3000ms: isConnected failed: ECONNREFUSED (Connection refused)")
        assertFalse(resolved.valid)
        assertFalse(resolved.leftoverDetected)
        assertEquals(null, resolved.original)
    }

    @Test
    fun settingValidatorsRejectErrorText() {
        assertFalse(SafetyChecks.isValidSettingValue("background_process_limit", "Daemon error: failed"))
        assertTrue(SafetyChecks.isValidSettingValue("background_process_limit", "8"))
        assertTrue(SafetyChecks.isValidSettingValue("background_process_limit", null))
        assertFalse(SafetyChecks.isValidSettingValue("sem_enhanced_cpu_responsiveness", "2"))
        assertTrue(SafetyChecks.isValidSettingValue("sem_enhanced_cpu_responsiveness", "1"))
        assertFalse(SafetyChecks.isValidSettingValue("restricted_device_performance", "Daemon error"))
        assertTrue(SafetyChecks.isValidSettingValue("restricted_device_performance", "1,0"))
    }

    @Test
    fun rollbackNeverExecutesCorruptRestoreCommand() {
        val corrupt = JournalItem(
            "background_process_limit",
            "Daemon error: failed to connect",
            "settings put global background_process_limit Daemon error: failed 2>/dev/null",
            "settings get global background_process_limit",
            JournalVerifyMode.VALUE,
            true,
            1,
        )
        var executed = false
        val result = JournalRollback.rollback(
            listOf(corrupt),
            execute = { executed = true; 0 },
            readVerified = { "8" },
        )
        assertFalse(executed)
        assertFalse(result.success)
        assertEquals("background_process_limit", result.failedKey)
    }
}
