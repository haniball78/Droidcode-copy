package com.droiddeck.launcher.core.privileged

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

object ShutdownRestore {
    private const val TAG = "SafetyShutdown"

    fun handle(context: Context, pending: BroadcastReceiver.PendingResult?) {
        val appContext = context.applicationContext
        val finished = AtomicBoolean(false)
        val finishOnce: () -> Unit = {
            if (finished.compareAndSet(false, true)) {
                runCatching { pending?.finish() }
            }
        }
        Thread({
            try {
                Thread.sleep(DeadMan.SHUTDOWN_DEADLINE_MS)
            } catch (_: InterruptedException) {
            }
            finishOnce()
        }, "safety-shutdown-deadline").start()
        Thread({
            val start = android.os.SystemClock.elapsedRealtime()
            com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(appContext, "safety.shutdown", "received")
            if (!Tweaks.recoveryNeeded(appContext)) {
                Log.i(TAG, "shutdown restore not needed, no journal")
                finishOnce()
                return@Thread
            }
            val ok = try {
                Tweaks.rollback(appContext)
            } catch (t: Throwable) {
                Log.w(TAG, "shutdown restore", t)
                false
            }
            val elapsed = android.os.SystemClock.elapsedRealtime() - start
            com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
                appContext, "safety.shutdown", if (ok) "rollback-finished" else "rollback-failed",
                fields = mapOf("duration_ms" to elapsed.toString()),
            )
            if (ok) {
                Log.i(TAG, "shutdown restore ok in ${elapsed}ms")
            } else {
                Log.w(TAG, "shutdown restore failed after ${elapsed}ms, journal kept")
            }
            finishOnce()
        }, "safety-shutdown").start()
    }
}

class SafetyShutdownReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val appContext = context?.applicationContext ?: return
        if (intent?.action != Intent.ACTION_SHUTDOWN) return
        ShutdownRestore.handle(appContext, goAsync())
    }
}
