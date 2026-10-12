package com.nawslk.app.messaging
import org.junit.Assert.*
import org.junit.Test
class MessageRulesTest {
    @Test fun contentAndDestinationsAreBounded() {
        assertTrue(MessageRules.validDestination("+967700000001"));assertFalse(MessageRules.validDestination("7000"));assertFalse(MessageRules.validDestination("+0012345678"))
        assertTrue(MessageRules.validBody("مرحبا"));assertFalse(MessageRules.validBody(" "));assertFalse(MessageRules.validBody("🙂".repeat(1601)));assertFalse(MessageRules.validBody("text\u0000"))
    }
    @Test fun deliveredAndReleasedStatesCannotBeReopened() {
        assertEquals(MessageStatus.SENT,MessageRules.transition(MessageStatus.SENDING,MessageStatus.SENT))
        assertThrows(IllegalArgumentException::class.java){MessageRules.transition(MessageStatus.DELIVERED,MessageStatus.SENDING)}
        assertThrows(IllegalArgumentException::class.java){MessageRules.transition(NumberStatus.RELEASED,NumberStatus.ACTIVE)}
        assertEquals(NumberStatus.RESERVED,MessageRules.transition(NumberStatus.AVAILABLE,NumberStatus.RESERVED))
    }
}
