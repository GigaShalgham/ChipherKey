package org.cipherkey.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Automatic resolver must (a) always land on a valid cipher, (b) cache
 * the choice per instance and (c) be cheap to construct. The winner itself
 * depends on the host CPU — that is the point — so the assertions stay
 * outcome-based.
 */
class AutomaticCipherSelectorTest {

    @Test
    fun `select returns one of the two supported ciphers`() {
        val selector = AutomaticCipherSelector(benchmarkBytes = 32 * 1024)
        val choice = selector.select()
        assertTrue(
            choice == CipherAlgorithm.AES_256_GCM ||
                choice == CipherAlgorithm.CHACHA20_POLY1305,
        )
    }

    @Test
    fun `choice is cached and peek exposes it without re-benchmarking`() {
        val selector = AutomaticCipherSelector(benchmarkBytes = 32 * 1024)
        assertNull(selector.peek())
        val first = selector.select()
        assertEquals(first, selector.peek())
        assertEquals(first, selector.select())
    }

    @Test
    fun `zero-byte benchmark falls back to aes without measuring`() {
        val selector = AutomaticCipherSelector(benchmarkBytes = 0)
        assertEquals(CipherAlgorithm.AES_256_GCM, selector.select())
    }
}
