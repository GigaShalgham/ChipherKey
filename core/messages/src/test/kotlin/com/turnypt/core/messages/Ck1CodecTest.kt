package com.turnypt.core.messages

import com.turnypt.core.crypto.Argon2Params
import com.turnypt.core.crypto.Argon2KeyDerivator
import com.turnypt.core.crypto.AeadCiphers
import com.turnypt.core.crypto.CipherAlgorithm
import com.turnypt.core.crypto.EncryptedMessage
import com.turnypt.core.crypto.EncryptionMode
import com.turnypt.core.crypto.KdfAlgorithm
import com.turnypt.core.crypto.PassphraseCryptoProvider
import com.turnypt.core.crypto.ProtocolRandom
import com.turnypt.core.crypto.RandomSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Ck1CodecTest {

    private fun message(ciphertext: ByteArray = ByteArray(48) { (it * 3).toByte() }): EncryptedMessage =
        EncryptedMessage(
            mode = EncryptionMode.PASSPHRASE,
            algorithm = CipherAlgorithm.AES_256_GCM,
            kdf = KdfAlgorithm.ARGON2ID,
            keyId = ByteArray(16) { (it + 1).toByte() },
            argon2Params = Argon2Params(16, 3, 1),
            salt = ByteArray(16) { (it + 100).toByte() },
            nonce = ByteArray(12) { (it + 50).toByte() },
            ciphertext = ciphertext,
        )

    @Test
    fun `encode decode roundtrip`() {
        val envelope = MessageEncoder.encode(message())
        assertTrue(envelope.startsWith("CK1:"))
        val decoded = MessageDecoder.decode(envelope)
        val original = message()
        assertArrayEquals(original.keyId, decoded.keyId)
        assertArrayEquals(original.salt, decoded.salt)
        assertArrayEquals(original.nonce, decoded.nonce)
        assertArrayEquals(original.ciphertext, decoded.ciphertext)
        assertEquals(original.argon2Params, decoded.argon2Params)
    }

    @Test
    fun `encoding is canonical base64url without padding`() {
        val envelope = MessageEncoder.encode(message())
        val body = envelope.removePrefix("CK1:")
        assertTrue(body.isNotEmpty())
        assertEquals(-1, body.indexOf('='))
        assertEquals(-1, body.indexOf('+'))
        assertEquals(-1, body.indexOf('/'))
        assertTrue(body.all { it in ALPHABET })
    }

    @Test
    fun `missing prefix is rejected`() {
        assertThrows(Ck1FormatException::class.java) {
            MessageDecoder.decode(MessageEncoder.encode(message()).removePrefix("CK1:"))
        }
    }

    @Test
    fun `padded base64 is rejected`() {
        val envelope = MessageEncoder.encode(message())
        val padded = envelope + "="
        assertThrows(Ck1FormatException::class.java) { MessageDecoder.decode(padded) }
    }

    @Test
    fun `standard alphabet base64 is rejected`() {
        // Replace a safe char with '+' — must be rejected.
        val envelope = MessageEncoder.encode(message())
        val corrupted = envelope.replaceRange(envelope.length - 4, envelope.length, "++++")
        assertThrows(Ck1FormatException::class.java) { MessageDecoder.decode(corrupted) }
    }

    @Test
    fun `whitespace inside payload is rejected`() {
        val envelope = MessageEncoder.encode(message())
        val spaced = envelope.substring(0, 10) + " " + envelope.substring(10)
        assertThrows(Ck1FormatException::class.java) { MessageDecoder.decode(spaced) }
    }

    @Test
    fun `truncated payload is rejected`() {
        val envelope = MessageEncoder.encode(message())
        // Chop far enough that the payload drops below the 71-byte minimum
        // (55-byte header + 16-byte tag).
        val chopped = envelope.substring(0, envelope.length - 60)
        assertThrows(Ck1FormatException::class.java) { MessageDecoder.decode(chopped) }
    }

    @Test
    fun `payload just below the minimum is rejected`() {
        // 70 bytes < 55 + 16 minimum.
        assertThrows(Ck1FormatException::class.java) {
            MessageDecoder.decode("CK1:" + Ck1.encodeBase64Url(ByteArray(70)))
        }
    }

    @Test
    fun `payload shorter than header is rejected`() {
        assertThrows(Ck1FormatException::class.java) {
            MessageDecoder.decode("CK1:" + Ck1.encodeBase64Url(ByteArray(20)))
        }
    }

    @Test
    fun `errorOf maps size failures`() {
        assertEquals(
            com.turnypt.core.crypto.CryptoError.MessageTooLarge,
            MessageDecoder.errorOf(Ck1FormatException("payload too large")),
        )
        assertEquals(
            com.turnypt.core.crypto.CryptoError.MalformedMessage,
            MessageDecoder.errorOf(Ck1FormatException("missing CK1: prefix")),
        )
    }

    @Test
    fun `different ciphertexts produce different envelopes`() {
        val a = MessageEncoder.encode(message(ByteArray(48) { 1 }))
        val b = MessageEncoder.encode(message(ByteArray(48) { 2 }))
        assertNotEquals(a, b)
    }

    /**
     * GOLDEN VECTOR — the cross-implementation compatibility anchor.
     *
     * Fixed inputs (documented in docs/PROTOCOL.md):
     *  - key      = (i*11+5) mod 256, i = 0..31   (32 bytes)
     *  - keyId    = (i+100) mod 256,  i = 0..15
     *  - salt     = (i+200) mod 256,  i = 0..15
     *  - params   = Argon2id, 64 MiB / 3 passes / 1 lane (encoded on wire)
     *  - nonce    = 0,1,...,11 (deterministic RandomSource)
     *  - plaintext (UTF-8) = "CipherKey CK1 golden vector v1"
     *
     * Any conforming CK1 implementation must produce the identical envelope.
     */
    @Test
    fun `golden vector`() = runTest {
        val key = ByteArray(32) { ((it * 11 + 5) % 256).toByte() }
        val keyId = ByteArray(16) { ((it + 100) % 256).toByte() }
        val salt = ByteArray(16) { ((it + 200) % 256).toByte() }
        val params = Argon2Params(16, 3, 1)

        var counter = 0
        val random = ProtocolRandom(RandomSource { count -> ByteArray(count) { ((counter++) % 251).toByte() } })

        val provider = PassphraseCryptoProvider(AeadCiphers(), random)
        val result = provider.encrypt(
            "CipherKey CK1 golden vector v1".toByteArray(),
            com.turnypt.core.crypto.EncryptionContext(
                mode = EncryptionMode.PASSPHRASE,
                key = key,
                keyId = keyId,
                salt = salt,
                kdfParams = params,
                maxPlaintextBytes = Ck1Limits.MAX_PLAINTEXT_BYTES,
            ),
        )
        val envelope = MessageEncoder.encode((result as com.turnypt.core.crypto.EncryptionResult.Success).message)
        assertEquals(GOLDEN_VECTOR, envelope)
    }

    private companion object {
        const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        /**
         * Cross-implementation compatibility anchor (also in docs/PROTOCOL.md).
         * Computed from the fixed inputs documented on the test above; AES-GCM
         * is deterministic, so any conforming CK1 stack reproduces this string.
         */
        const val GOLDEN_VECTOR =
            "CK1:AQEBAWRlZmdoaWprbG1ub3BxcnMQAAAAAwAByMnKy8zNzs_Q0dLT1NXW1wABAgMEBQYHCAkKC8sUsW5uL0eG8bhe2kE3XlxcDpO9PEBc00dLZZzZnYIjOjfyDpIIFAuNE5dTp0c"
    }
}
