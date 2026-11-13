package org.cipherkey.core.crypto

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Full provider-level tests with deterministic randomness.
 *
 * [CountingRandomSource] hands out 0,1,2,... bytes so envelopes are
 * reproducible byte-for-byte (the basis of the CK1 golden vector).
 */
class PassphraseCryptoProviderTest {

    /** Deterministic source: byte i of every call equals (counter++) % 251. */
    private class CountingRandomSource : RandomSource {
        private var counter = 0
        override fun nextBytes(count: Int): ByteArray = ByteArray(count) { ((counter++) % 251).toByte() }
    }

    private val key = ByteArray(32) { (it * 11 + 5).toByte() }
    private val keyId = ByteArray(16) { (it + 100).toByte() }
    private val salt = ByteArray(16) { (it + 200).toByte() }
    private val params = Argon2Params(16, 3, 1)

    private fun provider(random: ProtocolRandom = ProtocolRandom(RandomSource.SECURE)) =
        PassphraseCryptoProvider(AeadCiphers(), random)

    private fun context(maxPlaintext: Int = CRYPTO_MAX) = EncryptionContext(
        mode = EncryptionMode.PASSPHRASE,
        key = key,
        keyId = keyId,
        salt = salt,
        kdfParams = params,
        maxPlaintextBytes = maxPlaintext,
    )

    private fun decryptContext(maxSealed: Int = 256 * 1024) = DecryptionContext(
        mode = EncryptionMode.PASSPHRASE,
        key = key,
        maxSealedBytes = maxSealed,
    )

    @Test
    fun `encrypt then decrypt roundtrips`() = runTest {
        val plaintext = "سلام دنیا — hello world 🌍".toByteArray()
        val result = provider().encrypt(plaintext, context())
        assertTrue(result is EncryptionResult.Success)
        val message = (result as EncryptionResult.Success).message
        val decrypted = provider().decrypt(message, decryptContext())
        assertTrue(decrypted is DecryptionResult.Success)
        assertArrayEquals(plaintext, (decrypted as DecryptionResult.Success).plaintext)
    }

    @Test
    fun `envelope fields are populated from the context`() = runTest {
        val result = provider().encrypt("x".toByteArray(), context())
        val message = (result as EncryptionResult.Success).message
        assertArrayEquals(keyId, message.keyId)
        assertArrayEquals(salt, message.salt)
        assertEquals(params, message.argon2Params)
        assertEquals(EncryptionMode.PASSPHRASE, message.mode)
        assertEquals(CipherAlgorithm.AES_256_GCM, message.algorithm)
        assertEquals(KdfAlgorithm.ARGON2ID, message.kdf)
    }

    @Test
    fun `wrong key fails authentication`() = runTest {
        val message = (provider().encrypt("secret".toByteArray(), context()) as EncryptionResult.Success).message
        val wrongKeyContext = DecryptionContext(EncryptionMode.PASSPHRASE, key.copyOf().also { it[3] = 99 }, 256 * 1024)
        val outcome = provider().decrypt(message, wrongKeyContext)
        assertTrue(outcome is DecryptionResult.Failure)
        assertEquals(CryptoError.AuthenticationFailed, (outcome as DecryptionResult.Failure).error)
    }

    @Test
    fun `tampered header fails authentication via aad binding`() = runTest {
        val message = (provider().encrypt("secret".toByteArray(), context()) as EncryptionResult.Success).message
        // Tamper with the SERIALIZED form (header is AAD), then re-parse.
        val bytes = message.toByteArray()
        bytes[20] = (bytes[20].toInt() xor 0x01).toByte() // memory exponent byte
        val tampered = ProtocolHeader.parse(bytes)
        val outcome = provider().decrypt(tampered, decryptContext())
        assertEquals(CryptoError.AuthenticationFailed, (outcome as DecryptionResult.Failure).error)
    }

    @Test
    fun `oversized plaintext is rejected`() = runTest {
        val outcome = provider().encrypt(ByteArray(101), context(maxPlaintext = 100))
        assertEquals(CryptoError.InputTooLarge, (outcome as EncryptionResult.Failure).error)
    }

    @Test
    fun `wrong key size is rejected`() = runTest {
        val badContext = EncryptionContext(
            mode = EncryptionMode.PASSPHRASE,
            key = ByteArray(16),
            keyId = keyId,
            salt = salt,
            kdfParams = params,
            maxPlaintextBytes = 1024,
        )
        val outcome = provider().encrypt("x".toByteArray(), badContext)
        assertEquals(CryptoError.InvalidKey, (outcome as EncryptionResult.Failure).error)
    }

    @Test
    fun `oversized sealed input is rejected at decrypt`() = runTest {
        val big = EncryptedMessage(
            mode = EncryptionMode.PASSPHRASE,
            algorithm = CipherAlgorithm.AES_256_GCM,
            kdf = KdfAlgorithm.ARGON2ID,
            keyId = keyId,
            argon2Params = params,
            salt = salt,
            nonce = ByteArray(12),
            ciphertext = ByteArray(300),
        )
        val outcome = provider().decrypt(big, decryptContext(maxSealed = 256))
        assertEquals(CryptoError.MessageTooLarge, (outcome as DecryptionResult.Failure).error)
    }

    @Test
    fun `deterministic random source yields byte-stable envelope`() = runTest {
        val a = (provider(ProtocolRandom(CountingRandomSource())).encrypt("golden".toByteArray(), context()) as EncryptionResult.Success).message
        val b = (provider(ProtocolRandom(CountingRandomSource())).encrypt("golden".toByteArray(), context()) as EncryptionResult.Success).message
        assertArrayEquals(a.toByteArray(), b.toByteArray())
    }

    // ------------------------------------------------- v0.6.0 ChaCha20 mode

    @Test
    fun `chacha20-poly1305 envelope roundtrips and records the cipher`() = runTest {
        val plaintext = "تصویر رمز شده — image payload".toByteArray()
        val result = provider().encrypt(plaintext, context().copy(algorithm = CipherAlgorithm.CHACHA20_POLY1305))
        assertTrue(result is EncryptionResult.Success)
        val message = (result as EncryptionResult.Success).message
        assertEquals(CipherAlgorithm.CHACHA20_POLY1305, message.algorithm)
        // Decryption is independent of any preference: the wire id decides.
        val decrypted = provider().decrypt(message, decryptContext())
        assertTrue(decrypted is DecryptionResult.Success)
        assertArrayEquals(plaintext, (decrypted as DecryptionResult.Success).plaintext)
    }

    @Test
    fun `aes and chacha envelopes coexist under the same key`() = runTest {
        val aesMessage = (provider().encrypt("via aes".toByteArray(), context()) as EncryptionResult.Success).message
        val chachaMessage = (
            provider().encrypt(
                "via chacha".toByteArray(),
                context().copy(algorithm = CipherAlgorithm.CHACHA20_POLY1305),
            ) as EncryptionResult.Success
            ).message
        assertEquals(CipherAlgorithm.AES_256_GCM, aesMessage.algorithm)
        assertEquals(CipherAlgorithm.CHACHA20_POLY1305, chachaMessage.algorithm)
        assertArrayEquals(
            "via aes".toByteArray(),
            (provider().decrypt(aesMessage, decryptContext()) as DecryptionResult.Success).plaintext,
        )
        assertArrayEquals(
            "via chacha".toByteArray(),
            (provider().decrypt(chachaMessage, decryptContext()) as DecryptionResult.Success).plaintext,
        )
    }

    private companion object {
        const val CRYPTO_MAX = 16 * 1024
    }
}
