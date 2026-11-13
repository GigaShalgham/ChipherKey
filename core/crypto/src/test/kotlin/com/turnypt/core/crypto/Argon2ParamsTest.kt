package com.turnypt.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Argon2ParamsTest {

    @Test
    fun `encode decode roundtrip preserves values`() {
        val params = Argon2Params(memoryExponent = 16, iterations = 3, parallelism = 1)
        val decoded = Argon2Params.decode(params.encode())
        assertEquals(params, decoded)
    }

    @Test
    fun `encoded length is canonical`() {
        assertEquals(Argon2Params.ENCODED_LENGTH, Argon2Params(14, 3, 1).encode().size)
    }

    @Test
    fun `memory in KiB is a power of two`() {
        assertEquals(64 * 1024, Argon2Params(16, 3, 1).memoryKib)
        assertEquals(8, Argon2Params(3, 1, 1).memoryKib)
    }

    @Test
    fun `rejects memory exponent out of range`() {
        assertThrows(IllegalArgumentException::class.java) { Argon2Params(2, 3, 1) }
        assertThrows(IllegalArgumentException::class.java) { Argon2Params(23, 3, 1) }
    }

    @Test
    fun `rejects iterations out of range`() {
        assertThrows(IllegalArgumentException::class.java) { Argon2Params(16, 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { Argon2Params(16, 65_537, 1) }
    }

    @Test
    fun `rejects parallelism out of range`() {
        assertThrows(IllegalArgumentException::class.java) { Argon2Params(16, 3, 0) }
        assertThrows(IllegalArgumentException::class.java) { Argon2Params(16, 3, 65) }
    }

    @Test
    fun `rejects memory below 8 times parallelism`() {
        // 2^3 = 8 KiB with 2 lanes violates RFC 9106's 8*p KiB minimum.
        assertThrows(IllegalArgumentException::class.java) { Argon2Params(3, 1, 2) }
    }

    @Test
    fun `decode rejects wrong length`() {
        assertThrows(IllegalArgumentException::class.java) { Argon2Params.decode(ByteArray(6)) }
        assertThrows(IllegalArgumentException::class.java) { Argon2Params.decode(ByteArray(8)) }
    }

    @Test
    fun `profiles from policy module stay within wire limits`() {
        // BALANCED-style default must remain encodable (the shipped default).
        val balanced = Argon2Params(16, 3, 1)
        assertEquals(balanced, Argon2Params.decode(balanced.encode()))
        assertNotEquals(balanced, Argon2Params(16, 4, 1))
    }
}
