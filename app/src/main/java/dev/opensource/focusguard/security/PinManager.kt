package dev.opensource.focusguard.security

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PIN hashing: PBKDF2-HmacSHA256, 120k iterations, 160-bit salt, 256-bit key.
 * Deliberately simple, local-only, no dependencies.
 */
object PinManager {

    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256

    fun newSalt(): String {
        val bytes = ByteArray(20)
        SecureRandom().nextBytes(bytes)
        return bytes.toHex()
    }

    fun hash(pin: String, salt: String): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt.toByteArray(Charsets.UTF_8), ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded.toHex()
    }

    fun matches(pin: String, salt: String, expectedHash: String): Boolean =
        hash(pin, salt) == expectedHash

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
