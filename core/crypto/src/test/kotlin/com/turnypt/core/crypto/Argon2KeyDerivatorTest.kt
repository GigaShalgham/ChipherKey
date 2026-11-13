package com.turnypt.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Argon2id key derivation tests, including the RFC 9106 §5.3 Argon2id test
 * vector reproduced verbatim through BouncyCastle's lightweight API.
 *
 * Vector parameters: v=1.3 (0x13), m=32 KiB, t=3, p=4, pwd=32*0x01,
 * salt=16*0x02, secret=8*0x03, ad=12*0x04, tag length 32.
 *
 * The expected tag was cross-checked against the reference implementation
 * via the RustCrypto password-hashes known-answer tests (argon2/tests/kat.rs,
 * argon2id_v0x13) — BouncyCastle's output is byte-identical.
 */
class Argon2KeyDerivatorTest {

    private val derivator = Argon2KeyDerivator()

    @Test
    fun `RFC 9106 Argon2id test vector matches`() {
        val params = Argon2Params(memoryExponent = 5, iterations = 3, parallelism = 4)
        val passphrase = CharArray(32) { 0x01.toChar() }  // UTF-8 == raw 0x01 bytes
        val salt = ByteArray(16) { 0x02 }
        val secret = ByteArray(8) { 0x03 }
        val associatedData = ByteArray(12) { 0x04 }

        val tag = derivator.derive(
            passphrase = passphrase,
            salt = salt,
            params = params,
            outputLength = 32,
            secret = secret,
            associatedData = associatedData,
        )

        val expected = hex(
            "0d640df58d78766c08c037a34a8b53c9d" +
                "01ef0452d75b65eb52520e96b01e659",
        )
        assertArrayEquals(expected, tag)
    }

    @Test
    fun `derivation is deterministic for identical inputs`() {
        val params = fastParams()
        val a = derivator.derive("passphrase-1".toCharArray(), salt(1), params)
        val b = derivator.derive("passphrase-1".toCharArray(), salt(1), params)
        assertArrayEquals(a, b)
    }

    @Test
    fun `different salt yields different key`() {
        val params = fastParams()
        val a = derivator.derive("passphrase-1".toCharArray(), salt(1), params)
        val b = derivator.derive("passphrase-1".toCharArray(), salt(2), params)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `different passphrase yields different key`() {
        val params = fastParams()
        val a = derivator.derive("passphrase-1".toCharArray(), salt(1), params)
        val b = derivator.derive("passphrase-2".toCharArray(), salt(1), params)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `output length is honoured`() {
        assertEquals(32, derivator.derive("pw-123456".toCharArray(), salt(9), fastParams()).size)
        assertEquals(64, derivator.derive("pw-123456".toCharArray(), salt(9), fastParams(), outputLength = 64).size)
    }

    @Test
    fun `salt must be exactly 16 bytes`() {
        try {
            derivator.derive("pw-123456".toCharArray(), ByteArray(15), fastParams())
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `unicode passphrase derives stable key`() {
        // Persian passphrase must behave like any other string.
        val params = fastParams()
        val a = derivator.derive("رمز عبور محرمانه".toCharArray(), salt(4), params)
        val b = derivator.derive("رمز عبور محرمانه".toCharArray(), salt(4), params)
        assertArrayEquals(a, b)
    }

    // ------------------------------------------------- v0.5.1 heap pre-flight

    @Test
    fun `profile that cannot fit the heap fails as KdfMemoryException`() {
        // Emulated 256 MiB Android heap + 256 MiB PARANOID profile: the
        // pre-flight must reject BEFORE BouncyCastle allocates (this exact
        // combination OOM-crashed real devices in v0.5.0).
        val tinyHeap = Argon2KeyDerivator(maxHeapBytes = { 256L * 1024 * 1024 })
        val paranoid = Argon2Params(memoryExponent = 18, iterations = 4, parallelism = 1)
        try {
            tinyHeap.derive("pw-123456".toCharArray(), salt(9), paranoid)
            throw AssertionError("expected KdfMemoryException")
        } catch (expected: KdfMemoryException) {
            assertEquals(256L * 1024 * 1024, expected.requiredBytes)
        }
    }

    @Test
    fun `profile that fits the heap derives normally`() {
        // 64 MiB profile against an emulated 128 MiB heap (fits with the
        // 1.25 headroom): derivation proceeds and produces a full tag.
        val roomyHeap = Argon2KeyDerivator(maxHeapBytes = { 128L * 1024 * 1024 })
        val tag = roomyHeap.derive(
            "pw-123456".toCharArray(),
            salt(9),
            Argon2Params(memoryExponent = 16, iterations = 1, parallelism = 1),
        )
        assertEquals(32, tag.size)
    }

    private fun fastParams() = Argon2Params(memoryExponent = 3, iterations = 1, parallelism = 1)

    private fun salt(seed: Int) = ByteArray(16) { ((it + seed) and 0xFF).toByte() }

    private fun hex(text: String): ByteArray =
        text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
