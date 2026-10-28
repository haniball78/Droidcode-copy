package com.droiddeck.launcher.core.privileged

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

internal object RecoveryGeneration {
    fun isStale(itemGeneration: Int, sessionGen: Int): Boolean {
        return itemGeneration != 0 && itemGeneration != sessionGen
    }
}

internal object RecoveryRetry {
    val delaysMs: List<Long> = listOf(0L, 2000L, 5000L, 10000L)

    fun isIncreasing(): Boolean {
        return delaysMs.zipWithNext().all { (a, b) -> b > a }
    }
}

object RecoveryCoordinator {
    private const val TAG = "RecoveryCoordinator"
    private val running = AtomicBoolean(false)

    fun recoveryPending(context: Context): Boolean {
        val appContext = context.applicationContext
        if (GameModeEngine.hasLegacyMarker(appContext) || AppFreezerEngine.hasLegacyMarker(appContext)) return true
        return Tweaks.recoveryNeeded(appContext)
    }

    fun recoveryBlocksNewSession(context: Context, sessionGen: Int): Boolean {
        val appContext = context.applicationContext
        if (GameModeEngine.hasLegacyMarker(appContext) || AppFreezerEngine.hasLegacyMarker(appContext)) return true
        return Tweaks.journalSnapshot(appContext).any { RecoveryGeneration.isStale(it.generation, sessionGen) }
    }

    fun attemptAsync(context: Context) {
        val appContext = context.applicationContext
        if (!recoveryPending(appContext)) return
        if (!running.compareAndSet(false, true)) return
        Thread({
            try {
                val ok = attempt(appContext)
                DiagnosticsLog.log(appContext, "safety.restore", if (ok) "recovery-finished" else "recovery-failed")
            } catch (t: Throwable) {
                Log.w(TAG, "recovery attempt failed", t)
                DiagnosticsLog.log(appContext, "safety.restore", "recovery-failed", fields = mapOf("reason" to (t.javaClass.simpleName)))
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
        if (!recoveryPending(appContext)) return true
        if (!GameModeEngine.migrateLegacyToJournal(appContext)) {
            Log.w(TAG, "recovery pending: game mode legacy migration failed, journal kept")
            return false
        }
        if (!AppFreezerEngine.migrateLegacyToJournal(appContext)) {
            Log.w(TAG, "recovery pending: freezer legacy migration failed, journal kept")
            return false
        }
        for ((index, delayMs) in RecoveryRetry.delaysMs.withIndex()) {
            if (index > 0) {
                try {
                    Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            if (!Tweaks.recoveryNeeded(appContext)) {
                GameModeEngine.deleteLegacyMarker(appContext)
                AppFreezerEngine.deleteLegacyMarker(appContext)
                return true
            }
            if (Tweaks.rollback(appContext)) {
                runCatching {
                    PrivilegedBridge.ensureSessionToken(appContext)
                    PrivilegedBridge.executeChecked("settings delete global droiddeck_deadman_expired")
                }
                GameModeEngine.deleteLegacyMarker(appContext)
                AppFreezerEngine.deleteLegacyMarker(appContext)
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
