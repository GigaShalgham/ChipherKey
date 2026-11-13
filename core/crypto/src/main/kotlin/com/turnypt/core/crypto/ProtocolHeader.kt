package com.turnypt.core.crypto

/**
 * Canonical binary serialization of the CK1 authenticated header.
 *
 * THIS LAYOUT IS THE AUTHENTICATED-ASSOCIATED-DATA (AAD) DEFINITION of the
 * CK1 protocol: every byte before the ciphertext is bound to the AEAD tag.
 * The same layout is mirrored in docs/PROTOCOL.md and MUST NOT change for
 * CK1; any change requires a new protocol version.
 *
 * v0.6.0: the cipher byte may now carry 0x01 (AES-256-GCM) or 0x02
 * (ChaCha20-Poly1305). Both share the 12-byte nonce / 16-byte tag geometry
 * (see [CipherAlgorithm.ck1Compatible]), so the header layout — and every
 * existing AES envelope — is byte-for-byte unchanged. Old builds reject
 * 0x02 with `UnsupportedAlgorithm`, the designed forward-compatible path.
 *
 * Layout (passphrase mode), all multi-byte integers big-endian:
 *
 * ```
 *  offset  size  field
 *  0       1     protocol version (0x01)
 *  1       1     encryption mode
 *  2       1     cipher algorithm id
 *  3       1     KDF algorithm id
 *  4       16    session key identifier (public)
 *  20      1     Argon2id memory exponent (log2 of KiB)
 *  21      4     Argon2id iterations
 *  25      2     Argon2id parallelism
 *  27      16    Argon2id salt
 *  43      12    AEAD nonce
 *  55      ...   ciphertext || tag
 * ```
 */
object ProtocolHeader {

    const val VERSION: Int = 0x01
    const val PASSPHRASE_HEADER_LENGTH: Int = 55

    fun serialize(message: EncryptedMessage): ByteArray {
        if (message.mode != EncryptionMode.PASSPHRASE) {
            throw AeadFailure(CryptoError.UnsupportedMode(message.mode.id))
        }
        val kdfParams = message.argon2Params ?: throw AeadFailure(CryptoError.MalformedMessage)
        val salt = message.salt ?: throw AeadFailure(CryptoError.MalformedMessage)
        if (salt.size != Argon2KeyDerivator.SALT_LENGTH_BYTES) throw AeadFailure(CryptoError.InvalidSalt)
        if (message.nonce.size != message.algorithm.nonceLengthBytes) throw AeadFailure(CryptoError.InvalidNonce)
        if (message.keyId.size != ProtocolRandom.KEY_ID_LENGTH_BYTES) throw AeadFailure(CryptoError.MalformedMessage)

        val out = ByteArray(PASSPHRASE_HEADER_LENGTH)
        out[0] = VERSION.toByte()
        out[1] = message.mode.id.toByte()
        out[2] = message.algorithm.id.toByte()
        out[3] = message.kdf.id.toByte()
        message.keyId.copyInto(out, 4)
        val kdfBytes = kdfParams.encode()
        kdfBytes.copyInto(out, 20)
        salt.copyInto(out, 27)
        message.nonce.copyInto(out, 43)
        return out
    }

    /**
     * Parses the fixed header and returns the message with ciphertext
     * attached. Rejects unknown versions, algorithms, modes and KDFs, and
     * any structural inconsistency — before any cryptographic work happens.
     */
    fun parse(payload: ByteArray): EncryptedMessage {
        if (payload.size < PASSPHRASE_HEADER_LENGTH + CipherAlgorithm.AES_256_GCM.tagLengthBytes) {
            throw AeadFailure(CryptoError.MalformedMessage)
        }
        val message = parseHeaderFields(payload, from = 0)
        val ciphertext = payload.copyOfRange(PASSPHRASE_HEADER_LENGTH, payload.size)
        if (ciphertext.size < message.algorithm.tagLengthBytes) {
            throw AeadFailure(CryptoError.MalformedMessage)
        }
        return message.copy(ciphertext = ciphertext)
    }

    /**
     * v0.6.0: parses ONLY the 55 header bytes at [from] (ciphertext stays
     * empty). Shared by the CK1 envelope parser and the CKF1 file prefix
     * (which keeps the identical 55-byte header after its 4-byte magic) —
     * one field decoder, one validation policy.
     */
    fun parseHeaderFields(payload: ByteArray, from: Int): EncryptedMessage {
        if (from < 0 || payload.size < from + PASSPHRASE_HEADER_LENGTH) {
            throw AeadFailure(CryptoError.MalformedMessage)
        }
        if ((payload[from].toInt() and 0xFF) != VERSION) {
            throw AeadFailure(CryptoError.UnsupportedVersion(payload[from].toInt() and 0xFF))
        }
        val mode = EncryptionMode.fromId(payload[from + 1].toInt() and 0xFF)
            ?: throw AeadFailure(CryptoError.UnsupportedMode(payload[from + 1].toInt() and 0xFF))
        val algorithm = CipherAlgorithm.fromId(payload[from + 2].toInt() and 0xFF)
            ?: throw AeadFailure(CryptoError.UnsupportedAlgorithm(payload[from + 2].toInt() and 0xFF))
        val kdf = KdfAlgorithm.fromId(payload[from + 3].toInt() and 0xFF)
            ?: throw AeadFailure(CryptoError.UnsupportedKdf(payload[from + 3].toInt() and 0xFF))

        if (mode != EncryptionMode.PASSPHRASE) {
            // Reserved for the public-key provider (Phase 5); no CK1 v1
            // message may use it.
            throw AeadFailure(CryptoError.UnsupportedMode(mode.id))
        }
        if (!algorithm.ck1Compatible) throw AeadFailure(CryptoError.UnsupportedAlgorithm(algorithm.id))
        if (kdf != KdfAlgorithm.ARGON2ID) throw AeadFailure(CryptoError.UnsupportedKdf(kdf.id))

        val keyId = payload.copyOfRange(from + 4, from + 20)
        val argon2Params = try {
            Argon2Params.decode(payload.copyOfRange(from + 20, from + 27))
        } catch (e: IllegalArgumentException) {
            throw AeadFailure(CryptoError.MalformedMessage, e)
        }
        val salt = payload.copyOfRange(from + 27, from + 43)
        val nonce = payload.copyOfRange(from + 43, from + 55)

        return EncryptedMessage(
            mode = mode,
            algorithm = algorithm,
            kdf = kdf,
            keyId = keyId,
            argon2Params = argon2Params,
            salt = salt,
            nonce = nonce,
            ciphertext = ByteArray(0),
        )
    }
}
