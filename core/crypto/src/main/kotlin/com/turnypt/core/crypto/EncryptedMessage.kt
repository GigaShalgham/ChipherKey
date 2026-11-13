package com.turnypt.core.crypto

/**
 * The fully parsed CK1 message, mode-agnostic.
 *
 * For passphrase mode all header fields are present; the public-key mode
 * (reserved) will populate mode-specific fields when implemented.
 */
data class EncryptedMessage(
    val mode: EncryptionMode,
    val algorithm: CipherAlgorithm,
    val kdf: KdfAlgorithm,
    /** Public session identifier used to locate the matching local key. */
    val keyId: ByteArray,
    val argon2Params: Argon2Params?,
    val salt: ByteArray?,
    val nonce: ByteArray,
    /** Ciphertext including the appended AEAD tag. */
    val ciphertext: ByteArray,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = varargDeepHashCode()
}

internal fun varargDeepHashCode(vararg values: Any?): Int = values.contentHashCode()

/** Concatenation helper used by tests and the encoder. */
fun EncryptedMessage.toByteArray(): ByteArray {
    val header = ProtocolHeader.serialize(this)
    return header + ciphertext
}
