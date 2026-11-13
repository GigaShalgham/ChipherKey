package com.turnypt.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolHeaderTest {

    private fun sampleMessage(ciphertext: ByteArray = ByteArray(48) { (it % 7).toByte() }) =
        EncryptedMessage(
            mode = EncryptionMode.PASSPHRASE,
            algorithm = CipherAlgorithm.AES_256_GCM,
            kdf = KdfAlgorithm.ARGON2ID,
            keyId = ByteArray(16) { (it + 1).toByte() },
            argon2Params = Argon2Params(16, 3, 1),
            salt = ByteArray(16) { (it + 17).toByte() },
            nonce = ByteArray(12) { (it + 33).toByte() },
            ciphertext = ciphertext,
        )

    @Test
    fun `serialize then parse roundtrips`() {
        val message = sampleMessage()
        val bytes = message.toByteArray()
        val parsed = ProtocolHeader.parse(bytes)
        assertEquals(message.mode, parsed.mode)
        assertEquals(message.algorithm, parsed.algorithm)
        assertEquals(message.kdf, parsed.kdf)
        assertArrayEquals(message.keyId, parsed.keyId)
        assertEquals(message.argon2Params, parsed.argon2Params)
        assertArrayEquals(message.salt, parsed.salt)
        assertArrayEquals(message.nonce, parsed.nonce)
        assertArrayEquals(message.ciphertext, parsed.ciphertext)
    }

    @Test
    fun `header length matches documented layout`() {
        assertEquals(55, ProtocolHeader.PASSPHRASE_HEADER_LENGTH)
        assertEquals(55 + 48, sampleMessage().toByteArray().size)
    }

    @Test
    fun `unknown version is rejected`() {
        val bytes = sampleMessage().toByteArray()
        bytes[0] = 0x02
        val failure = assertThrows(AeadFailure::class.java) { ProtocolHeader.parse(bytes) }
        assertTrue(failure.error is CryptoError.UnsupportedVersion)
    }

    @Test
    fun `unknown mode is rejected`() {
        val bytes = sampleMessage().toByteArray()
        bytes[1] = 0x7F
        assertThrows(AeadFailure::class.java) { ProtocolHeader.parse(bytes) }
    }

    @Test
    fun `reserved public key mode is rejected in v1`() {
        val bytes = sampleMessage().toByteArray()
        bytes[1] = EncryptionMode.PUBLIC_KEY.id.toByte()
        val failure = assertThrows(AeadFailure::class.java) { ProtocolHeader.parse(bytes) }
        assertTrue(failure.error is CryptoError.UnsupportedMode)
    }

    @Test
    fun `unknown cipher algorithm is rejected`() {
        val bytes = sampleMessage().toByteArray()
        bytes[2] = 0x55
        assertThrows(AeadFailure::class.java) { ProtocolHeader.parse(bytes) }
    }

    @Test
    fun `unknown kdf is rejected`() {
        val bytes = sampleMessage().toByteArray()
        bytes[3] = 0x77
        assertThrows(AeadFailure::class.java) { ProtocolHeader.parse(bytes) }
    }

    @Test
    fun `truncated payload is rejected`() {
        val bytes = sampleMessage().toByteArray()
        assertThrows(AeadFailure::class.java) { ProtocolHeader.parse(bytes.copyOf(54)) }
        assertThrows(AeadFailure::class.java) { ProtocolHeader.parse(ByteArray(0)) }
    }

    @Test
    fun `header bytes are authenticated aad material`() {
        // The serialize output must be exactly the AAD bound to the tag:
        // deterministic and byte-stable for identical inputs.
        val a = ProtocolHeader.serialize(sampleMessage())
        val b = ProtocolHeader.serialize(sampleMessage())
        assertArrayEquals(a, b)
        assertEquals(55, a.size)
    }

    @Test
    fun `serialization validates salt and nonce lengths`() {
        val badSalt = sampleMessage().copy(salt = ByteArray(15))
        assertThrows(AeadFailure::class.java) { ProtocolHeader.serialize(badSalt) }
        val badNonce = sampleMessage().copy(nonce = ByteArray(11))
        assertThrows(AeadFailure::class.java) { ProtocolHeader.serialize(badNonce) }
        val badKeyId = sampleMessage().copy(keyId = ByteArray(17))
        assertThrows(AeadFailure::class.java) { ProtocolHeader.serialize(badKeyId) }
    }
}
