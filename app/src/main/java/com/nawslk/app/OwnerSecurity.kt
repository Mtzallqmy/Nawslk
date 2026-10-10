package com.nawslk.app

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import java.util.Base64

/** Local PIN verification only. A local PIN never authorizes remote backend access. */
object OwnerSecurity {
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256

    fun newSalt(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun derive(pin: CharArray, saltBase64: String): String {
        require(pin.size in 6..12 && pin.all { it.isDigit() }) { "PIN must contain 6 to 12 digits" }
        val salt = Base64.getDecoder().decode(saltBase64)
        val spec = PBEKeySpec(pin, salt, ITERATIONS, KEY_BITS)
        return try {
            Base64.getEncoder().encodeToString(
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            )
        } finally {
            spec.clearPassword()
        }
    }

    fun verify(pin: CharArray, salt: String, expected: String): Boolean {
        val actual = derive(pin, salt)
        return java.security.MessageDigest.isEqual(
            Base64.getDecoder().decode(actual),
            Base64.getDecoder().decode(expected)
        )
    }
}
