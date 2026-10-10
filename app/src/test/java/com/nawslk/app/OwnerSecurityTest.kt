package com.nawslk.app

import org.junit.Assert.*
import org.junit.Test

class OwnerSecurityTest {
    @Test fun validPinMatchesAndWrongPinDoesNot() {
        val salt = OwnerSecurity.newSalt()
        val digest = OwnerSecurity.derive("123456".toCharArray(), salt)
        assertTrue(OwnerSecurity.verify("123456".toCharArray(), salt, digest))
        assertFalse(OwnerSecurity.verify("654321".toCharArray(), salt, digest))
    }

    @Test(expected = IllegalArgumentException::class)
    fun shortPinRejected() {
        OwnerSecurity.derive("123".toCharArray(), OwnerSecurity.newSalt())
    }

    @Test fun saltsDiffer() {
        assertNotEquals(OwnerSecurity.newSalt(), OwnerSecurity.newSalt())
    }
}
