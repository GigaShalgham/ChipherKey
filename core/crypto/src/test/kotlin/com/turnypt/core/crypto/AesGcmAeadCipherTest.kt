package com.turnypt.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AesGcmAeadCipherTest {

    private val cipher = AesGcmAeadCipher()
    private val key = ByteArray(32) { (it * 7 + 1).toByte() }
    private val nonce = ByteArray(12) { (it * 3).toByte() }
    private val aad = "CK1-associated-data".toByteArray()

    @Test
    fun `seal then open roundtrips`() {
        val plaintext = "Hello, Turnypt!".toByteArray()
        val sealed = cipher.seal(key, nonce, plaintext, aad)
        val opened = cipher.open(key, nonce, sealed, aad)
        assertArrayEquals(plaintext, opened)
    }

    @Test
    fun `sealed output is ciphertext plus 16 byte tag`() {
        val plaintext = ByteArray(100)
        val sealed = cipher.seal(key, nonce, plaintext, aad)
        assertEquals(116, sealed.size)
    }

    @Test
    fun `empty plaintext still authenticates`() {
        val sealed = cipher.seal(key, nonce, ByteArray(0), aad)
        assertEquals(16, sealed.size)
        assertArrayEquals(ByteArray(0), cipher.open(key, nonce, sealed, aad))
    }

    @Test
    fun `wrong key fails authentication`() {
        val sealed = cipher.seal(key, nonce, "secret".toByteArray(), aad)
        val wrongKey = key.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val failure = assertThrows(AeadFailure::class.java) {
            cipher.open(wrongKey, nonce, sealed, aad)
        }
        assertEquals(CryptoError.AuthenticationFailed, failure.error)
    }

    @Test
    fun `flipping any ciphertext byte fails authentication`() {
        val plaintext = "attack at dawn".toByteArray()
        val sealed = cipher.seal(key, nonce, plaintext, aad)
        for (i in sealed.indices) {
            val tampered = sealed.copyOf()
            tampered[i] = (tampered[i].toInt() xor 0x01).toByte()
            val failure = assertThrows(AeadFailure::class.java) {
                cipher.open(key, nonce, tampered, aad)
            }
            assertEquals(CryptoError.AuthenticationFailed, failure.error)
        }
    }

    @Test
    fun `associated data mismatch fails authentication`() {
        val sealed = cipher.seal(key, nonce, "secret".toByteArray(), aad)
        assertThrows(AeadFailure::class.java) {
            cipher.open(key, nonce, sealed, "CK1-other-data".toByteArray())
        }
    }

    @Test
    fun `truncated sealed blob is rejected`() {
        val sealed = cipher.seal(key, nonce, "secret".toByteArray(), aad)
        assertThrows(AeadFailure::class.java) {
            cipher.open(key, nonce, sealed.copyOf(15), aad)
        }
        assertThrows(AeadFailure::class.java) {
            cipher.open(key, nonce, ByteArray(0), aad)
        }
    }

    @Test
    fun `invalid key or nonce length is rejected before crypto work`() {
        assertThrows(AeadFailure::class.java) {
            cipher.seal(ByteArray(16), nonce, "x".toByteArray(), aad)
        }
        assertThrows(AeadFailure::class.java) {
            cipher.seal(key, ByteArray(8), "x".toByteArray(), aad)
        }
        assertThrows(AeadFailure::class.java) {
            cipher.open(ByteArray(16), nonce, ByteArray(32), aad)
        }
    }

    @Test
    fun `large plaintext roundtrips`() {
        val plaintext = ByteArray(16 * 1024) { (it % 251).toByte() }
        val sealed = cipher.seal(key, nonce, plaintext, aad)
        assertArrayEquals(plaintext, cipher.open(key, nonce, sealed, aad))
        assertTrue(sealed.size > plaintext.size)
    }

    @Test
    fun `same key and nonce with different plaintexts produce different tags`() {
        val a = cipher.seal(key, nonce, "one".toByteArray(), aad)
        val b = cipher.seal(key, nonce, "two".toByteArray(), aad)
        assertNotEquals(a.contentHashCode(), b.contentHashCode())
        assertTrue(!a.contentEquals(b))
    }
}
