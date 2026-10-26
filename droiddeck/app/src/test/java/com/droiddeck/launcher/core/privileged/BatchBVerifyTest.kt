package com.droiddeck.launcher.core.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchBVerifyTest {
    private fun containsItem(expect: String): JournalItem {
        return JournalItem(
            key = "app-freezer-doze",
            original = null,
            restoreCmd = "cmd deviceidle unforce 2>/dev/null",
            verifyCmd = "dumpsys deviceidle",
            verifyMode = JournalVerifyMode.CONTAINS,
            persistent = true,
            generation = 7,
            verifyExpect = expect,
        )
    }

    @Test
    fun containsModePassesOnlyWhenOutputContainsExpectation() {
        val pass = JournalRollback.rollback(
            listOf(containsItem("mForceIdle=false")),
            execute = { 0 },
            readVerified = { "state mForceIdle=false other" },
        )
        assertTrue(pass.success)
        val fail = JournalRollback.rollback(
            listOf(containsItem("mForceIdle=false")),
            execute = { 0 },
            readVerified = { "state mForceIdle=true other" },
        )
        assertFalse(fail.success)
        assertEquals("app-freezer-doze", fail.failedKey)
    }

    @Test
    fun notContainsModePassesOnlyWhenOutputLacksExpectation() {
        val item = JournalItem(
            key = "x",
            original = null,
            restoreCmd = "true",
            verifyCmd = "echo",
            verifyMode = JournalVerifyMode.NOT_CONTAINS,
            persistent = true,
            generation = 7,
            verifyExpect = "suspended-pkg",
        )
        assertTrue(
            JournalRollback.rollback(listOf(item), execute = { 0 }, readVerified = { "clean" }).success
        )
        assertFalse(
            JournalRollback.rollback(listOf(item), execute = { 0 }, readVerified = { "suspended-pkg here" }).success
        )
    }

    @Test
    fun unverifiedModeNeverConsultsVerifyAndFailedReadFailsVerifiedModes() {
        val unverified = JournalItem(
            key = "u",
            original = null,
            restoreCmd = "true",
            verifyCmd = null,
            verifyMode = JournalVerifyMode.UNVERIFIED,
            persistent = true,
            generation = 7,
        )
        assertTrue(
            JournalRollback.rollback(
                listOf(unverified),
                execute = { 0 },
                readVerified = { throw AssertionError("verify must not be consulted") },
            ).success
        )
        val valueItem = JournalItem(
            key = "background_process_limit",
            original = "8",
            restoreCmd = "settings put global background_process_limit 8 2>/dev/null",
            verifyCmd = "settings get global background_process_limit",
            verifyMode = JournalVerifyMode.VALUE,
            persistent = true,
            generation = 7,
        )
        assertFalse(
            JournalRollback.rollback(listOf(valueItem), execute = { 0 }, readVerified = { null }).success
        )
        assertFalse(
            JournalRollback.rollback(listOf(containsItem("mForceIdle=false")), execute = { 0 }, readVerified = { null }).success
        )
    }

    @Test
    fun codecRoundTripsContainsAndMapsLegacyExitToUnverified() {
        val item = containsItem("mForceIdle=false")
        val decoded = JournalCodec.decode(JournalCodec.encode(listOf(item)))
        assertEquals(listOf(item), decoded)
        val legacy = JournalCodec.encode(listOf(item)).replace("\"mode\":\"contains\"", "\"mode\":\"exit\"")
        val legacyDecoded = JournalCodec.decode(legacy)
        assertEquals(JournalVerifyMode.UNVERIFIED, legacyDecoded?.firstOrNull()?.verifyMode)
        val missingExpect = JournalCodec.encode(listOf(item)).replace("\"verifyExpect\":\"mForceIdle=false\"", "\"verifyExpect\":null")
        assertEquals(null, JournalCodec.decode(missingExpect))
    }

    @Test
    fun boostUncertaintyKeepsItemUnlessProvenNeverEnabled() {
        assertFalse(BoostUncertainty.shouldRemoveItem(true, 0, "false"))
        assertTrue(BoostUncertainty.shouldRemoveItem(false, 0, "false"))
        assertTrue(BoostUncertainty.shouldRemoveItem(false, 0, "  false\n"))
        assertFalse(BoostUncertainty.shouldRemoveItem(false, 0, "true"))
        assertFalse(BoostUncertainty.shouldRemoveItem(false, 0, ""))
        assertFalse(BoostUncertainty.shouldRemoveItem(false, 0, null))
        assertFalse(BoostUncertainty.shouldRemoveItem(false, -1, "false"))
        assertFalse(BoostUncertainty.shouldRemoveItem(false, 1, "false"))
    }

    @Test
    fun recoveryGenerationStaleRule() {
        assertFalse(RecoveryGeneration.isStale(0, 5))
        assertFalse(RecoveryGeneration.isStale(5, 5))
        assertTrue(RecoveryGeneration.isStale(3, 5))
        assertTrue(RecoveryGeneration.isStale(5, 0))
    }
}
