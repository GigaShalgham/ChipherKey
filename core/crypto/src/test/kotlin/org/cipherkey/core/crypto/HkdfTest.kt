package org.cipherkey.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** RFC 5869 test vectors for the HKDF-SHA256 helper (CKF1 file subkeys). */
class HkdfTest {

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun `rfc 5869 test case 1 — basic case`() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val okm = Hkdf.sha256(ikm, salt, info, 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a" +
                "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865",
            okm.toHex(),
        )
    }

    @Test
    fun `rfc 5869 test case 2 — longer inputs`() {
        val ikm = hex(
            "000102030405060708090a0b0c0d0e0f" +
                "101112131415161718191a1b1c1d1e1f" +
                "202122232425262728292a2b2c2d2e2f" +
                "303132333435363738393a3b3c3d3e3f" +
                "404142434445464748494a4b4c4d4e4f",
        )
        val salt = hex(
            "606162636465666768696a6b6c6d6e6f" +
                "707172737475767778797a7b7c7d7e7f" +
                "808182838485868788898a8b8c8d8e8f" +
                "909192939495969798999a9b9c9d9e9f" +
                "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf",
        )
        val info = hex(
            "b0b1b2b3b4b5b6b7b8b9babbbcbdbebf" +
                "c0c1c2c3c4c5c6c7c8c9cacbcccdcecf" +
                "d0d1d2d3d4d5d6d7d8d9dadbdcdddedf" +
                "e0e1e2e3e4e5e6e7e8e9eaebecedeeef" +
                "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff",
        )
        val okm = Hkdf.sha256(ikm, salt, info, 82)
        assertEquals(
            "b11e398dc80327a1c8e7f78c596a4934" +
                "4f012eda2d4efad8a050cc4c19afa97c" +
                "59045a99cac7827271cb41c65e590e09" +
                "da3275600c2f09b8367793a9aca3db71" +
                "cc30c58179ec3e87c14c01d5c1f3434f" +
                "1d87",
            okm.toHex(),
        )
    }

    @Test
    fun `different salts derive different subkeys`() {
        val ikm = ByteArray(32) { 9 }
        val a = Hkdf.sha256(ikm, ByteArray(12) { 1 }, ByteArray(0), 32)
        val b = Hkdf.sha256(ikm, ByteArray(12) { 2 }, ByteArray(0), 32)
        assertEquals(32, a.size)
        assertEquals(32, b.size)
        org.junit.Assert.assertFalse(a.contentEquals(b))
    }
}
