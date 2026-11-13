package com.turnypt.data.repository

import kotlinx.coroutines.test.runTest
import com.turnypt.core.messages.InviteCodec
import com.turnypt.core.messages.SessionInvite
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionKeyRepositoryTest {

    @Test
    fun `createEra generates fresh key material each time`() = runTest {
        val device = TestDevice()
        val (contact, era1) = device.newContact("Bob")
        val era2 = device.sessionKeys.createEra(contact.id, TestDevice.FAST_PARAMS)
        assertTrue(!era1.keyId.contentEquals(era2.keyId))
        assertTrue(!era1.salt.contentEquals(era2.salt))
        assertEquals(2, device.sessionKeyDao.state.value.size)
    }

    // ------------------------------------------------- v0.5.2 awaitingFirstPassphrase

    @Test
    fun `a locally created era awaits its first passphrase`() = runTest {
        val device = TestDevice()
        val (contact, era) = device.newContact("Bob")
        // No friend invite was entered and the era was never unlocked here:
        // THIS side is choosing the shared passphrase right now.
        assertTrue(era.awaitingFirstPassphrase)
        assertTrue(device.sessionKeys.awaitingFirstPassphrase(contact.id))
        assertTrue(device.sessionKeys.awaitingFirstPassphrase(contact.id, era.sessionId))
    }

    @Test
    fun `an imported era never awaits a first passphrase`() = runTest {
        val device = TestDevice()
        val contact = device.contacts.createContact("Alice")
        val invite = SessionInvite(ByteArray(16) { 7 }, ByteArray(16) { 8 }, TestDevice.FAST_PARAMS)
        val imported = device.sessionKeys.importEra(contact.id, invite) as EraImport.Imported
        // The peer already picked the passphrase — suggesting one is dishonest.
        assertTrue(!imported.era.awaitingFirstPassphrase)
        assertTrue(!device.sessionKeys.awaitingFirstPassphrase(contact.id))
    }

    @Test
    fun `a locally created era stops awaiting after the first unlock stores a wrap`() = runTest {
        val device = TestDevice()
        val (contact, era) = device.newContact("Bob")
        // First unlock on this device persists the wrapped verifier…
        device.sessionKeys.storeWrappedKey(era.sessionId, byteArrayOf(1, 2, 3))
        // …so the passphrase is now established: no more suggestion.
        assertTrue(!device.sessionKeys.awaitingFirstPassphrase(contact.id))
        assertTrue(!device.sessionKeys.awaitingFirstPassphrase(contact.id, era.sessionId))
    }

    @Test
    fun `a contact without eras never awaits a passphrase`() = runTest {
        val device = TestDevice()
        val contact = device.contacts.createContact("Ghost")
        assertTrue(!device.sessionKeys.awaitingFirstPassphrase(contact.id))
    }

    @Test
    fun `latestEra returns the newest era`() = runTest {
        val device = TestDevice()
        val (contact, _) = device.newContact("Bob")
        device.time.now += 10_000
        val era2 = device.sessionKeys.createEra(contact.id, TestDevice.FAST_PARAMS)
        assertEquals(era2.sessionId, device.sessionKeys.latestEra(contact.id)?.sessionId)
    }

    @Test
    fun `eraByKeyId finds eras across contacts`() = runTest {
        val device = TestDevice()
        val (contact, era) = device.newContact("Bob")
        val found = device.sessionKeys.eraByKeyId(era.keyId)
        assertNotNull(found)
        assertEquals(contact.id, found!!.contactId)
        assertNull(device.sessionKeys.eraByKeyId(ByteArray(16)))
    }

    @Test
    fun `importEra stores the peer parameters verbatim`() = runTest {
        val device = TestDevice()
        val contact = device.contacts.createContact("Alice")
        val invite = SessionInvite(
            keyId = ByteArray(16) { (it + 1).toByte() },
            salt = ByteArray(16) { (it + 50).toByte() },
            params = TestDevice.FAST_PARAMS,
        )
        val result = device.sessionKeys.importEra(contact.id, invite)
        assertTrue(result is EraImport.Imported)
        val imported = (result as EraImport.Imported).era
        assertArrayEquals(invite.keyId, imported.keyId)
        assertArrayEquals(invite.salt, imported.salt)
        assertEquals(invite.params, imported.params)
    }

    @Test
    fun `importing the same key twice for the same contact is idempotent`() = runTest {
        val device = TestDevice()
        val contact = device.contacts.createContact("Alice")
        val invite = SessionInvite(ByteArray(16) { 1 }, ByteArray(16) { 2 }, TestDevice.FAST_PARAMS)
        device.sessionKeys.importEra(contact.id, invite)
        val second = device.sessionKeys.importEra(contact.id, invite)
        assertTrue(second is EraImport.AlreadyPresent)
        assertEquals(1, device.sessionKeyDao.state.value.size)
    }

    @Test
    fun `importing a key owned by another contact is refused`() = runTest {
        val device = TestDevice()
        val bob = device.contacts.createContact("Bob")
        val alice = device.contacts.createContact("Alice")
        val invite = SessionInvite(ByteArray(16) { 3 }, ByteArray(16) { 4 }, TestDevice.FAST_PARAMS)
        device.sessionKeys.importEra(bob.id, invite)
        val result = device.sessionKeys.importEra(alice.id, invite)
        assertTrue(result is EraImport.KeyBelongsToOtherContact)
    }

    @Test
    fun `invite code roundtrips through the CKS1 codec`() = runTest {
        val device = TestDevice()
        val (_, era) = device.newContact("Bob")
        val decoded = InviteCodec.decode(era.inviteCode)
        assertArrayEquals(era.keyId, decoded.keyId)
        assertArrayEquals(era.salt, decoded.salt)
        assertEquals(era.params, decoded.params)
    }

    @Test
    fun `wrapped verifier persists and loads`() = runTest {
        val device = TestDevice()
        val (_, era) = device.newContact("Bob")
        assertNull(device.sessionKeys.wrappedKeyOf(era.sessionId))
        val blob = device.protector.wrap(ByteArray(32) { 7 }, era.keyId)
        device.sessionKeys.storeWrappedKey(era.sessionId, blob)
        val loaded = device.sessionKeys.wrappedKeyOf(era.sessionId)
        assertNotNull(loaded)
        assertArrayEquals(blob, loaded)
    }

    @Test
    fun `erasForContact lists newest first`() = runTest {
        val device = TestDevice()
        val (contact, era1) = device.newContact("Bob")
        device.time.now += 10_000
        val era2 = device.sessionKeys.createEra(contact.id, TestDevice.FAST_PARAMS)
        val eras = device.sessionKeys.erasForContact(contact.id)
        assertEquals(listOf(era2.sessionId, era1.sessionId), eras.map { it.sessionId })
    }

    @Test
    fun `same-millisecond eras resolve deterministically`() = runTest {
        val device = TestDevice()
        val (contact, era1) = device.newContact("Bob")
        // NO clock advance: both eras share a createdAt timestamp, so the
        // deterministic tiebreak (sessionId DESC, deep-scan finding 16)
        // decides which era is "latest".
        val era2 = device.sessionKeys.createEra(contact.id, TestDevice.FAST_PARAMS)
        val expectedWinner = listOf(era1, era2).maxByOrNull { it.sessionId.value }!!

        assertEquals(expectedWinner.sessionId, device.sessionKeys.latestEra(contact.id)?.sessionId)
        assertEquals(
            expectedWinner.sessionId,
            device.sessionKeys.erasForContact(contact.id).first().sessionId,
        )
        // Stable across repeated queries.
        repeat(3) {
            assertEquals(expectedWinner.sessionId, device.sessionKeys.latestEra(contact.id)?.sessionId)
        }
    }

    /**
     * v0.4.2 (deep-scan N2): era writes bump the counter open UIs collect to
     * drop their stale "unlocked" snapshot and re-check the fingerprint
     * offer. Reads and no-op imports must not bump it.
     */
    @Test
    fun `eraChanges bumps on create import and delete only`() = runTest {
        val device = TestDevice()
        val contact = device.contacts.createContact("Bob")
        val before = device.sessionKeys.eraChanges.value

        // A read must not bump.
        device.sessionKeys.latestEra(contact.id)
        assertEquals(before, device.sessionKeys.eraChanges.value)

        // Creating does.
        device.sessionKeys.createEra(contact.id, TestDevice.FAST_PARAMS)
        val afterCreate = device.sessionKeys.eraChanges.value
        assertTrue(afterCreate > before)

        // An idempotent re-import of the SAME key writes nothing: no bump.
        val invite = InviteCodec.decode(
            device.sessionKeys.latestEra(contact.id)!!.inviteCode,
        )
        val second = device.sessionKeys.importEra(contact.id, invite)
        assertTrue(second is EraImport.AlreadyPresent)
        assertEquals(afterCreate, device.sessionKeys.eraChanges.value)

        // Deleting the contact's eras does.
        device.sessionKeys.deleteAllForContact(contact.id)
        assertTrue(device.sessionKeys.eraChanges.value > afterCreate)
    }
}
