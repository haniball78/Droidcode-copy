package com.droiddeck.launcher.session

import com.droiddeck.launcher.core.privileged.AtomicStateFile
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SuspendStoreRecoveryTest {
    private fun session(): SuspendedSession {
        return SuspendedSession(7, "steam", SuspendKind.USER, 123L, false, "s1")
    }

    @Test
    fun loadRecoversFromBackupWhenTargetIsCorrupt() {
        val dir = Files.createTempDirectory("suspend-recovery-test").toFile()
        assertEquals(true, SuspendStore.save(dir, session()))
        File(dir, "suspended-session.json").writeText("not json")
        AtomicStateFile.bakFile(File(dir, "suspended-session.json"))
            .writeText(SuspendCodec.encode(session()))
        assertEquals(session(), SuspendStore.load(dir))
    }

    @Test
    fun loadReturnsNullWhenNothingSurvives() {
        val dir = Files.createTempDirectory("suspend-recovery-empty").toFile()
        assertNull(SuspendStore.load(dir))
    }

    @Test
    fun clearRemovesTargetBackupAndTemp() {
        val dir = Files.createTempDirectory("suspend-recovery-clear").toFile()
        SuspendStore.save(dir, session())
        val file = File(dir, "suspended-session.json")
        AtomicStateFile.bakFile(file).writeText(SuspendCodec.encode(session()))
        AtomicStateFile.tmpFile(file).writeText(SuspendCodec.encode(session()))
        assertEquals(true, SuspendStore.clear(dir))
        assertEquals(false, file.exists())
        assertEquals(false, AtomicStateFile.bakFile(file).exists())
        assertEquals(false, AtomicStateFile.tmpFile(file).exists())
    }
}
