package com.nawslk.app.calls

/** Deterministic call lifecycle; rejects invalid transitions. */
object CallStateMachine {
    private val allowed = mapOf(
        CallStatus.IDLE to setOf(CallStatus.RINGING, CallStatus.CONNECTING),
        CallStatus.RINGING to setOf(CallStatus.CONNECTING, CallStatus.REJECTED, CallStatus.ENDED),
        CallStatus.CONNECTING to setOf(CallStatus.ACTIVE, CallStatus.FAILED, CallStatus.ENDED),
        CallStatus.ACTIVE to setOf(CallStatus.ENDED, CallStatus.FAILED),
        CallStatus.ENDED to emptySet(),
        CallStatus.FAILED to emptySet(),
        CallStatus.REJECTED to emptySet()
    )
    fun transition(session: CallSession, next: CallStatus, atMs: Long): CallSession {
        require(next in allowed.getValue(session.status)) {
            "Invalid call transition: ${session.status} -> $next"
        }
        val terminal = next in setOf(CallStatus.ENDED, CallStatus.FAILED, CallStatus.REJECTED)
        return session.copy(status = next, endedAtMs = if (terminal) atMs else null)
    }
}
