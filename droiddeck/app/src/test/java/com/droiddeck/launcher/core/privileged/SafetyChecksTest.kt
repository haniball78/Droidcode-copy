package com.droiddeck.launcher.core.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyChecksTest {
    @Test
    fun cleanBackgroundLimitRejectsGovernorValues() {
        assertFalse(SafetyChecks.isCleanBackgroundLimit(null))
        assertFalse(SafetyChecks.isCleanBackgroundLimit("2"))
        assertFalse(SafetyChecks.isCleanBackgroundLimit("4"))
        assertTrue(SafetyChecks.isCleanBackgroundLimit("8"))
        assertTrue(SafetyChecks.isCleanBackgroundLimit("0"))
    }

    @Test
    fun restoreVerifiedRequiresExitCodeAndReadback() {
        assertTrue(SafetyChecks.restoreVerified(0, "8", "8"))
        assertTrue(SafetyChecks.restoreVerified(0, null, null))
        assertFalse(SafetyChecks.restoreVerified(-1, "8", "8"))
        assertFalse(SafetyChecks.restoreVerified(0, "8", "4"))
        assertFalse(SafetyChecks.restoreVerified(0, null, "4"))
    }

    @Test
    fun whitelistParsingMatchesPackageLine() {
        val output = "system apps:\n  com.droiddeck.launcher\n  com.example.other\n"
        assertTrue(SafetyChecks.isWhitelisted(output, "com.droiddeck.launcher"))
        assertFalse(SafetyChecks.isWhitelisted(output, "com.droiddeck.launcher2"))
        assertFalse(SafetyChecks.isWhitelisted("", "com.droiddeck.launcher"))
    }

    @Test
    fun appOpsParsingRequiresAllow() {
        assertTrue(SafetyChecks.isBackgroundAllowed("RUN_IN_BACKGROUND: allow"))
        assertFalse(SafetyChecks.isBackgroundAllowed("RUN_IN_BACKGROUND: ignore"))
        assertFalse(SafetyChecks.isBackgroundAllowed(""))
    }

    @Test
    fun gameModeMarkerRoundTripsTurboOriginals() {
        val marker = GameModeMarker("8", "0", "1,0", "1")
        val decoded = SafetyChecks.decodeGameModeMarker(SafetyChecks.encodeGameModeMarker(marker))
        assertEquals(marker, decoded)
    }

    @Test
    fun oldSingleLineMarkerStillDecodesAsBackgroundLimit() {
        val decoded = SafetyChecks.decodeGameModeMarker("8")
        assertEquals("8", decoded.backgroundLimit)
        assertEquals(null, decoded.semEnhanced)
    }

    @Test
    fun freezerMarkerRoundTripsDozeOnlySession() {
        val encoded = SafetyChecks.encodeFreezerMarker(emptyList(), true)
        val (packages, doze) = SafetyChecks.decodeFreezerMarker(encoded)
        assertTrue(packages.isEmpty())
        assertTrue(doze)
        assertTrue(SafetyChecks.isDozeMarker(encoded))
    }

    @Test
    fun parseBackgroundLimitTrimsAndTreatsNullAsAbsent() {
        assertEquals("8", SafetyChecks.parseBackgroundLimit("  8\n"))
        assertEquals(null, SafetyChecks.parseBackgroundLimit(""))
        assertEquals(null, SafetyChecks.parseBackgroundLimit("null"))
        assertEquals(null, SafetyChecks.parseBackgroundLimit("  null  "))
    }

    @Test
    fun freezerMarkerWithoutDozeDecodesAsDozeFalse() {
        val encoded = SafetyChecks.encodeFreezerMarker(listOf("com.example.a"), false)
        val (packages, doze) = SafetyChecks.decodeFreezerMarker(encoded)
        assertEquals(listOf("com.example.a"), packages)
        assertFalse(doze)
        assertFalse(SafetyChecks.isDozeMarker(encoded))
    }

    @Test
    fun freezerMarkerRoundTripsPackagesAndDoze() {
        val encoded = SafetyChecks.encodeFreezerMarker(listOf("com.example.a", "com.example.b"), true)
        val (packages, doze) = SafetyChecks.decodeFreezerMarker(encoded)
        assertEquals(listOf("com.example.a", "com.example.b"), packages)
        assertTrue(doze)
    }
}
