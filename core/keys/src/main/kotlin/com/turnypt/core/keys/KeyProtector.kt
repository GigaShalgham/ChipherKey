package com.turnypt.core.keys

import java.security.MessageDigest
import java.util.Locale

/**
 * Protects secret key material at rest.
 *
 * Implementations wrap (encrypt+authenticate) raw key bytes before they are
 * handed to persistence, and unwrap them only for the lifetime of a single
 * cryptographic operation. The production implementation is backed by the
 * Android Keystore (security:storage); tests use a local AES key.
 */
interface KeyProtector {
    /** Returns an opaque, authenticated blob safe for database storage. */
    fun wrap(plainKey: ByteArray, keyId: ByteArray): ByteArray

    /**
     * Recovers the raw key. Throws [KeyProtectionException] when the blob
     * fails authentication or the keyId does not match the one used at wrap
     * time (the keyId is bound as associated data).
     */
    fun unwrap(wrapped: ByteArray, keyId: ByteArray): ByteArray
}

class KeyProtectionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Human-comparable fingerprint of key material.
 *
 * SHA-256 over the raw key, formatted as six colon-separated groups of
 * decimal numbers (0-255 each), e.g. "072:199:003:...". Users compare this
 * out-of-band to confirm both sides derived the same session key.
 *
 * Digits are formatted with [Locale.ROOT] (deep-scan finding 9): with the
 * default locale, Persian-locale devices would render "۰۷۲"-style digits
 * and break text comparison of this out-of-band verifier.
 */
object KeyFingerprint {

    fun of(key: ByteArray): String =
        format(MessageDigest.getInstance("SHA-256").digest(key).takeLast(6))

    /** Fingerprint of a session identity (keyId + salt), for invite cards. */
    fun ofSession(keyId: ByteArray, salt: ByteArray): String =
        format(MessageDigest.getInstance("SHA-256").digest(keyId + salt).takeLast(6))

    private fun format(lastSixBytes: List<Byte>): String =
        lastSixBytes.joinToString(":") { String.format(Locale.ROOT, "%03d", it.toInt() and 0xFF) }
}
