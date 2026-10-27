package com.droiddeck.launcher.core.privileged

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeviceBaselineOnceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clean() {
        context.getSharedPreferences("device-baseline", Context.MODE_PRIVATE).edit().clear().apply()
    }

    @Test
    fun secondCaptureAfterSuccessChangesNothing() {
        context.getSharedPreferences("device-baseline", Context.MODE_PRIVATE).edit()
            .putBoolean("baseline_captured", true)
            .putString("baseline_file", "device-baseline-20260101-000000.md")
            .putLong("baseline_time_ms", 123L)
            .apply()
        DeviceBaselineCapture.captureViaBridge(context)
        val info = DeviceBaselineCapture.info(context)
        assertTrue(info.captured)
        assertEquals("device-baseline-20260101-000000.md", info.fileName)
        assertEquals(123L, info.capturedAtMs)
    }

    @Test
    fun ensureAfterPairingAfterSuccessChangesNothing() {
        context.getSharedPreferences("device-baseline", Context.MODE_PRIVATE).edit()
            .putBoolean("baseline_captured", true)
            .putString("baseline_file", "device-baseline-20260101-000000.md")
            .putLong("baseline_time_ms", 123L)
            .apply()
        DeviceBaselineCapture.ensureAfterPairing(context, "127.0.0.1", 1)
        val info = DeviceBaselineCapture.info(context)
        assertTrue(info.captured)
        assertEquals("device-baseline-20260101-000000.md", info.fileName)
        assertEquals(123L, info.capturedAtMs)
    }

    @Test
    fun failedCaptureLeavesFlagUnsetForRetry() {
        val result = DeviceBaselineCapture.ensureAfterPairing(context, "127.0.0.1", 1)
        assertNull(result)
        assertFalse(DeviceBaselineCapture.info(context).captured)
    }
}
