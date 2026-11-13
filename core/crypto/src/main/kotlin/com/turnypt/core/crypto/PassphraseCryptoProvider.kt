package com.turnypt.core.crypto

/**
 * Mode A provider: shared-passphrase communication.
 *
 * Per-message responsibilities:
 *  - draw a fresh 96-bit random nonce (never reused with the same key by
 *    construction, see PROTOCOL.md for the collision-budget analysis);
 *  - serialize the canonical header and use it as associated data so that
 *    every protocol field is authenticated;
 *  - seal with the context's cipher — AES-256-GCM (0x01) or ChaCha20-
 *    Poly1305 (0x02) since v0.6.0; the choice travels IN the envelope, so
 *    decryption is independent of any local preference.
 *
 * Key derivation is NOT performed here: keys are derived once per session
 * (contact-level, Argon2id) and supplied already unwrapped. This is a
 * deliberate performance and security decision documented in
 * docs/ARCHITECTURE.md.
 */
class PassphraseCryptoProvider(
    private val aeads: AeadCiphers = AeadCiphers(),
    private val random: ProtocolRandom = ProtocolRandom(RandomSource.SECURE),
) : CryptoProvider {

    override suspend fun encrypt(plaintext: ByteArray, context: EncryptionContext): EncryptionResult {
        if (context.mode != EncryptionMode.PASSPHRASE) {
            throw IllegalArgumentException("PassphraseCryptoProvider cannot serve mode ${context.mode}")
        }
        if (plaintext.size > context.maxPlaintextBytes) {
            return EncryptionResult.Failure(CryptoError.InputTooLarge)
        }
        if (context.key.size != context.algorithm.keyLengthBytes) {
            return EncryptionResult.Failure(CryptoError.InvalidKey)
        }
        if (context.salt.size != Argon2KeyDerivator.SALT_LENGTH_BYTES) {
            return EncryptionResult.Failure(CryptoError.InvalidSalt)
        }
        val message = EncryptedMessage(
            mode = EncryptionMode.PASSPHRASE,
            algorithm = context.algorithm,
            kdf = KdfAlgorithm.ARGON2ID,
            keyId = context.keyId.copyOf(),
            argon2Params = context.kdfParams,
            salt = context.salt.copyOf(),
            nonce = random.nonce(context.algorithm),
            ciphertext = ByteArray(0),
        )
        return try {
            val header = ProtocolHeader.serialize(message)
            val sealed = aeads.of(context.algorithm)
                .seal(context.key, message.nonce, plaintext, header)
            EncryptionResult.Success(message.copy(ciphertext = sealed))
        } catch (e: AeadFailure) {
            EncryptionResult.Failure(e.error)
        }
    }

    override suspend fun decrypt(message: EncryptedMessage, context: DecryptionContext): DecryptionResult {
        if (context.mode != EncryptionMode.PASSPHRASE) {
            return fail(CryptoError.UnsupportedMode(context.mode.id))
        }
        if (message.mode != EncryptionMode.PASSPHRASE) {
            return fail(CryptoError.UnsupportedMode(message.mode.id))
        }
        if (message.ciphertext.size > context.maxSealedBytes) {
            return fail(CryptoError.MessageTooLarge)
        }
        if (message.ciphertext.size < message.algorithm.tagLengthBytes) {
            return fail(CryptoError.MalformedMessage)
        }
        if (context.key.size != message.algorithm.keyLengthBytes) {
            return fail(CryptoError.InvalidKey)
        }
        return try {
            val header = ProtocolHeader.serialize(message)
            val plaintext = aeads.of(message.algorithm)
                .open(context.key, message.nonce, message.ciphertext, header)
            DecryptionResult.Success(plaintext)
        } catch (e: AeadFailure) {
            fail(e.error)
        }
    }

    private fun fail(error: CryptoError): DecryptionResult.Failure = DecryptionResult.Failure(error)
}
