package com.turnypt.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * RFC 8439 §2.8.2 is the normative AEAD test vector — the implementation
 * must reproduce it byte-for-byte before anything else is believed.
 */
class ChaCha20Poly1305AeadCipherTest {

    private val cipher = ChaCha20Poly1305AeadCipher()

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `rfc 8439 section 2_8_2 aead vector reproduces exactly`() {
        val key = hex(
            "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f",
        )
        val nonce = hex("070000004041424344454647")
        val aad = hex("50515253c0c1c2c3c4c5c6c7")
        val plaintext = (
            "Ladies and Gentlemen of the class of '99: If I could offer you " +
                "only one tip for the future, sunscreen would be it."
            ).toByteArray(Charsets.US_ASCII)
        val expectedCiphertext = hex(
            "d31a8d34648e60db7b86afbc53ef7ec2" +
                "a4aded51296e08fea9e2b5a736ee62d6" +
                "3dbea45e8ca9671282fafb69da92728b" +
                "1a71de0a9e060b2905d6a5b67ecd3b36" +
                "92ddbd7f2d778b8c9803aee328091b58" +
                "fab324e4fad675945585808b4831d7bc" +
                "3ff4def08e4b7a9de576d26586cec64b" +
                "6116",
        )
        val expectedTag = hex("1ae10b594f09e26a7e902ecbd0600691")

        val sealed = cipher.seal(key, nonce, plaintext, aad)

        val ciphertext = sealed.copyOfRange(0, sealed.size - 16)
        val tag = sealed.copyOfRange(sealed.size - 16, sealed.size)
        assertArrayEquals(expectedCiphertext, ciphertext)
        assertArrayEquals(expectedTag, tag)

        // And back: the same vector must open cleanly.
        val opened = cipher.open(key, nonce, sealed, aad)
        assertArrayEquals(plaintext, opened)
    }

    @Test
    fun `roundtrip with empty aad and empty plaintext`() {
        val key = ByteArray(32) { (it * 7).toByte() }
        val nonce = ByteArray(12) { (it * 3).toByte() }
        val sealedEmpty = cipher.seal(key, nonce, ByteArray(0), ByteArray(0))
        assertEquals(16, sealedEmpty.size) // tag only
        assertArrayEquals(ByteArray(0), cipher.open(key, nonce, sealedEmpty, ByteArray(0)))

        val sealed = cipher.seal(key, nonce, "سلام".toByteArray(), ByteArray(0))
        assertArrayEquals("سلام".toByteArray(), cipher.open(key, nonce, sealed, ByteArray(0)))
    }

    @Test
    fun `tampered ciphertext or aad fails authentication`() {
        val key = ByteArray(32) { 1 }
        val nonce = ByteArray(12) { 2 }
        val sealed = cipher.seal(key, nonce, "attack at dawn".toByteArray(), "hdr".toByteArray())

        val tampered = sealed.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        try {
            cipher.open(key, nonce, tampered, "hdr".toByteArray())
            fail("tampered ciphertext must not authenticate")
        } catch (e: AeadFailure) {
            assertEquals(CryptoError.AuthenticationFailed, e.error)
        }

        try {
            cipher.open(key, nonce, sealed, "HDR".toByteArray())
            fail("tampered aad must not authenticate")
        } catch (e: AeadFailure) {
            assertEquals(CryptoError.AuthenticationFailed, e.error)
        }
    }

    @Test
    fun `wrong key fails authentication`() {
        val key = ByteArray(32) { 5 }
        val wrongKey = ByteArray(32) { 6 }
        val nonce = ByteArray(12)
        val sealed = cipher.seal(key, nonce, "x".toByteArray(), ByteArray(0))
        try {
            cipher.open(wrongKey, nonce, sealed, ByteArray(0))
            fail("wrong key must not authenticate")
        } catch (e: AeadFailure) {
            assertEquals(CryptoError.AuthenticationFailed, e.error)
        }
    }

    @Test
    fun `oversized key and nonce are rejected`() {
        assertThrows(AeadFailure::class.java) {
            cipher.seal(ByteArray(31), ByteArray(12), ByteArray(0), ByteArray(0))
        }
        assertThrows(AeadFailure::class.java) {
            cipher.seal(ByteArray(32), ByteArray(11), ByteArray(0), ByteArray(0))
        }
    }

    @Test
    fun `sealed shorter than the tag is malformed`() {
        try {
            cipher.open(ByteArray(32), ByteArray(12), ByteArray(15), ByteArray(0))
            fail("sub-tag input must be rejected")
        } catch (e: AeadFailure) {
            assertEquals(CryptoError.MalformedMessage, e.error)
        }
    }

    @Test
    fun `registry maps wire ids to implementations`() {
        val registry = AeadCiphers()
        assertTrue(registry.of(CipherAlgorithm.AES_256_GCM) is AesGcmAeadCipher)
        assertTrue(registry.of(CipherAlgorithm.CHACHA20_POLY1305) is ChaCha20Poly1305AeadCipher)
    }
}
