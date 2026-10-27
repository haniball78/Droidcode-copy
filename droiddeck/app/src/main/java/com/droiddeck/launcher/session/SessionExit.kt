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
    private val lock = Any()

    var state: ExitState = ExitState.IDLE
        get() = synchronized(lock) { field }
        private set
    var reason: ExitReason? = null
        get() = synchronized(lock) { field }
        private set
    var generation: Int = 0
        get() = synchronized(lock) { field }
        private set

    private fun generationMatches(generation: Int): Boolean {
        return generation == 0 || this.generation == 0 || generation == this.generation
    }

    private fun claimGeneration(generation: Int) {
        if (generation != 0) this.generation = generation
    }

    fun request(reason: ExitReason, generation: Int = 0): Boolean {
        return synchronized(lock) {
            if (state != ExitState.IDLE && state != ExitState.DONE) return@synchronized false
            if (!generationMatches(generation)) return@synchronized false
            claimGeneration(generation)
            state = ExitState.REQUESTED
            this.reason = reason
            true
        }
    }

    fun beginTeardown(generation: Int = 0) {
        synchronized(lock) {
            if (state == ExitState.TEARDOWN || state == ExitState.RESTORING) return
            if (!generationMatches(generation)) return
            claimGeneration(generation)
            if (state == ExitState.IDLE) reason = ExitReason.GUEST_ENDED
            state = ExitState.TEARDOWN
        }
    }

    fun beginRestore(generation: Int = 0): Boolean {
        return synchronized(lock) {
            if (state != ExitState.TEARDOWN && state != ExitState.REQUESTED) return@synchronized false
            if (!generationMatches(generation)) return@synchronized false
            state = ExitState.RESTORING
            true
        }
    }

    fun restoreSucceeded() {
        synchronized(lock) {
            if (state != ExitState.RESTORING) return
            state = ExitState.DONE
        }
    }

    fun restoreFailed() {
        synchronized(lock) {
            if (state != ExitState.RESTORING) return
            state = ExitState.RESTORE_FAILED
        }
    }

    fun retryRestore(): Boolean {
        return synchronized(lock) {
            if (state != ExitState.RESTORE_FAILED) return@synchronized false
            state = ExitState.RESTORING
            true
        }
    }

    fun reset() {
        synchronized(lock) {
            state = ExitState.IDLE
            reason = null
            generation = 0
        }
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

    fun request(context: Context, reason: ExitReason, generation: Int = 0): Boolean {
        com.droiddeck.launcher.core.privileged.DiagnosticsLog.attach(context)
        if (!SessionState.running) {
            SessionState.stopRequested = true
            return false
        }
        if (!gate.request(reason, generation)) return false
        mutableState.value = gate.state
        com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
            context, "safety.exit", "requested",
            fields = mapOf("reason" to reason.name, "generation" to generation.toString()),
        )
        SessionService.stop(context)
        return true
    }

    fun beginDirect(reason: ExitReason, generation: Int = 0) {
        gate.request(reason, generation)
        mutableState.value = gate.state
        com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
            "safety.exit", "requested",
            fields = mapOf("reason" to reason.name, "generation" to generation.toString()),
        )
    }

    fun beginTeardown(generation: Int = 0) {
        gate.beginTeardown(generation)
        mutableState.value = gate.state
        com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
            "safety.exit", "teardown-started", fields = mapOf("generation" to generation.toString()),
        )
    }

    fun beginRestore(generation: Int = 0): Boolean {
        val ok = gate.beginRestore(generation)
        mutableState.value = gate.state
        return ok
    }

    fun restoreSucceeded() {
        gate.restoreSucceeded()
        mutableState.value = gate.state
        com.droiddeck.launcher.core.privileged.DiagnosticsLog.log("safety.exit", "restore-done")
    }

    fun restoreFailed() {
        gate.restoreFailed()
        mutableState.value = gate.state
        com.droiddeck.launcher.core.privileged.DiagnosticsLog.log("safety.exit", "restore-failed")
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
