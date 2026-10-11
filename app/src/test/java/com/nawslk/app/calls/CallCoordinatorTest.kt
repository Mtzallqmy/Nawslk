package com.nawslk.app.calls

import org.junit.Assert.*
import org.junit.Test

class CallCoordinatorTest {
    @Test fun sessionLifecycle() {
        var now = 1000L
        val coordinator = CallCoordinator { now }
        val started = coordinator.startDemo("test")
        assertEquals(CallStatus.CONNECTING, started.status)
        now = 1200L
        assertEquals(CallStatus.ACTIVE, coordinator.update(CallStatus.ACTIVE).status)
        now = 1500L
        val ended = coordinator.hangUp()
        assertEquals(1500L, ended.endedAtMs)
        assertNull(coordinator.current.value)
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsConcurrentCalls() {
        val coordinator = CallCoordinator()
        coordinator.startDemo("one")
        coordinator.startDemo("two")
    }
}
