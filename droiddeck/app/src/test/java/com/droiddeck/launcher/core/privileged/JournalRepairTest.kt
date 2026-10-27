package com.droiddeck.launcher.core.privileged

import org.junit.Assert.assertEquals
import org.junit.Test

class JournalRepairTest {
    private fun item(key: String, original: String?, restoreCmd: String, mode: JournalVerifyMode = JournalVerifyMode.VALUE): JournalItem {
        return JournalItem(
            key,
            original,
            restoreCmd,
            "settings get global $key",
            mode,
            true,
            1,
        )
    }

    @Test
    fun daemonErrorOriginalIsCorrupt() {
        val corrupt = item(
            "background_process_limit",
            "Daemon error: failed to connect to /127.0.0.1 (port 28751)",
            "settings put global background_process_limit Daemon error: failed 2>/dev/null",
        )
        assertEquals(RepairDecision.CORRUPT, JournalRepair.classify(corrupt, "2"))
    }

    @Test
    fun absentOnBothSidesIsAlreadyOk() {
        val absent = item("background_process_limit", null, "settings delete global background_process_limit 2>/dev/null")
        assertEquals(RepairDecision.ALREADY_OK, JournalRepair.classify(absent, "null"))
        assertEquals(RepairDecision.ALREADY_OK, JournalRepair.classify(absent, ""))
    }

    @Test
    fun matchingNumericCurrentIsAlreadyOk() {
        val numeric = item("background_process_limit", "8", "settings put global background_process_limit 8 2>/dev/null")
        assertEquals(RepairDecision.ALREADY_OK, JournalRepair.classify(numeric, "8\n"))
    }

    @Test
    fun differingCurrentIsRetry() {
        val numeric = item("background_process_limit", "8", "settings put global background_process_limit 8 2>/dev/null")
        assertEquals(RepairDecision.RETRY, JournalRepair.classify(numeric, "4"))
    }

    @Test
    fun nullCurrentIsUnreadable() {
        val numeric = item("background_process_limit", "8", "settings put global background_process_limit 8 2>/dev/null")
        assertEquals(RepairDecision.UNREADABLE, JournalRepair.classify(numeric, null))
    }

    @Test
    fun invalidSemEnhancedOriginalIsCorrupt() {
        val invalid = item(
            "sem_enhanced_cpu_responsiveness",
            "2",
            "settings put global sem_enhanced_cpu_responsiveness 2 2>/dev/null",
        )
        assertEquals(RepairDecision.CORRUPT, JournalRepair.classify(invalid, "1"))
    }

    @Test
    fun validNumericOriginalIsNeverCorrupt() {
        val numeric = item("background_process_limit", "2", "settings put global background_process_limit 2 2>/dev/null")
        assertEquals(RepairDecision.ALREADY_OK, JournalRepair.classify(numeric, "2"))
        assertEquals(RepairDecision.RETRY, JournalRepair.classify(numeric, "4"))
    }

    @Test
    fun nonValueModeReadableIsRetryAndNullIsUnreadable() {
        val contains = item(
            "app-freezer-doze",
            null,
            "cmd deviceidle unforce 2>/dev/null",
            JournalVerifyMode.CONTAINS,
        )
        assertEquals(RepairDecision.RETRY, JournalRepair.classify(contains, "mForceIdle=false"))
        assertEquals(RepairDecision.UNREADABLE, JournalRepair.classify(contains, null))
    }
}
