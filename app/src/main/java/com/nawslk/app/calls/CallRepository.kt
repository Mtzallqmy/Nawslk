package com.nawslk.app.calls

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Offline demonstration call repository.
 * Does not make real calls or request telephony permissions.
 */
class CallRepository {
    private val mutableHistory = MutableStateFlow<List<CallSession>>(emptyList())
    val history: StateFlow<List<CallSession>> = mutableHistory

    fun startDemo(peer: String, now: Long = System.currentTimeMillis()): CallSession {
        require(peer.isNotBlank())
        val session = CallSession(
            id = UUID.randomUUID().toString(),
            peer = peer.trim(),
            direction = CallDirection.OUTGOING,
            transport = CallTransport.DEMO,
            status = CallStatus.CONNECTING,
            startedAtMs = now
        )
        mutableHistory.value = listOf(session) + mutableHistory.value
        return session
    }

    fun finish(id: String, now: Long = System.currentTimeMillis()): CallSession {
        val current = mutableHistory.value.first { it.id == id }
        val finished = CallStateMachine.transition(current, CallStatus.ENDED, now)
        mutableHistory.value = mutableHistory.value.map { if (it.id == id) finished else it }
        return finished
    }
}
