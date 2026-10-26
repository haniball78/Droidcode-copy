package com.droiddeck.launcher.core.privileged

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameModeCheckedApplyTest {
    private fun read(output: String, exitCode: Int = 0): PrivilegedResult {
        return PrivilegedResult(exitCode, output, PrivilegedMode.NONE)
    }

    @Test
    fun actionStepWithoutVerifyNeedsOnlyExitZero() {
        assertTrue(GameModeStepVerify.verified(0, null, null, false))
        assertFalse(GameModeStepVerify.verified(1, null, null, false))
    }

    @Test
    fun settingStepRequiresMatchingReadBack() {
        assertTrue(GameModeStepVerify.verified(0, read("4\n"), "4", false))
        assertFalse(GameModeStepVerify.verified(0, read("2\n"), "4", false))
        assertFalse(GameModeStepVerify.verified(0, read("null\n"), "4", false))
        assertFalse(GameModeStepVerify.verified(0, read("4\n", 1), "4", false))
    }

    @Test
    fun restrictedPerformanceVerifiesAsLiteralValue() {
        assertTrue(GameModeStepVerify.verified(0, read("0,0"), "0,0", false))
        assertFalse(GameModeStepVerify.verified(0, read("0"), "0,0", false))
    }

    @Test
    fun fixedPerformanceVerifiesOnlyOnTrue() {
        assertTrue(GameModeStepVerify.verified(0, read("true\n"), "true", true))
        assertFalse(GameModeStepVerify.verified(0, read("false\n"), "true", true))
        assertFalse(GameModeStepVerify.verified(0, read(""), "true", true))
    }

    @Test
    fun putFailureFailsEvenWithMatchingRead() {
        assertFalse(GameModeStepVerify.verified(-1, read("4\n"), "4", false))
    }
}
