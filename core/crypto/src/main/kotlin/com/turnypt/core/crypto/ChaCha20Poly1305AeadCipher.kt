package com.turnypt.core.crypto

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

/**
 * ChaCha20-Poly1305 AEAD (RFC 8439) via the BouncyCastle LIGHTWEIGHT API.
 *
 * v0.6.0: the second CK1 cipher (wire id 0x02). BouncyCastle is already a
 * core:crypto dependency (Argon2id) and — critically — the lightweight API
 * performs NO JCA provider insertion, so the Android platform providers are
 * never overridden (same discipline as the Argon2id usage). Running through
 * BC instead of the platform provider also means ONE implementation on
 * every API level (Conscrypt's ChaCha20-Poly1305 only exists from API 28+;
 * Turnypt supports minSdk 26) and identical behavior in JVM unit tests
 * and on device.
 *
 * ChaCha20 is an ARX stream cipher: no lookup tables, constant-time in
 * software by construction — the natural choice for devices without a
 * hardware AES unit (see AutomaticCipherSelector).
 *
 * A fresh engine instance is created per call: [ChaCha20Poly1305] is not
 * documented as thread-safe, and instantiation is cheap.
 */
class ChaCha20Poly1305AeadCipher : AeadCipher {

    override fun seal(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        validate(key, nonce)
        val engine = ChaCha20Poly1305()
        engine.init(
            true,
            AEADParameters(KeyParameter(key), TAG_BITS, nonce, associatedData),
        )
        return try {
            val out = ByteArray(engine.getOutputSize(plaintext.size))
            var written = engine.processBytes(plaintext, 0, plaintext.size, out, 0)
            written += engine.doFinal(out, written)
            if (written == out.size) out else out.copyOf(written)
        } catch (e: InvalidCipherTextException) {
            // Cannot happen on encrypt; kept for exhaustive mapping.
            throw AeadFailure(CryptoError.AuthenticationFailed, e)
        } catch (e: Exception) {
            throw AeadFailure(CryptoError.InputTooLarge, e)
        }
    }

    override fun open(
        key: ByteArray,
        nonce: ByteArray,
        sealed: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        validate(key, nonce)
        if (sealed.size < CipherAlgorithm.CHACHA20_POLY1305.tagLengthBytes) {
            throw AeadFailure(CryptoError.MalformedMessage)
        }
        val engine = ChaCha20Poly1305()
        engine.init(
            false,
            AEADParameters(KeyParameter(key), TAG_BITS, nonce, associatedData),
        )
        return try {
            val out = ByteArray(engine.getOutputSize(sealed.size))
            var written = engine.processBytes(sealed, 0, sealed.size, out, 0)
            written += engine.doFinal(out, written)
            if (written == out.size) out else out.copyOf(written)
        } catch (e: InvalidCipherTextException) {
            // Tag mismatch or malformed ciphertext: nothing is disclosed.
            throw AeadFailure(CryptoError.AuthenticationFailed, e)
        } catch (e: IllegalStateException) {
            throw AeadFailure(CryptoError.MalformedMessage, e)
        } catch (e: Exception) {
            throw AeadFailure(CryptoError.MalformedMessage, e)
        }
    }

    private fun validate(key: ByteArray, nonce: ByteArray) {
        if (key.size != CipherAlgorithm.CHACHA20_POLY1305.keyLengthBytes) {
            throw AeadFailure(CryptoError.InvalidKey)
        }
        if (nonce.size != CipherAlgorithm.CHACHA20_POLY1305.nonceLengthBytes) {
            throw AeadFailure(CryptoError.InvalidNonce)
        }
    }

    private companion object {
        const val TAG_BITS = 128
    }
}

/**
 * Registry of the CK1 AEAD implementations (v0.6.0). Both implementations
 * are stateless per call, so one registry instance serves the process.
 */
class AeadCiphers {

    /** The AEAD implementation for a wire [algorithm]. */
    fun of(algorithm: CipherAlgorithm): AeadCipher = when (algorithm) {
        CipherAlgorithm.AES_256_GCM -> AesGcmAeadCipher()
        CipherAlgorithm.CHACHA20_POLY1305 -> ChaCha20Poly1305AeadCipher()
    }
}
