package com.nawslk.app

import org.junit.Assert.*
import org.junit.Test

class PinThrottleTest {
    @Test fun fiveFailuresTriggerLockout() {
        assertTrue(PinThrottle.canTry(4, 1000L, 1000L))
        assertFalse(PinThrottle.canTry(5, 1000L, 2000L))
        assertTrue(PinThrottle.canTry(5, 1000L, 61_000L))
    }
}
