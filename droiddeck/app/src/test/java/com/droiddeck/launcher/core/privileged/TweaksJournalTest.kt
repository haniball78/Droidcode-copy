package com.droiddeck.launcher.core.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TweaksJournalTest {
    private fun settingItem(
        key: String,
        original: String?,
        generation: Int = 7,
    ): JournalItem {
        val restore = if (original == null) {
            "settings delete global $key 2>/dev/null"
        } else {
            "settings put global $key $original 2>/dev/null"
        }
        return JournalItem(
            key = key,
            original = original,
            restoreCmd = restore,
            verifyCmd = "settings get global $key",
            verifyMode = JournalVerifyMode.VALUE,
            persistent = true,
            generation = generation,
        )
    }

    @Test
    fun codecRoundTripsNullOriginalAndEscapedCommands() {
        val items = listOf(
            settingItem("background_process_limit", null),
            JournalItem(
                key = "fixed-performance",
                original = "false",
                restoreCmd = "cmd power set-fixed-performance-mode-enabled false 2>/dev/null",
                verifyCmd = null,
                verifyMode = JournalVerifyMode.UNVERIFIED,
                persistent = false,
                generation = 7,
            ),
            JournalItem(
                key = "we\"ird\\key",
                original = "a\nb",
                restoreCmd = "echo \"a\\b\"",
                verifyCmd = "echo x",
                verifyMode = JournalVerifyMode.VALUE,
                persistent = true,
                generation = 9,
            ),
        )
        val decoded = JournalCodec.decode(JournalCodec.encode(items))
        assertEquals(items, decoded)
    }

    @Test
    fun codecRejectsCorruptInputs() {
        assertNull(JournalCodec.decode(""))
        assertNull(JournalCodec.decode("{\"version\":1,\"items\":["))
        assertNull(JournalCodec.decode("{\"version\":2,\"items\":[]}"))
        assertNull(JournalCodec.decode("{\"version\":1,\"items\":[{\"key\":\"x\"}]}"))
        assertNull(JournalCodec.decode("{\"version\":1,\"items\":[]} trailing"))
        assertNull(JournalCodec.decode("not json"))
        val missingMode = JournalCodec.encode(listOf(settingItem("background_process_limit", "8")))
            .replace("\"mode\":\"value\"", "\"mode\":\"bogus\"")
        assertNull(JournalCodec.decode(missingMode))
        val missingVerify = JournalCodec.encode(listOf(settingItem("background_process_limit", "8")))
            .replace("\"verify\":\"settings get global background_process_limit\"", "\"verify\":null")
        assertNull(JournalCodec.decode(missingVerify))
    }

    @Test
    fun codecRoundTripsEmptyJournal() {
        assertEquals(emptyList<JournalItem>(), JournalCodec.decode(JournalCodec.encode(emptyList())))
    }

    @Test
    fun rollbackRunsInReverseOrder() {
        val items = listOf(
            settingItem("a_key", "1"),
            settingItem("b_key", "2"),
            settingItem("c_key", "3"),
        )
        val order = mutableListOf<String>()
        val actual = mapOf("a_key" to "1", "b_key" to "2", "c_key" to "3")
        val result = JournalRollback.rollback(
            items,
            execute = { cmd ->
                order.add(cmd)
                0
            },
            readVerified = { item -> actual[item.key] },
        )
        assertTrue(result.success)
        assertTrue(result.remaining.isEmpty())
        assertNull(result.failedKey)
        assertEquals(
            listOf(
                "settings put global c_key 3 2>/dev/null",
                "settings put global b_key 2 2>/dev/null",
                "settings put global a_key 1 2>/dev/null",
            ),
            order,
        )
    }

    @Test
    fun rollbackStopsAtFailedItemAndKeepsEarlierItems() {
        val items = listOf(
            settingItem("a_key", "1"),
            settingItem("b_key", "2"),
            settingItem("c_key", "3"),
        )
        val executed = mutableListOf<String>()
        val result = JournalRollback.rollback(
            items,
            execute = { cmd ->
                executed.add(cmd)
                if (cmd.contains("b_key")) 1 else 0
            },
            readVerified = { item -> item.original },
        )
        assertFalse(result.success)
        assertEquals("b_key", result.failedKey)
        assertEquals(listOf(items[0], items[1]), result.remaining)
        assertFalse(executed.any { it.contains("a_key") })
        assertTrue(executed.any { it.contains("c_key") })
        assertTrue(executed.any { it.contains("b_key") })
    }

    @Test
    fun rollbackFailsOnVerifyMismatchAndKeepsItem() {
        val items = listOf(settingItem("background_process_limit", "8"))
        val result = JournalRollback.rollback(
            items,
            execute = { 0 },
            readVerified = { "4" },
        )
        assertFalse(result.success)
        assertEquals(listOf(items[0]), result.remaining)
        assertEquals("background_process_limit", result.failedKey)
    }

    @Test
    fun rollbackAcceptsAbsentValueWhenOriginalIsNull() {
        val items = listOf(settingItem("background_process_limit", null))
        val result = JournalRollback.rollback(
            items,
            execute = { 0 },
            readVerified = { "null" },
        )
        assertTrue(result.success)
        assertTrue(result.remaining.isEmpty())
    }

    @Test
    fun rollbackExitModeDoesNotConsultVerify() {
        val item = JournalItem(
            key = "deep-doze",
            original = null,
            restoreCmd = "cmd deviceidle unforce 2>/dev/null",
            verifyCmd = null,
            verifyMode = JournalVerifyMode.UNVERIFIED,
            persistent = true,
            generation = 7,
        )
        val result = JournalRollback.rollback(
            listOf(item),
            execute = { 0 },
            readVerified = { throw AssertionError("verify must not be consulted in UNVERIFIED mode") },
        )
        assertTrue(result.success)
    }
}
