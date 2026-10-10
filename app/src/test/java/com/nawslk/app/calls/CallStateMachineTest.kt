package com.nawslk.app.calls

import org.junit.Assert.*
import org.junit.Test

class CallStateMachineTest {
    private fun initial() = CallSession("id", "peer", CallDirection.OUTGOING,
        CallTransport.DEMO, CallStatus.CONNECTING, 1000L)

    @Test fun lifecycle() {
        val active = CallStateMachine.transition(initial(), CallStatus.ACTIVE, 1100L)
        val ended = CallStateMachine.transition(active, CallStatus.ENDED, 2000L)
        assertEquals(CallStatus.ENDED, ended.status)
        assertEquals(2000L, ended.endedAtMs)
    }
    @Test(expected = IllegalArgumentException::class)
    fun cannotReactivateEndedCall() {
        val ended = CallStateMachine.transition(initial(), CallStatus.ENDED, 2000L)
        CallStateMachine.transition(ended, CallStatus.ACTIVE, 2100L)
    }
}
