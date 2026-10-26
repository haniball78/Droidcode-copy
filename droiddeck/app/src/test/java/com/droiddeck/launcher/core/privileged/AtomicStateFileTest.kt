package com.droiddeck.launcher.core.privileged

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AtomicStateFileTest {
    private fun tempDir(): File = Files.createTempDirectory("atomic-state-test").toFile()

    private fun decode(text: String): String? = if (text.startsWith("valid:")) text else null

    @Test
    fun saveReplacesTargetAndRemovesBackup() {
        val dir = tempDir()
        val file = File(dir, "state.json")
        assertTrue(AtomicStateFile.save(file, "valid:first"))
        assertTrue(AtomicStateFile.save(file, "valid:second"))
        assertEquals("valid:second", file.readText())
        assertFalse(AtomicStateFile.bakFile(file).exists())
        assertFalse(AtomicStateFile.tmpFile(file).exists())
    }

    @Test
    fun readRecoveredPrefersTargetOverBackup() {
        val dir = tempDir()
        val file = File(dir, "state.json")
        file.writeText("valid:target")
        AtomicStateFile.bakFile(file).writeText("valid:backup")
        assertEquals("valid:target", AtomicStateFile.readRecovered(file, ::decode))
    }

    @Test
    fun readRecoveredFallsBackToCommittedBackupOverUncommittedTemp() {
        val dir = tempDir()
        val file = File(dir, "state.json")
        file.writeText("corrupt")
        AtomicStateFile.bakFile(file).writeText("valid:backup")
        AtomicStateFile.tmpFile(file).writeText("valid:attempt")
        assertEquals("valid:backup", AtomicStateFile.readRecovered(file, ::decode))
    }

    @Test
    fun readRecoveredUsesTempOnlyWhenNoCommittedStateSurvives() {
        val dir = tempDir()
        val file = File(dir, "state.json")
        AtomicStateFile.tmpFile(file).writeText("valid:attempt")
        assertEquals("valid:attempt", AtomicStateFile.readRecovered(file, ::decode))
        assertTrue(file.exists())
        assertEquals("valid:attempt", file.readText())
    }

    @Test
    fun readRecoveredReturnsNullWhenNothingDecodes() {
        val dir = tempDir()
        val file = File(dir, "state.json")
        file.writeText("corrupt")
        AtomicStateFile.bakFile(file).writeText("also corrupt")
        assertNull(AtomicStateFile.readRecovered(file, ::decode))
    }

    @Test
    fun deleteRemovesTargetBackupAndTemp() {
        val dir = tempDir()
        val file = File(dir, "state.json")
        file.writeText("valid:target")
        AtomicStateFile.bakFile(file).writeText("valid:backup")
        AtomicStateFile.tmpFile(file).writeText("valid:attempt")
        assertTrue(AtomicStateFile.delete(file))
        assertFalse(file.exists())
        assertFalse(AtomicStateFile.bakFile(file).exists())
        assertFalse(AtomicStateFile.tmpFile(file).exists())
    }

    @Test
    fun saveFailsWhenParentIsAFile() {
        val dir = tempDir()
        val notADir = File(dir, "blocker")
        notADir.writeText("x")
        assertFalse(AtomicStateFile.save(File(notADir, "state.json"), "valid:x"))
    }
}
