package org.cipherkey.core.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * CKF1 streaming container: roundtrips on every chunk boundary, and the
 * adversarial cases the format promises to reject (tamper, splice,
 * truncate, reorder, wrong key) — for BOTH ciphers.
 */
class CkfChunkedCipherTest {

    private val key = ByteArray(32) { (it * 13 + 1).toByte() }
    private val keyId = ByteArray(16) { (it + 30).toByte() }
    private val salt = ByteArray(16) { (it + 60).toByte() }
    private val params = Argon2Params(16, 3, 1)
    private val cipher = CkfChunkedCipher()

    private fun nonceFor(seed: Int): ByteArray = ByteArray(12) { (seed + it).toByte() }

    private fun encrypt(
        data: ByteArray,
        algorithm: CipherAlgorithm = CipherAlgorithm.AES_256_GCM,
        nonce: ByteArray = nonceFor(7),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        cipher.encrypt(
            key = key,
            keyId = keyId,
            salt = salt,
            params = params,
            algorithm = algorithm,
            baseNonce = nonce,
            input = ByteArrayInputStream(data),
            output = out,
        )
        return out.toByteArray()
    }

    private fun decrypt(file: ByteArray, k: ByteArray = key): ByteArray {
        val out = ByteArrayOutputStream()
        cipher.decrypt(ByteArrayInputStream(file), out, k)
        return out.toByteArray()
    }

    private fun expectFailure(file: ByteArray, expected: CryptoError, k: ByteArray = key) {
        try {
            decrypt(file, k)
            fail("expected $expected")
        } catch (e: AeadFailure) {
            assertEquals(expected, e.error)
        }
    }

    private fun pseudoRandom(size: Int, seed: Int): ByteArray =
        ByteArray(size) { ((it * 31 + seed * 17) % 251).toByte() }

    // ------------------------------------------------------------ roundtrip

    @Test
    fun `empty input roundtrips with a single empty final chunk`() {
        val file = encrypt(ByteArray(0))
        // prefix(59) + one tag(16) and nothing else
        assertEquals(Ckf1.PREFIX_LENGTH + 16, file.size)
        assertTrue(Ckf1.hasMagic(file))
        assertArrayEquals(ByteArray(0), decrypt(file))
    }

    @Test
    fun `roundtrips across every chunk boundary`() {
        val c = Ckf1.CHUNK_PLAINTEXT_BYTES
        val sizes = listOf(1, 100, c - 1, c, c + 1, 2 * c, 3 * c - 5, 3 * c)
        for (size in sizes) {
            val data = pseudoRandom(size, seed = size % 97)
            assertArrayEquals("size $size", data, decrypt(encrypt(data)))
        }
    }

    @Test
    fun `roundtrips with chacha20-poly1305`() {
        val data = pseudoRandom(Ckf1.CHUNK_PLAINTEXT_BYTES * 2 + 123, seed = 5)
        val file = encrypt(data, algorithm = CipherAlgorithm.CHACHA20_POLY1305)
        // The header records the cipher; decrypt reads it from there.
        val header = Ckf1.parsePrefix(file)
        assertEquals(CipherAlgorithm.CHACHA20_POLY1305, header.algorithm)
        assertArrayEquals(data, decrypt(file))
    }

    @Test
    fun `exact multiple of chunk size carries the empty final sentinel`() {
        val file = encrypt(ByteArray(Ckf1.CHUNK_PLAINTEXT_BYTES) { 0x41 })
        // 59 + (65536+16) + (0+16)
        assertEquals(Ckf1.PREFIX_LENGTH + Ckf1.CHUNK_PLAINTEXT_BYTES + 16 + 16, file.size)
        assertArrayEquals(ByteArray(Ckf1.CHUNK_PLAINTEXT_BYTES) { 0x41 }, decrypt(file))
    }

    @Test
    fun `header fields survive`() {
        val header = Ckf1.parsePrefix(encrypt("x".toByteArray()))
        assertArrayEquals(keyId, header.keyId)
        assertArrayEquals(salt, header.salt)
        assertEquals(params, header.argon2Params)
        assertEquals(EncryptionMode.PASSPHRASE, header.mode)
        assertEquals(KdfAlgorithm.ARGON2ID, header.kdf)
    }

    @Test
    fun `progress callback reports plaintext bytes`() {
        val data = pseudoRandom(Ckf1.CHUNK_PLAINTEXT_BYTES + 10, seed = 3)
        val seen = mutableListOf<Long>()
        val out = ByteArrayOutputStream()
        cipher.encrypt(
            key, keyId, salt, params, CipherAlgorithm.AES_256_GCM, nonceFor(1),
            ByteArrayInputStream(data), out,
        ) { seen.add(it) }
        assertEquals(listOf(Ckf1.CHUNK_PLAINTEXT_BYTES.toLong(), data.size.toLong()), seen)
    }

    // ------------------------------------------------------------ rejection

    @Test
    fun `wrong key fails authentication`() {
        expectFailure(encrypt("secret image".toByteArray()), CryptoError.AuthenticationFailed, k = ByteArray(32) { 9 })
    }

    @Test
    fun `tampered magic is not a cipherkey file`() {
        val file = encrypt("x".toByteArray()).also { it[1] = 0x58 }
        try {
            Ckf1.parsePrefix(file)
            fail("bad magic must be rejected")
        } catch (e: AeadFailure) {
            assertEquals(CryptoError.NotACipherFile, e.error)
        }
    }

    @Test
    fun `tampered header byte fails authentication`() {
        // The header is the chunk AAD: flip a salt byte and every chunk
        // stops authenticating.
        val file = encrypt("photograph bytes".toByteArray())
        val tamperedSalt = file.copyOf().also { it[4 + 27] = (it[4 + 27].toInt() xor 0x40).toByte() }
        expectFailure(tamperedSalt, CryptoError.AuthenticationFailed)
    }

    @Test
    fun `unknown cipher id in the file prefix is rejected`() {
        val file = encrypt("photograph bytes".toByteArray())
        // 0x01 (AES) xor 0x05 = 0x04 — not a compiled-in id.
        val tamperedCipherId = file.copyOf().also { it[4 + 2] = (it[4 + 2].toInt() xor 0x05).toByte() }
        try {
            Ckf1.parsePrefix(tamperedCipherId)
            fail("unknown cipher id must be rejected before any crypto work")
        } catch (e: AeadFailure) {
            assertEquals(CryptoError.UnsupportedAlgorithm(0x04), e.error)
        }
    }

    @Test
    fun `tampered chunk byte fails authentication`() {
        val data = pseudoRandom(Ckf1.CHUNK_PLAINTEXT_BYTES * 2 + 7, seed = 11)
        val file = encrypt(data)
        // Flip a byte inside the SECOND chunk frame.
        val secondChunkOffset = Ckf1.PREFIX_LENGTH + Ckf1.CHUNK_PLAINTEXT_BYTES + 16 + 3
        val tampered = file.copyOf().also { it[secondChunkOffset] = (it[secondChunkOffset].toInt() xor 0x01).toByte() }
        expectFailure(tampered, CryptoError.AuthenticationFailed)
    }

    @Test
    fun `truncated file without the final short chunk is rejected`() {
        val data = pseudoRandom(Ckf1.CHUNK_PLAINTEXT_BYTES * 2, seed = 13)
        val file = encrypt(data)
        // Drop the empty final sentinel: full chunk + full chunk + nothing.
        val truncated = file.copyOfRange(0, file.size - 16)
        expectFailure(truncated, CryptoError.FileTruncated)
    }

    @Test
    fun `mid-file truncation is rejected`() {
        val data = pseudoRandom(Ckf1.CHUNK_PLAINTEXT_BYTES + 500, seed = 17)
        val file = encrypt(data)
        // Cut INSIDE the second chunk frame.
        val truncated = file.copyOfRange(0, Ckf1.PREFIX_LENGTH + Ckf1.CHUNK_PLAINTEXT_BYTES + 16 + 200)
        expectFailure(truncated, CryptoError.AuthenticationFailed)
    }

    @Test
    fun `reordered chunks are rejected`() {
        val data = pseudoRandom(Ckf1.CHUNK_PLAINTEXT_BYTES * 3, seed = 19)
        val file = encrypt(data)
        val frame = Ckf1.CHUNK_PLAINTEXT_BYTES + 16
        val prefix = file.copyOfRange(0, Ckf1.PREFIX_LENGTH)
        val f0 = file.copyOfRange(Ckf1.PREFIX_LENGTH, Ckf1.PREFIX_LENGTH + frame)
        val f1 = file.copyOfRange(Ckf1.PREFIX_LENGTH + frame, Ckf1.PREFIX_LENGTH + 2 * frame)
        val sentinel = file.copyOfRange(Ckf1.PREFIX_LENGTH + 2 * frame, file.size)
        val reordered = prefix + f1 + f0 + sentinel
        expectFailure(reordered, CryptoError.AuthenticationFailed)
    }

    @Test
    fun `trailing garbage after the final chunk is rejected`() {
        // The extra byte glues itself onto the short final frame, so the
        // frame no longer matches what was sealed — authentication fails.
        val file = encrypt("tiny".toByteArray()) + 0x00
        expectFailure(file, CryptoError.AuthenticationFailed)
    }

    @Test
    fun `prefix shorter than 59 bytes is malformed`() {
        try {
            decrypt(ByteArray(58))
            fail("short file must be rejected")
        } catch (e: AeadFailure) {
            assertEquals(CryptoError.MalformedMessage, e.error)
        }
    }

    // ------------------------------------------------------------ subkeys

    @Test
    fun `two files under the same era key derive independent subkeys`() {
        val data = "same plaintext".toByteArray()
        val a = encrypt(data, nonce = nonceFor(1))
        val b = encrypt(data, nonce = nonceFor(2))
        // Same content, different base nonce ⇒ different ciphertexts.
        assertTrue(!a.contentEquals(b))
        assertArrayEquals(data, decrypt(a))
        assertArrayEquals(data, decrypt(b))
    }
}
