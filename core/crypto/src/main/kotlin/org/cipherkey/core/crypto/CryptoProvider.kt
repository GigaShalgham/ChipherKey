package org.cipherkey.core.crypto

/**
 * High-level cryptographic provider contract.
 *
 * Implementations MUST:
 *  - never return partially decrypted or unauthenticated plaintext;
 *  - authenticate all protocol metadata (associated data) alongside the
 *    ciphertext;
 *  - reject unsupported algorithms/versions before doing any crypto work.
 */
interface CryptoProvider {

    suspend fun encrypt(plaintext: ByteArray, context: EncryptionContext): EncryptionResult

    suspend fun decrypt(message: EncryptedMessage, context: DecryptionContext): DecryptionResult
}

/**
 * Result of an encryption attempt. On failure only the error is available —
 * no partially built message is ever returned.
 */
sealed class EncryptionResult {
    data class Success(val message: EncryptedMessage) : EncryptionResult()

    data class Failure(val error: CryptoError) : EncryptionResult()
}

/** Parameters needed to encrypt for a session. */
data class EncryptionContext(
    val mode: EncryptionMode,
    /** Raw session key (already derived); its lifetime is the caller's duty. */
    val key: ByteArray,
    val keyId: ByteArray,
    val salt: ByteArray,
    val kdfParams: Argon2Params,
    /** Hard upper bound on accepted plaintext size. */
    val maxPlaintextBytes: Int,
    /**
     * v0.6.0: the cipher this message is sealed with. Default keeps every
     * existing caller on AES-256-GCM — the algorithm is recorded in the
     * envelope, so old and new messages coexist freely.
     */
    val algorithm: CipherAlgorithm = CipherAlgorithm.AES_256_GCM,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = varargDeepHashCode(key, keyId, salt, kdfParams)
}

/** Parameters needed to attempt a decryption. */
data class DecryptionContext(
    val mode: EncryptionMode,
    /** Raw session key believed to match [EncryptedMessage.keyId]. */
    val key: ByteArray,
    val maxSealedBytes: Int,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = varargDeepHashCode(key)
}

/**
 * Result of a decryption attempt. On failure only the error is available —
 * never plaintext, never a partial result.
 */
sealed class DecryptionResult {
    data class Success(val plaintext: ByteArray) : DecryptionResult() {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = plaintext.contentHashCode()
    }

    data class Failure(val error: CryptoError) : DecryptionResult()
}
