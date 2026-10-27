package com.droiddeck.launcher.core.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreezerVerifyTest {
    private fun legacyPackagesItem(): JournalItem {
        return JournalItem(
            "app-freezer-packages",
            "0",
            "cmd package unsuspend --user 0 com.samsung.android.bixby.agent com.microsoft.skydrive 2>/dev/null; ",
            "cmd package list packages -s --user 0 2>/dev/null | grep -F -e com.samsung.android.bixby.agent -e com.microsoft.skydrive 2>/dev/null | wc -l",
            JournalVerifyMode.VALUE,
            true,
            999,
        )
    }

    @Test
    fun newVerifyCmdReadsDumpsysFlagNotPackageList() {
        val cmd = AppFreezerEngine.packagesVerifyCmd(listOf("com.microsoft.skydrive", "com.samsung.android.forest"))
        assertTrue(cmd.contains("for p in com.microsoft.skydrive com.samsung.android.forest"))
        assertTrue(cmd.contains("dumpsys package \$p"))
        assertTrue(cmd.contains("suspended=true"))
        assertFalse(cmd.contains("list packages -s"))
    }

    @Test
    fun legacyStoredItemUsesDerivedDumpsysCmd() {
        val effective = Tweaks.effectiveVerifyCmd(legacyPackagesItem())
        assertTrue(effective != null)
        assertTrue(effective!!.contains("for p in com.samsung.android.bixby.agent com.microsoft.skydrive"))
        assertTrue(effective.contains("dumpsys package \$p"))
        assertFalse(effective.contains("list packages -s"))
    }

    @Test
    fun nonPackagesItemKeepsStoredCmd() {
        val item = JournalItem(
            "background_process_limit",
            null,
            "settings delete global background_process_limit 2>/dev/null",
            "settings get global background_process_limit",
            JournalVerifyMode.VALUE,
            true,
            999,
        )
        assertEquals("settings get global background_process_limit", Tweaks.effectiveVerifyCmd(item))
    }

    @Test
    fun storedTargetsParseChunkedRestoreScript() {
        val restore = "cmd package unsuspend --user 0 com.a.one com.b.two 2>/dev/null; cmd package unsuspend --user 0 com.c.three 2>/dev/null; "
        assertEquals(listOf("com.a.one", "com.b.two", "com.c.three"), Tweaks.storedTargets(restore))
    }

    @Test
    fun storedTargetsEmptyWithoutUnsuspend() {
        assertTrue(Tweaks.storedTargets("settings delete global background_process_limit 2>/dev/null").isEmpty())
    }
}
