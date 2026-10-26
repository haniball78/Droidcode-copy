package com.droiddeck.launcher.session

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ExitReason {
    USER,
    NOTIFICATION,
    TASK_REMOVED,
    GUEST_ENDED,
    SAFETY,
    BRIDGE_LOST,
    SERVICE_DESTROYED,
}

enum class ExitState {
    IDLE,
    REQUESTED,
    TEARDOWN,
    RESTORING,
    RESTORE_FAILED,
    DONE,
}

internal class ExitGate {
    var state: ExitState = ExitState.IDLE
        private set
    var reason: ExitReason? = null
        private set

    fun request(reason: ExitReason): Boolean {
        if (state == ExitState.REQUESTED || state == ExitState.TEARDOWN || state == ExitState.RESTORING) return false
        if (state == ExitState.RESTORE_FAILED) return false
        state = ExitState.REQUESTED
        this.reason = reason
        return true
    }

    fun beginTeardown() {
        if (state == ExitState.IDLE) reason = ExitReason.GUEST_ENDED
        state = ExitState.TEARDOWN
    }

    fun beginRestore(): Boolean {
        if (state == ExitState.DONE) return false
        state = ExitState.RESTORING
        return true
    }

    fun restoreSucceeded() {
        state = ExitState.DONE
    }

    fun restoreFailed() {
        state = ExitState.RESTORE_FAILED
    }

    fun retryRestore(): Boolean {
        if (state != ExitState.RESTORE_FAILED) return false
        state = ExitState.RESTORING
        return true
    }

    fun reset() {
        state = ExitState.IDLE
        reason = null
    }
}

object SessionExit {
    private val gate = ExitGate()
    private val mutableState = MutableStateFlow(ExitState.IDLE)
    val stateFlow: StateFlow<ExitState> = mutableState.asStateFlow()

    val state: ExitState get() = gate.state
    val reason: ExitReason? get() = gate.reason
    fun isExiting(): Boolean = gate.state == ExitState.REQUESTED ||
        gate.state == ExitState.TEARDOWN || gate.state == ExitState.RESTORING

    fun request(context: Context, reason: ExitReason): Boolean {
        if (!SessionState.running) {
            SessionState.stopRequested = true
            return false
        }
        if (!gate.request(reason)) return false
        mutableState.value = gate.state
        SessionService.stop(context)
        return true
    }

    fun beginDirect(reason: ExitReason) {
        gate.request(reason)
        mutableState.value = gate.state
    }

    fun beginTeardown() {
        gate.beginTeardown()
        mutableState.value = gate.state
    }

    fun beginRestore(): Boolean {
        val ok = gate.beginRestore()
        mutableState.value = gate.state
        return ok
    }

    fun restoreSucceeded() {
        gate.restoreSucceeded()
        mutableState.value = gate.state
    }

    fun restoreFailed() {
        gate.restoreFailed()
        mutableState.value = gate.state
    }

    fun retryRestore(): Boolean {
        val ok = gate.retryRestore()
        mutableState.value = gate.state
        return ok
    }

    fun reset() {
        gate.reset()
        mutableState.value = gate.state
    }
}
