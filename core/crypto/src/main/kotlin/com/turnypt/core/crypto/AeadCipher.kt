package com.turnypt.core.crypto

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Authenticated encryption with associated data (AEAD).
 *
 * The JCA-backed implementation is used on both the JVM (unit tests) and
 * Android (production); the Android platform provider supplies the
 * AES-GCM implementation, which is hardware-accelerated on modern SoCs.
 */
interface AeadCipher {
    /** Returns ciphertext || tag. Throws [AeadFailure] when inputs are invalid. */
    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, associatedData: ByteArray): ByteArray

    /**
     * Verifies the tag and returns the plaintext.
     * Throws [AeadFailure] with [CryptoError.AuthenticationFailed] when the
     * tag does not verify — no plaintext is ever returned partially.
     */
    fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray, associatedData: ByteArray): ByteArray
}

class AeadFailure(val error: CryptoError, cause: Throwable? = null) : Exception(error.message, cause)

/** AES-256-GCM via JCA. 256-bit key, 96-bit nonce, 128-bit tag. */
class AesGcmAeadCipher : AeadCipher {

    override fun seal(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        if (key.size != CipherAlgorithm.AES_256_GCM.keyLengthBytes) throw AeadFailure(CryptoError.InvalidKey)
        if (nonce.size != CipherAlgorithm.AES_256_GCM.nonceLengthBytes) throw AeadFailure(CryptoError.InvalidNonce)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(associatedData)
            cipher.doFinal(plaintext)
        } catch (e: GeneralSecurityException) {
            throw AeadFailure(CryptoError.InputTooLarge, e)
        } catch (e: IllegalStateException) {
            throw AeadFailure(CryptoError.InputTooLarge, e)
        }
    }

    override fun open(
        key: ByteArray,
        nonce: ByteArray,
        sealed: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        if (key.size != CipherAlgorithm.AES_256_GCM.keyLengthBytes) throw AeadFailure(CryptoError.InvalidKey)
        if (nonce.size != CipherAlgorithm.AES_256_GCM.nonceLengthBytes) throw AeadFailure(CryptoError.InvalidNonce)
        val minimum = CipherAlgorithm.AES_256_GCM.tagLengthBytes
        if (sealed.size < minimum) throw AeadFailure(CryptoError.MalformedMessage)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(associatedData)
            cipher.doFinal(sealed)
        } catch (e: GeneralSecurityException) {
            // Includes AEADBadTagException: authentication failed. Nothing is
            // disclosed about *why*.
            throw AeadFailure(CryptoError.AuthenticationFailed, e)
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
