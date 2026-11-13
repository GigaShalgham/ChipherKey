package com.turnypt.security.policies

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class KeyPolicyTest {

    @Test
    fun `short passphrase is rejected`() {
        assertNotNull(KeyPolicy.passphraseProblem("short".toCharArray()))
    }

    @Test
    fun `eight character passphrase is accepted`() {
        assertNull(KeyPolicy.passphraseProblem("12345678".toCharArray()))
    }

    @Test
    fun `whitespace only passphrase is rejected`() {
        assertNotNull(KeyPolicy.passphraseProblem("        ".toCharArray()))
    }

    @Test
    fun `oversized passphrase is rejected`() {
        assertNotNull(KeyPolicy.passphraseProblem(CharArray(KeyPolicy.MAX_PASSPHRASE_CHARS + 1)))
    }

    @Test
    fun `persian passphrase is accepted`() {
        assertNull(KeyPolicy.passphraseProblem("رمز عبور امن و بلند".toCharArray()))
    }

    @Test
    fun `timeout is clamped into the legal range`() {
        assertEquals(
            KeyPolicy.MIN_SESSION_TIMEOUT_MILLIS,
            KeyPolicy.clampSessionTimeout(0),
        )
        assertEquals(
            KeyPolicy.MAX_SESSION_TIMEOUT_MILLIS,
            KeyPolicy.clampSessionTimeout(Long.MAX_VALUE / 2),
        )
        assertEquals(
            5 * 60_000L,
            KeyPolicy.clampSessionTimeout(5 * 60_000L),
        )
    }

    @Test
    fun `profiles expose distinct parameter sets within wire limits`() {
        assertEquals(3, Argon2Profile.entries.size)
        val memories = Argon2Profile.entries.map { it.params.memoryKib }.toSet()
        assertEquals(3, memories.size)
        assertEquals(Argon2Profile.BALANCED, Argon2Profile.DEFAULT)
        assertEquals("BALANCED", Argon2Profile.fromName("balanced")?.name)
        assertEquals(null, Argon2Profile.fromName("nope"))
    }
}
