package com.turnypt.core.sessions

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import com.turnypt.core.common.ContactId
import com.turnypt.core.common.SessionId
import com.turnypt.core.crypto.Argon2KeyDerivator
import com.turnypt.core.crypto.Argon2Params
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeySessionManagerTest {

    private class MutableTimeSource(var now: Long) : TimeSource {
        override fun nowMillis(): Long = now
    }

    private val time = MutableTimeSource(1_000_000L)
    private val fastParams = Argon2Params(memoryExponent = 3, iterations = 1, parallelism = 1)
    private val contactA = ContactId("contact-a")
    private val contactB = ContactId("contact-b")

    private fun manager() = KeySessionManager(
        derivator = Argon2KeyDerivator(),
        timeSource = time,
    )

    private suspend fun unlock(manager: KeySessionManager, contactId: ContactId, passphrase: String, saltSeed: Int): SessionKey {
        val derived = manager.deriveKey(passphrase.toCharArray(), salt(saltSeed), fastParams)
        return manager.activate(
            contactId = contactId,
            sessionId = SessionId("session-$contactId-$saltSeed"),
            keyId = ByteArray(16) { (it + saltSeed).toByte() },
            salt = salt(saltSeed),
            params = fastParams,
            keyBytes = derived,
            timeoutMillis = 60_000,
        )
    }

    @Test
    fun `activate then activeSession returns the session`() = runTest {
        val manager = manager()
        val sessionKey = unlock(manager, contactA, "passphrase", 1)
        assertEquals(sessionKey.sessionId, manager.activeSession(contactA)?.sessionId)
    }

    @Test
    fun `expiry wipes the session`() = runTest {
        val manager = manager()
        val sessionKey = unlock(manager, contactA, "passphrase", 1)
        time.now += 60_001
        assertNull(manager.activeSession(contactA))
        // The key material of the expired session was wiped.
        assertTrue(sessionKey.keyBytes.all { it == 0.toByte() })
    }

    @Test
    fun `lock wipes the session`() = runTest {
        val manager = manager()
        val sessionKey = unlock(manager, contactA, "passphrase", 1)
        assertTrue(manager.lock(contactA))
        assertNull(manager.activeSession(contactA))
        assertTrue(sessionKey.keyBytes.all { it == 0.toByte() })
        assertFalse(manager.lock(contactA)) // second lock: nothing to lock
    }

    @Test
    fun `replacement wipes the previous session key`() = runTest {
        val manager = manager()
        val first = unlock(manager, contactA, "passphrase", 1)
        unlock(manager, contactA, "different-pass", 2)
        assertTrue(first.keyBytes.all { it == 0.toByte() })
        assertEquals(SessionId("session-contact-a-2"), manager.activeSession(contactA)?.sessionId)
    }

    @Test
    fun `activate copies keyBytes from the caller`() = runTest {
        val manager = manager()
        val derived = manager.deriveKey("passphrase".toCharArray(), salt(1), fastParams)
        val sessionKey = manager.activate(
            contactId = contactA,
            sessionId = SessionId("session-copy"),
            keyId = ByteArray(16) { 1 },
            salt = salt(1),
            params = fastParams,
            keyBytes = derived,
            timeoutMillis = 60_000,
        )
        // Deep-scan finding 1: zeroing the caller's buffer (as the
        // repository does around verifier persistence) must not corrupt
        // the live session — previously the array was stored by reference.
        derived.fill(0)
        assertTrue(sessionKey.keyBytes.any { it != 0.toByte() })
        assertEquals(sessionKey.sessionId, manager.activeSession(contactA)?.sessionId)
    }

    @Test
    fun `sweepExpired wipes expired sessions without a query`() = runTest {
        val manager = manager()
        val a = unlock(manager, contactA, "pass", 1)
        val b = unlock(manager, contactB, "pass", 2)
        time.now += 61_000
        // Deep-scan finding 8: expiry is lazy — the sweeper is what bounds
        // how long an expired, unqueried key stays in memory.
        assertEquals(2, manager.sweepExpired())
        assertTrue(a.keyBytes.all { it == 0.toByte() })
        assertTrue(b.keyBytes.all { it == 0.toByte() })
        assertNull(manager.activeSession(contactA))
        assertNull(manager.activeSession(contactB))
        // Idempotent: nothing left to sweep.
        assertEquals(0, manager.sweepExpired())
    }

    @Test
    fun `sessions of different contacts are isolated`() = runTest {
        val manager = manager()
        val keyA = unlock(manager, contactA, "shared-pass", 1)
        val keyB = unlock(manager, contactB, "shared-pass", 2)
        // Same passphrase, different salt -> different keys.
        assertFalse(keyA.keyBytes.contentEquals(keyB.keyBytes))
        assertNotEquals(keyA.fingerprint, keyB.fingerprint)
        // Lookup never crosses contacts.
        assertEquals(keyA.sessionId, manager.activeSession(contactA)?.sessionId)
        assertEquals(keyB.sessionId, manager.activeSession(contactB)?.sessionId)
    }

    @Test
    fun `locking one contact leaves the other intact`() = runTest {
        val manager = manager()
        val keyA = unlock(manager, contactA, "shared-pass", 1)
        val keyB = unlock(manager, contactB, "shared-pass", 2)
        manager.lock(contactA)
        assertNull(manager.activeSession(contactA))
        assertEquals(keyB.sessionId, manager.activeSession(contactB)?.sessionId)
        assertFalse(keyB.keyBytes.all { it == 0.toByte() })
        assertTrue(keyA.keyBytes.all { it == 0.toByte() })
    }

    @Test
    fun `lockAll wipes everything and reports the count`() = runTest {
        val manager = manager()
        unlock(manager, contactA, "shared-pass", 1)
        unlock(manager, contactB, "shared-pass", 2)
        assertEquals(2, manager.lockAll())
        assertNull(manager.activeSession(contactA))
        assertNull(manager.activeSession(contactB))
        assertTrue(manager.unlockedContactIds().isEmpty())
    }

    @Test
    fun `unlockedContactIds sweeps expired sessions`() = runTest {
        val manager = manager()
        unlock(manager, contactA, "pass", 1)
        unlock(manager, contactB, "pass", 2)
        time.now += 61_000
        assertTrue(manager.unlockedContactIds().isEmpty())
    }

    @Test
    fun `expiryOf reports the absolute deadline`() = runTest {
        val manager = manager()
        unlock(manager, contactA, "pass", 1)
        assertEquals(1_060_000L, manager.expiryOf(contactA))
        assertNull(manager.expiryOf(contactB))
    }

    @Test
    fun `deriveKey is deterministic and salt-sensitive`() = runTest {
        val manager = manager()
        val a1 = manager.deriveKey("shared".toCharArray(), salt(1), fastParams)
        val a2 = manager.deriveKey("shared".toCharArray(), salt(1), fastParams)
        val b = manager.deriveKey("shared".toCharArray(), salt(2), fastParams)
        assertArrayEquals(a1, a2)
        assertFalse(a1.contentEquals(b))
    }

    // ------------------------------------------- historical (v0.4.0)

    private suspend fun unlockHistorical(
        manager: KeySessionManager,
        contactId: ContactId,
        sessionId: SessionId,
        saltSeed: Int,
    ): SessionKey {
        val derived = manager.deriveKey("old-pass".toCharArray(), salt(saltSeed), fastParams)
        return manager.activateHistorical(
            contactId = contactId,
            sessionId = sessionId,
            keyId = ByteArray(16) { (it + saltSeed).toByte() },
            salt = salt(saltSeed),
            params = fastParams,
            keyBytes = derived,
            timeoutMillis = 60_000,
        )
    }

    @Test
    fun `historical session is returned by historicalSession`() = runTest {
        val manager = manager()
        val sessionKey = unlockHistorical(manager, contactA, SessionId("old-era-1"), 1)
        assertEquals(sessionKey.sessionId, manager.historicalSession(SessionId("old-era-1"))?.sessionId)
    }

    @Test
    fun `historical session is invisible to activeSession lookups`() = runTest {
        val manager = manager()
        unlockHistorical(manager, contactA, SessionId("old-era-1"), 1)
        // An old-era key must never silently act as the contact's session.
        assertNull(manager.activeSession(contactA))
        assertEquals(0, manager.unlockedContactIds().size)
    }

    @Test
    fun `historical expiry wipes the session`() = runTest {
        val manager = manager()
        val sessionKey = unlockHistorical(manager, contactA, SessionId("old-era-1"), 1)
        time.now += 60_001
        assertNull(manager.historicalSession(SessionId("old-era-1")))
        assertTrue(sessionKey.keyBytes.all { it == 0.toByte() })
    }

    @Test
    fun `replacing a historical session wipes the previous copy`() = runTest {
        val manager = manager()
        val first = unlockHistorical(manager, contactA, SessionId("old-era-1"), 1)
        unlockHistorical(manager, contactA, SessionId("old-era-1"), 2)
        assertTrue(first.keyBytes.all { it == 0.toByte() })
    }

    @Test
    fun `historical cache is bounded and evicts the least recently used`() = runTest {
        val manager = manager()
        val ids = (1..KeySessionManager.MAX_HISTORICAL_SESSIONS).map { SessionId("era-$it") }
        ids.forEachIndexed { index, id -> unlockHistorical(manager, contactA, id, index + 1) }
        assertEquals(KeySessionManager.MAX_HISTORICAL_SESSIONS, ids.size)

        // Touch era-1: it becomes the most recently used.
        assertNotNull(manager.historicalSession(ids.first()))
        // One beyond the bound: the eldest non-touched entry (era-2) is evicted.
        val overflow = unlockHistorical(manager, contactA, SessionId("era-overflow"), 99)

        assertNull(manager.historicalSession(ids[1]))                 // era-2 wiped
        assertEquals(ids.first(), manager.historicalSession(ids.first())?.sessionId) // era-1 kept
        assertEquals(overflow.sessionId, manager.historicalSession(SessionId("era-overflow"))?.sessionId)
    }

    @Test
    fun `lock wipes the contact's historical sessions too`() = runTest {
        val manager = manager()
        val historical = unlockHistorical(manager, contactA, SessionId("old-era-1"), 1)
        val activeB = unlock(manager, contactB, "shared-pass", 2)
        assertTrue(manager.lock(contactA))
        assertTrue(historical.keyBytes.all { it == 0.toByte() })
        assertNull(manager.historicalSession(SessionId("old-era-1")))
        // Other contacts keep their sessions.
        assertEquals(activeB.sessionId, manager.activeSession(contactB)?.sessionId)
    }

    @Test
    fun `lockAll counts active plus historical sessions`() = runTest {
        val manager = manager()
        unlock(manager, contactA, "pass", 1)
        unlockHistorical(manager, contactB, SessionId("old-b1"), 2)
        unlockHistorical(manager, contactB, SessionId("old-b2"), 3)
        assertEquals(3, manager.lockAll())
        assertNull(manager.historicalSession(SessionId("old-b1")))
        assertNull(manager.activeSession(contactA))
    }

    @Test
    fun `sweepExpired also sweeps historical sessions`() = runTest {
        val manager = manager()
        val historical = unlockHistorical(manager, contactA, SessionId("old-era-1"), 1)
        unlock(manager, contactB, "pass", 2)
        time.now += 61_000
        assertEquals(2, manager.sweepExpired())
        assertTrue(historical.keyBytes.all { it == 0.toByte() })
        assertNull(manager.historicalSession(SessionId("old-era-1")))
    }

    @Test
    fun `fingerprint is stable for identical key material`() = runTest {
        val manager = manager()
        val s1 = unlock(manager, contactA, "pass", 1)
        assertEquals(s1.fingerprint, s1.fingerprint)
        // 6 groups of 3 decimal digits joined by colons: 6*3 + 5 = 23.
        assertEquals(23, s1.fingerprint.length)
    }

    // ------------------------------------------------- v0.4.2 sessionChanges

    /**
     * v0.4.2 (deep-scan N1): every state change must bump the counter the
     * UIs collect to re-read "who is unlocked" — and a no-op must not.
     */
    @Test
    fun `sessionChanges bumps on activate lock and lockAll`() = runTest {
        val manager = manager()
        val before = manager.sessionChanges.value

        unlock(manager, contactA, "passphrase", 1)
        val afterActivate = manager.sessionChanges.value
        assertTrue(afterActivate > before)

        assertTrue(manager.lock(contactA))
        val afterLock = manager.sessionChanges.value
        assertTrue(afterLock > afterActivate)

        // Nothing left to lock: lockAll is a no-op and must NOT bump.
        assertEquals(0, manager.lockAll())
        assertEquals(afterLock, manager.sessionChanges.value)

        // With something to wipe, lockAll bumps again.
        unlock(manager, contactB, "passphrase", 2)
        assertEquals(1, manager.lockAll())
        assertTrue(manager.sessionChanges.value > afterLock)
    }

    /** The lazy expiry path inside activeSession must bump too. */
    @Test
    fun `sessionChanges bumps on lazy expiry`() = runTest {
        val manager = manager()
        unlock(manager, contactA, "passphrase", 1)
        val before = manager.sessionChanges.value

        time.now += 60_001
        assertNull(manager.activeSession(contactA))
        assertTrue(manager.sessionChanges.value > before)
    }

    /** The background sweeper's expiry must bump the counter as well. */
    @Test
    fun `sessionChanges bumps on sweep`() = runTest {
        val manager = manager()
        unlock(manager, contactA, "passphrase", 1)
        val before = manager.sessionChanges.value

        time.now += 60_001
        assertEquals(1, manager.sweepExpired())
        assertTrue(manager.sessionChanges.value > before)
    }

    private fun salt(seed: Int) = ByteArray(16) { ((it + seed * 7) % 256).toByte() }
}
