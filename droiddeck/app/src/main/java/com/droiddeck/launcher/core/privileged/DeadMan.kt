package com.droiddeck.launcher.core.privileged

enum class DeadManState {
    DISARMED,
    ARMED,
    EXPIRED,
}

class DeadMan {
    var state: DeadManState = DeadManState.DISARMED
        private set
    var lastBeatMs: Long = -1L
        private set

    fun arm(nowMs: Long): Boolean {
        state = DeadManState.ARMED
        lastBeatMs = nowMs
        return true
    }

    fun beat(nowMs: Long): Boolean {
        if (state != DeadManState.ARMED) return false
        lastBeatMs = nowMs
        return true
    }

    fun disarm(): Boolean {
        state = DeadManState.DISARMED
        lastBeatMs = -1L
        return true
    }

    fun isExpired(nowMs: Long): Boolean {
        return state == DeadManState.EXPIRED ||
            (state == DeadManState.ARMED && nowMs - lastBeatMs > WATCHDOG_MS)
    }

    fun checkExpiry(nowMs: Long): Boolean {
        if (state == DeadManState.ARMED && nowMs - lastBeatMs > WATCHDOG_MS) {
            state = DeadManState.EXPIRED
            return true
        }
        return false
    }

    companion object {
        const val BEAT_INTERVAL_MS = 10000L
        const val WATCHDOG_MS = 45000L
        const val SHUTDOWN_DEADLINE_MS = 2000L
    }
}

object ShutdownDeadline {
    fun withinDeadline(startMs: Long, nowMs: Long): Boolean {
        return nowMs - startMs <= DeadMan.SHUTDOWN_DEADLINE_MS
    }

    fun remainingMs(startMs: Long, nowMs: Long): Long {
        return (DeadMan.SHUTDOWN_DEADLINE_MS - (nowMs - startMs)).coerceAtLeast(0L)
    }
}
