package com.droiddeck.launcher.core.privileged

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

internal object RecoveryRetry {
    val delaysMs: List<Long> = listOf(0L, 2000L, 5000L, 10000L)

    fun isIncreasing(): Boolean {
        return delaysMs.zipWithNext().all { (a, b) -> b > a }
    }
}

object RecoveryCoordinator {
    private const val TAG = "RecoveryCoordinator"
    private val running = AtomicBoolean(false)

    fun attemptAsync(context: Context) {
        val appContext = context.applicationContext
        if (!Tweaks.recoveryNeeded(appContext)) return
        if (!running.compareAndSet(false, true)) return
        Thread({
            try {
                attempt(appContext)
            } catch (t: Throwable) {
                Log.w(TAG, "recovery attempt failed", t)
            } finally {
                running.set(false)
            }
        }, "safety-recovery").start()
    }

    fun attemptOnBridgeReady(context: Context) {
        attemptAsync(context)
    }

    fun attempt(context: Context): Boolean {
        val appContext = context.applicationContext
        if (!Tweaks.recoveryNeeded(appContext)) return true
        for ((index, delayMs) in RecoveryRetry.delaysMs.withIndex()) {
            if (index > 0) {
                try {
                    Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            if (!Tweaks.recoveryNeeded(appContext)) return true
            if (Tweaks.rollback(appContext)) {
                runCatching {
                    PrivilegedBridge.executeChecked("settings delete global droiddeck_deadman_expired")
                }
                Log.i(TAG, "recovery succeeded on attempt ${index + 1}")
                return true
            }
            if (!PrivilegedBridge.isPrivilegedAvailable()) {
                Log.w(TAG, "recovery pending: privileged bridge not available, journal kept")
            } else {
                Log.w(TAG, "recovery attempt ${index + 1} failed, journal kept")
            }
        }
        Log.w(TAG, "recovery pending after ${RecoveryRetry.delaysMs.size} attempts, journal kept")
        return false
    }
}
