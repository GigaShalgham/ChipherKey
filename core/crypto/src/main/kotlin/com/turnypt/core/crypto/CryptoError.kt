package com.turnypt.core.crypto

/**
 * Cryptographic failure taxonomy.
 *
 * Errors deliberately carry no sensitive material (no key bytes, no
 * plaintext fragments) and intentionally do not distinguish *why*
 * authentication failed beyond what the protocol needs.
 */
sealed class CryptoError(open val message: String) {

    /** The AEAD authentication tag did not verify. */
    data object AuthenticationFailed : CryptoError("Authentication failed")

    /** The envelope references an algorithm this build does not implement. */
    data class UnsupportedAlgorithm(val id: Int) : CryptoError("Unsupported algorithm id $id")

    /** The envelope references a KDF this build does not implement. */
    data class UnsupportedKdf(val id: Int) : CryptoError("Unsupported KDF id $id")

    /** The envelope references an encryption mode this build does not implement. */
    data class UnsupportedMode(val id: Int) : CryptoError("Unsupported mode id $id")

    /** The envelope protocol version is newer/unknown. */
    data class UnsupportedVersion(val version: Int) : CryptoError("Unsupported protocol version $version")

    /** The envelope is structurally malformed (bad lengths, truncation...). */
    data object MalformedMessage : CryptoError("Malformed message")

    /** Nonce length does not match the algorithm requirements. */
    data object InvalidNonce : CryptoError("Invalid nonce")

    /** Salt length does not match the KDF requirements. */
    data object InvalidSalt : CryptoError("Invalid salt")

    /** The supplied key does not satisfy the algorithm requirements. */
    data object InvalidKey : CryptoError("Invalid key")

    /** Plaintext exceeds the maximum permitted size. */
    data object InputTooLarge : CryptoError("Input too large")

    /** The encoded message exceeds the maximum permitted envelope size. */
    data object MessageTooLarge : CryptoError("Message too large")

    /**
     * v0.6.0 (CKF1): the sealed chunk stream ended without a final chunk —
     * the file was cut short after the last full-size chunk.
     */
    data object FileTruncated : CryptoError("Encrypted file is truncated")

    /** v0.6.0 (CKF1): the input does not start with the CKF1 magic. */
    data object NotACipherFile : CryptoError("Not a Turnypt encrypted file")
}
