package com.droiddeck.launcher.core.privileged

import com.droiddeck.launcher.session.SafetyAction
import com.droiddeck.launcher.session.SafetyInput
import com.droiddeck.launcher.session.SafetyPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchCVerifyTest {
    private fun baseInput(thermal: Int, bridgeOk: Boolean, bridgeUnknown: Boolean = false, reported: Boolean = false): SafetyInput {
        return SafetyInput(
            battery = null,
            thermalStatus = thermal,
            bridgeOk = bridgeOk,
            bridgeUnknown = bridgeUnknown,
            tweaksActive = true,
            tweaksAlreadyEnded = false,
            warnedAt15 = false,
            stopCountdownActive = false,
            bridgeLostAlreadyReported = reported,
        )
    }

    @Test
    fun unknownThermalNeverTriggersEndTweaks() {
        val unknown = SafetyPolicy.evaluate(baseInput(SafetyPolicy.THERMAL_UNKNOWN, true))
        assertEquals(SafetyAction.NONE, unknown.action)
        assertFalse(unknown.tweaksAlreadyEnded)
        val severe = SafetyPolicy.evaluate(baseInput(SafetyPolicy.THERMAL_SEVERE, true))
        assertEquals(SafetyAction.END_TWEAKS, severe.action)
    }

    @Test
    fun unknownBridgeNeitherReportsLostNorClearsReport() {
        val fresh = SafetyPolicy.evaluate(baseInput(SafetyPolicy.THERMAL_UNKNOWN, false, bridgeUnknown = true))
        assertEquals(SafetyAction.NONE, fresh.action)
        assertFalse(fresh.bridgeLostAlreadyReported)
        val reported = SafetyPolicy.evaluate(baseInput(SafetyPolicy.THERMAL_UNKNOWN, false, bridgeUnknown = true, reported = true))
        assertEquals(SafetyAction.NONE, reported.action)
        assertTrue(reported.bridgeLostAlreadyReported)
        val lost = SafetyPolicy.evaluate(baseInput(SafetyPolicy.THERMAL_UNKNOWN, false))
        assertEquals(SafetyAction.BRIDGE_LOST_RESTORE, lost.action)
    }

    @Test
    fun fakeShellMiddleStepFailureRollsBackAppliedSteps() {
        val executed = mutableListOf<String>()
        val steps = listOf(
            Triple("settings put global background_process_limit 4", "settings get global background_process_limit", "4"),
            Triple("am kill-all", null, null),
            Triple("settings put global sem_enhanced_cpu_responsiveness 1", "settings get global sem_enhanced_cpu_responsiveness", "1"),
        )
        val fake: (String) -> Pair<Int, String> = { cmd ->
            executed.add(cmd)
            when (cmd) {
                "am kill-all" -> Pair(1, "")
                "settings get global background_process_limit" -> Pair(0, "4")
                else -> Pair(0, "")
            }
        }
        var applied = 0
        var aborted = false
        for ((put, verify, expected) in steps) {
            val putResult = fake(put)
            val read = verify?.let { fake(it) }
            val ok = GameModeStepVerify.verified(
                putResult.first,
                if (read == null) null else PrivilegedResult(read.first, read.second, PrivilegedMode.NONE),
                expected,
                false,
            )
            if (!ok) {
                aborted = true
                break
            }
            applied++
        }
        assertTrue(aborted)
        assertEquals(1, applied)
        val rollback = JournalRollback.rollback(
            listOf(
                JournalItem(
                    key = "background_process_limit",
                    original = "8",
                    restoreCmd = "settings put global background_process_limit 8",
                    verifyCmd = "settings get global background_process_limit",
                    verifyMode = JournalVerifyMode.VALUE,
                    persistent = true,
                    generation = 3,
                ),
            ),
            execute = { cmd ->
                executed.add("rollback:$cmd")
                0
            },
            readVerified = { "8" },
        )
        assertTrue(rollback.success)
        assertTrue(rollback.remaining.isEmpty())
        assertTrue(executed.any { it.startsWith("rollback:settings put global background_process_limit 8") })
    }

    @Test
    fun lateDaemonResponseMissesShutdownDeadline() {
        assertTrue(ShutdownDeadline.withinDeadline(1000L, 2999L))
        assertFalse(ShutdownDeadline.withinDeadline(1000L, 3001L))
        assertEquals(0L, ShutdownDeadline.remainingMs(1000L, 3001L))
    }

    @Test
    fun perResourceVerifyFailuresKeepJournalItem() {
        val doze = JournalItem(
            key = "app-freezer-doze",
            original = null,
            restoreCmd = "cmd deviceidle unforce",
            verifyCmd = "dumpsys deviceidle",
            verifyMode = JournalVerifyMode.CONTAINS,
            persistent = true,
            generation = 3,
            verifyExpect = "mForceIdle=false",
        )
        val dozeFail = JournalRollback.rollback(listOf(doze), execute = { 0 }, readVerified = { "mForceIdle=true" })
        assertFalse(dozeFail.success)
        assertEquals("app-freezer-doze", dozeFail.failedKey)
        assertEquals(listOf(doze), dozeFail.remaining)
        val packages = JournalItem(
            key = "app-freezer-packages",
            original = "0",
            restoreCmd = "cmd package unsuspend --user 0 com.example.game",
            verifyCmd = "cmd package list packages -s --user 0",
            verifyMode = JournalVerifyMode.NOT_CONTAINS,
            persistent = true,
            generation = 3,
            verifyExpect = "com.example.game",
        )
        val pkgFail = JournalRollback.rollback(listOf(packages), execute = { 0 }, readVerified = { "com.example.game" })
        assertFalse(pkgFail.success)
        assertEquals(listOf(packages), pkgFail.remaining)
        val fixed = JournalItem(
            key = "fixed-performance",
            original = "false",
            restoreCmd = "cmd power set-fixed-performance-mode-enabled false",
            verifyCmd = "cmd power get-fixed-performance-mode-enabled",
            verifyMode = JournalVerifyMode.VALUE,
            persistent = true,
            generation = 3,
        )
        val fixedFail = JournalRollback.rollback(listOf(fixed), execute = { 0 }, readVerified = { "true" })
        assertFalse(fixedFail.success)
        assertEquals(listOf(fixed), fixedFail.remaining)
    }

    @Test
    fun corruptTargetAndBakFallBackToValidTmp() {
        val dir = createTempDir("batchc")
        val target = File(dir, "safety-journal.json")
        target.writeText("{not json")
        File(dir, "safety-journal.json.bak").writeText("{also not json")
        File(dir, "safety-journal.json.tmp").writeText("{\"version\":1,\"items\":[]}")
        val decoded = AtomicStateFile.readRecovered(target, JournalCodec::decode)
        assertEquals(emptyList<JournalItem>(), decoded)
        val dir2 = createTempDir("batchc2")
        val target2 = File(dir2, "safety-journal.json")
        File(dir2, "safety-journal.json.bak").writeText("{bad")
        File(dir2, "safety-journal.json.tmp").writeText("{bad")
        assertNull(AtomicStateFile.readRecovered(target2, JournalCodec::decode))
    }

    @Test
    fun failedSaveLeavesExistingTargetUntouched() {
        val dir = createTempDir("batchc3")
        val target = File(dir, "safety-journal.json")
        target.writeText("{\"version\":1,\"items\":[]}")
        val tmpAsDir = File(dir, "safety-journal.json.tmp")
        assertTrue(tmpAsDir.mkdir())
        assertFalse(AtomicStateFile.save(target, "{\"version\":1,\"items\":[]}"))
        assertEquals("{\"version\":1,\"items\":[]}", target.readText())
        assertTrue(tmpAsDir.delete())
        val bakAsDir = File(dir, "safety-journal.json.bak")
        assertTrue(bakAsDir.mkdir())
        assertFalse(AtomicStateFile.save(target, "{\"version\":1,\"items\":[]}"))
        assertEquals("{\"version\":1,\"items\":[]}", target.readText())
    }
}
