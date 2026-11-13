package com.turnypt.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.5.1: pure heap-budget arithmetic for the Argon2id pre-flight check
 * (the 256 MiB PARANOID preset OOM-crashed before this guard existed).
 */
class Argon2MemoryGuardTest {

    private val mib = 1024L * 1024L

    @Test
    fun `required heap is 1024 bytes per KiB of profile memory`() {
        // 2^18 KiB = 256 MiB profile -> a single 256 MiB int[] allocation.
        assertEquals(
            256L * mib,
            Argon2MemoryGuard.requiredHeapBytes(Argon2Params(18, 1, 1)),
        )
        // 2^14 KiB = 16 MiB (INTERACTIVE preset).
        assertEquals(
            16L * mib,
            Argon2MemoryGuard.requiredHeapBytes(Argon2Params(14, 3, 1)),
        )
    }

    @Test
    fun `a 512 MiB heap serves the 256 MiB profile with headroom`() {
        assertTrue(Argon2MemoryGuard.fits(512L * mib, Argon2Params(18, 4, 1)))
    }

    @Test
    fun `a 256 MiB stock heap rejects the 256 MiB profile`() {
        // 256 MiB * 1.25 headroom = 320 MiB > 256 MiB: the exact crash
        // scenario reported on devices without largeHeap.
        assertFalse(Argon2MemoryGuard.fits(256L * mib, Argon2Params(18, 4, 1)))
    }

    @Test
    fun `a 256 MiB heap still serves the 64 MiB default profile`() {
        assertTrue(Argon2MemoryGuard.fits(256L * mib, Argon2Params(16, 3, 1)))
    }

    @Test
    fun `unbounded heap never rejects`() {
        assertTrue(Argon2MemoryGuard.fits(Long.MAX_VALUE, Argon2Params(22, 4, 1)))
    }

    @Test
    fun `budget comparison is overflow safe`() {
        // 4 GiB profile against a Long.MAX_VALUE-ish heap must not overflow
        // into a false negative.
        assertTrue(Argon2MemoryGuard.fits(Long.MAX_VALUE, Argon2Params(22, 1, 1)))
        // And a huge required size against a small heap stays a rejection.
        assertFalse(Argon2MemoryGuard.fits(64L * mib, Argon2Params(22, 1, 1)))
    }
}
