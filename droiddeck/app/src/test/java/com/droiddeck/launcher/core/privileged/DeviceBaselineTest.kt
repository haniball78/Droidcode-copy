package com.droiddeck.launcher.core.privileged

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceBaselineTest {
    private fun tempDir(): File = Files.createTempDirectory("baseline-test").toFile()

    @Test
    fun unreadableValuesAreNeverGuessedAsClean() {
        val def = BaselineDefinition("settings.global.background_process_limit", "settings get global background_process_limit", DeviceBaseline::parseSetting)
        val failed = DeviceBaseline.entryFor(def, 1, "")
        assertNull(failed.value)
        assertTrue(failed.display.startsWith("UNREADABLE"))
        val blank = DeviceBaseline.entryFor(def, 0, "null")
        assertNull(blank.value)
        assertTrue(blank.display.startsWith("UNREADABLE"))
        val good = DeviceBaseline.entryFor(def, 0, "4")
        assertEquals("4", good.value)
    }

    @Test
    fun noGetterIsRecordedUnreadableWithReason() {
        val def = DeviceBaseline.definitions("com.example").first { it.key == "activity.child_process_restrictions" }
        val entry = DeviceBaseline.entryFor(def, -1, null)
        assertNull(entry.value)
        assertTrue(entry.unreadableReason!!.contains("no public getter"))
    }

    @Test
    fun whitelistForceIdleAndGrantsParse() {
        assertEquals("whitelisted", DeviceBaseline.parseWhitelist("com.example\n", "com.example"))
        assertEquals("not whitelisted", DeviceBaseline.parseWhitelist("other.pkg\n", "com.example"))
        assertNull(DeviceBaseline.parseWhitelist("", "com.example"))
        assertEquals("true", DeviceBaseline.parseForceIdle("mForceIdle=true mScreenOn=false"))
        assertNull(DeviceBaseline.parseForceIdle("no idle line here"))
        val dump = "grantedPermissions:\n  android.permission.DUMP\ninstallPermissions:\n"
        assertEquals("granted", DeviceBaseline.parseGranted(dump, "android.permission.DUMP"))
        assertEquals("not granted", DeviceBaseline.parseGranted(dump, "android.permission.WRITE_SECURE_SETTINGS"))
        assertNull(DeviceBaseline.parseGranted("", "android.permission.DUMP"))
    }

    @Test
    fun definitionsCoverEveryMutatedAngle() {
        val keys = DeviceBaseline.definitions("com.example").map { it.key }
        assertTrue(keys.any { it.contains("background_process_limit") })
        assertTrue(keys.any { it.contains("fixed_performance") })
        assertTrue(keys.any { it.contains("phantom") })
        assertTrue(keys.any { it.contains("whitelist") })
        assertTrue(keys.any { it.contains("RUN_IN_BACKGROUND") })
        assertTrue(keys.any { it.contains("suspended") })
        assertTrue(keys.any { it.contains("force_idle") })
        assertTrue(keys.any { it.contains("game.modes") })
    }

    @Test
    fun fileNameIsTimestamped() {
        val name = DeviceBaseline.fileName(1790000000000L)
        assertTrue(name.startsWith("device-baseline-"))
        assertTrue(name.endsWith(".md"))
        assertFalse(name.contains(" "))
    }

    @Test
    fun resolveUniqueFileGuardDoesNotOverwriteExistingFile() {
        val dir = tempDir()
        val first = File(dir, "device-baseline-20260101-000000.md")
        first.writeText("original baseline")
        val second = DeviceBaseline.resolveUniqueFile(dir, first.name)
        assertFalse(second.exists())
        assertTrue(second.name.contains("-2"))
        second.writeText("later capture")
        val third = DeviceBaseline.resolveUniqueFile(dir, first.name)
        assertTrue(third.name.contains("-3"))
        assertEquals("original baseline", first.readText())
    }

    @Test
    fun renderKeepsUnreadableEntriesVisible() {
        val entries = listOf(
            BaselineEntry("settings.global.background_process_limit", "settings get global background_process_limit", "4", null),
            BaselineEntry("power.fixed_performance", "cmd power get-fixed-performance-mode-enabled", null, "read returned no usable value"),
        )
        val text = DeviceBaseline.renderMarkdown("com.example", 1790000000000L, entries, listOf("test note"))
        assertTrue(text.contains("background_process_limit"))
        assertTrue(text.contains("UNREADABLE"))
        assertTrue(text.contains("never overwritten"))
        assertTrue(text.contains("test note"))
    }
}
