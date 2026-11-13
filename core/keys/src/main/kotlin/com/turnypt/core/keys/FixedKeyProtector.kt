package com.turnypt.core.keys

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * KeyProtector backed by a single in-memory AES-256 key.
 *
 * TEST / BENCHMARK USE ONLY — a fixed key provides no at-rest protection;
 * it exists so that JVM unit tests of the repository layer can exercise the
 * wrap/verify/unwrap flow without Android Keystore. The production
 * implementation is [com.turnypt.security.storage.AndroidKeystoreKeyProtector]
 * (security:storage module).
 *
 * Wire format: nonce(12) || ciphertext||tag(16), AES-256-GCM, AAD = keyId —
 * the same construction the Keystore protector uses, so both are
 * format-compatible from the caller's point of view.
 */
class FixedKeyProtector(
    keyBytes: ByteArray? = null,
) : KeyProtector {

    private val key: ByteArray = keyBytes?.copyOf()?.also {
        require(it.size == 32) { "FixedKeyProtector requires a 256-bit key" }
    } ?: ByteArray(32).also { SECURE.nextBytes(it) }

    override fun wrap(plainKey: ByteArray, keyId: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_LENGTH).also { SECURE.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(keyId)
        return nonce + cipher.doFinal(plainKey)
    }

    override fun unwrap(wrapped: ByteArray, keyId: ByteArray): ByteArray {
        if (wrapped.size < NONCE_LENGTH + TAG_LENGTH) {
            throw KeyProtectionException("wrapped blob truncated")
        }
        val nonce = wrapped.copyOfRange(0, NONCE_LENGTH)
        val sealed = wrapped.copyOfRange(NONCE_LENGTH, wrapped.size)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(keyId)
            cipher.doFinal(sealed)
        } catch (e: GeneralSecurity) {
            throw KeyProtectionException("unwrap failed (authentication or keyId mismatch)", e)
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val NONCE_LENGTH = 12
        const val TAG_LENGTH = 16
        val SECURE = SecureRandom()
    }
}

// Local alias to keep the catch clause readable without importing the whole
// exception hierarchy of JCA at the top of the file.
private typealias GeneralSecurity = java.security.GeneralSecurityException
