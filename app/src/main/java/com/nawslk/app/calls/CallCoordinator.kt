package com.nawslk.app.calls

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Owns the local call lifecycle. Transport adapters will drive connecting/active
 * transitions; starting a session does not imply a real call was placed.
 */
class CallCoordinator(private val clock: () -> Long = System::currentTimeMillis) {
    private val mutableCurrent = MutableStateFlow<CallSession?>(null)
    val current: StateFlow<CallSession?> = mutableCurrent

    fun startDemo(peer: String): CallSession {
        require(peer.isNotBlank())
        check(mutableCurrent.value == null) { "A call is already in progress" }
        return CallSession(
            id = UUID.randomUUID().toString(),
            peer = peer.trim(),
            direction = CallDirection.OUTGOING,
            transport = CallTransport.DEMO,
            status = CallStatus.CONNECTING,
            startedAtMs = clock()
        ).also { mutableCurrent.value = it }
    }

    fun update(next: CallStatus): CallSession {
        val current = checkNotNull(mutableCurrent.value) { "No active session" }
        return CallStateMachine.transition(current, next, clock()).also {
            mutableCurrent.value = if (it.status in terminal) null else it
        }
    }

    fun hangUp(): CallSession = update(CallStatus.ENDED)

    companion object {
        private val terminal = setOf(CallStatus.ENDED, CallStatus.FAILED, CallStatus.REJECTED)
    }
}
