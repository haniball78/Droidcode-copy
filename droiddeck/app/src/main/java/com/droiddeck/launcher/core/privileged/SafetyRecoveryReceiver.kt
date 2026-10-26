package com.droiddeck.launcher.core.privileged

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SafetyRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val appContext = context?.applicationContext ?: return
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED && intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        RecoveryCoordinator.attemptAsync(appContext)
    }
}
