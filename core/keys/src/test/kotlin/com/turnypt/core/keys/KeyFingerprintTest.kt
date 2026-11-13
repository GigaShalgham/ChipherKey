package com.turnypt.core.keys

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the fingerprint format (deep-scan finding 9): the
 * digits must be locale-independent so that users on Persian-locale devices
 * can compare fingerprints out-of-band with users on any other locale.
 */
class KeyFingerprintTest {

    @Test
    fun `fingerprints render locale-independent ASCII digits`() {
        val original = Locale.getDefault()
        try {
            // Java's fa locale formats integers with U+06F0.. digits
            // ("۰۷۲"), which would break text comparison of the verifier.
            Locale.setDefault(Locale("fa", "IR"))
            val fingerprint = KeyFingerprint.of(ByteArray(32) { (it * 7).toByte() })
            assertEquals(23, fingerprint.length)
            assertTrue(
                "expected ASCII digits, got: $fingerprint",
                Regex("^[0-9]{3}(:[0-9]{3}){5}$").matches(fingerprint),
            )
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `ofSession is stable and shape-correct`() {
        val one = KeyFingerprint.ofSession(ByteArray(16) { 1 }, ByteArray(16) { 2 })
        val same = KeyFingerprint.ofSession(ByteArray(16) { 1 }, ByteArray(16) { 2 })
        val other = KeyFingerprint.ofSession(ByteArray(16) { 9 }, ByteArray(16) { 2 })
        assertEquals(one, same)
        assertTrue(one != other)
        assertTrue(Regex("^[0-9]{3}(:[0-9]{3}){5}$").matches(one))
    }
}
