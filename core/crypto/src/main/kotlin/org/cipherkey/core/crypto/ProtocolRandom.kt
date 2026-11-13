package org.cipherkey.core.crypto

import java.security.SecureRandom

/**
 * Source of cryptographic randomness.
 *
 * An interface (rather than a direct [SecureRandom] reference) so that
 * deterministic tests can inject a fixed sequence.
 */
fun interface RandomSource {
    fun nextBytes(count: Int): ByteArray

    companion object {
        val SECURE: RandomSource = RandomSource { count ->
            ByteArray(count).also { SECURE_RANDOM.nextBytes(it) }
        }

        private val SECURE_RANDOM = SecureRandom()
    }
}

/** Generates the values the protocol needs from a [RandomSource]. */
class ProtocolRandom(private val source: RandomSource) {

    /**
     * 96-bit AEAD nonce. Statistically unique per key; see PROTOCOL.md.
     * v0.6.0: sized by the target [algorithm] (every CK1 v1 cipher uses
     * 96 bits today; the parameter keeps the generator honest if that
     * ever changes).
     */
    fun nonce(algorithm: CipherAlgorithm = CipherAlgorithm.AES_256_GCM): ByteArray =
        source.nextBytes(algorithm.nonceLengthBytes)

    /** 128-bit Argon2id salt; one per passphrase-derived session key. */
    fun salt(): ByteArray = source.nextBytes(Argon2KeyDerivator.SALT_LENGTH_BYTES)

    /** 128-bit session key identifier; public, not secret. */
    fun keyId(): ByteArray = source.nextBytes(KEY_ID_LENGTH_BYTES)

    companion object {
        const val KEY_ID_LENGTH_BYTES = 16
    }
}
