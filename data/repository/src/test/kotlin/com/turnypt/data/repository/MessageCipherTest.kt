package com.turnypt.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import com.turnypt.core.crypto.CryptoError
import com.turnypt.core.keys.KeyProtectionException
import com.turnypt.core.keys.KeyProtector
import com.turnypt.core.messages.Ck1
import com.turnypt.core.messages.InviteCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end tests of the composition facade. "Two devices" are two
 * independent [TestDevice]s (separate databases, separate key protectors —
 * emulating device-bound Keystores — and separate session managers), exactly
 * like two phones sharing a passphrase and a CKS1 invite.
 */
class MessageCipherTest {

    private val passphrase = "correct horse battery staple"

    /** Sender creates a contact; receiver imports the invite. Both unlock. */
    private suspend fun pairedDevices(): Pair<TestDevice, TestDevice> {
        val sender = TestDevice()
        val receiver = TestDevice()
        val (contact, era) = sender.newContact("Bob")
        val invite = InviteCodec.decode(era.inviteCode)
        val alice = receiver.contacts.createContact("Alice")
        receiver.sessionKeys.importEra(alice.id, invite)
        assertTrue(sender.cipher.unlockContact(contact.id, passphrase.toCharArray()) is UnlockOutcome.Unlocked)
        assertTrue(receiver.cipher.unlockContact(alice.id, passphrase.toCharArray()) is UnlockOutcome.Unlocked)
        return sender to receiver
    }

    @Test
    fun `two devices round trip a message`() = runTest {
        val (sender, receiver) = pairedDevices()
        val contactId = sender.theSenderContact()

        val outcome = sender.cipher.encryptDraft(contactId, "meet me at the usual place")
        assertTrue(outcome is EncryptOutcome.Encrypted)
        val envelope = (outcome as EncryptOutcome.Encrypted).envelope
        // v1.1.0: written envelopes carry the Turnypt prefix.
        assertTrue(envelope.startsWith("TNP1:"))

        val decrypted = receiver.cipher.decryptEnvelope(envelope)
        assertTrue(decrypted is DecryptOutcome.Decrypted)
        val result = decrypted as DecryptOutcome.Decrypted
        assertEquals("meet me at the usual place", result.plaintext)
        assertEquals("Alice", result.contactName)
    }

    @Test
    fun `wrong passphrase is rejected on the second unlock`() = runTest {
        val device = TestDevice()
        val (contact, _) = device.newContact("Bob")
        // First unlock stores the Keystore-wrapped verifier.
        assertTrue(device.cipher.unlockContact(contact.id, passphrase.toCharArray()) is UnlockOutcome.Unlocked)
        device.cipher.lockContact(contact.id)
        // Second unlock with the wrong passphrase must fail verification.
        val outcome = device.cipher.unlockContact(contact.id, "wrong passphrase here".toCharArray())
        assertTrue(outcome is UnlockOutcome.WrongPassphrase)
        assertNull(device.cipher.activeSessionFingerprint(contact.id))
    }

    @Test
    fun `stored verifier is a digest, never the raw session key`() = runTest {
        val device = TestDevice()
        val (contact, era) = device.newContact("Bob")
        assertTrue(device.cipher.unlockContact(contact.id, passphrase.toCharArray()) is UnlockOutcome.Unlocked)

        val wrapped = device.sessionKeys.wrappedKeyOf(era.sessionId)
        assertNotNull(wrapped)
        val stored = device.protector.unwrap(wrapped!!, era.keyId)

        // v2 format: 0x02 || SHA-256(key) — 33 bytes, not the 32-byte key.
        assertEquals(33, stored.size)
        assertEquals(0x02, stored[0].toInt())
        val rawKey = device.sessions.deriveKey(passphrase.toCharArray(), era.salt, era.params)
        assertFalse(stored.contentEquals(rawKey))
        val expectedDigest = java.security.MessageDigest.getInstance("SHA-256").digest(rawKey)
        assertTrue(expectedDigest.contentEquals(stored.copyOfRange(1, stored.size)))
    }

    @Test
    fun `legacy full-key verifier still unlocks and migrates to the digest form`() = runTest {
        val device = TestDevice()
        val (contact, era) = device.newContact("Bob")
        // Simulate a database written by <= 0.2.1: the wrapped blob
        // decrypts to the FULL 32-byte session key.
        val rawKey = device.sessions.deriveKey(passphrase.toCharArray(), era.salt, era.params)
        device.sessionKeys.storeWrappedKey(era.sessionId, device.protector.wrap(rawKey, era.keyId))
        device.cipher.lockContact(contact.id)

        val outcome = device.cipher.unlockContact(contact.id, passphrase.toCharArray())
        assertTrue(outcome is UnlockOutcome.Unlocked)

        // Transparently migrated to the v2 digest form.
        val migrated = device.protector.unwrap(
            device.sessionKeys.wrappedKeyOf(era.sessionId)!!,
            era.keyId,
        )
        assertEquals(33, migrated.size)
        assertEquals(0x02, migrated[0].toInt())

        // And the migrated verifier still rejects a wrong passphrase.
        device.cipher.lockContact(contact.id)
        assertTrue(
            device.cipher.unlockContact(contact.id, "wrong passphrase here".toCharArray())
                is UnlockOutcome.WrongPassphrase,
        )
    }

    @Test
    fun `keystore infrastructure failure is failed, not wrong passphrase`() = runTest {
        // Emulates a Keystore master key invalidated by a system update:
        // wrap works, unwrap always fails (deep-scan finding 2).
        val device = TestDevice(protector = FailingKeystoreProtector)
        val (contact, era) = device.newContact("Bob")
        val rawKey = device.sessions.deriveKey(passphrase.toCharArray(), era.salt, era.params)
        device.sessionKeys.storeWrappedKey(era.sessionId, device.protector.wrap(rawKey, era.keyId))

        val outcome = device.cipher.unlockContact(contact.id, passphrase.toCharArray())
        // The passphrase is CORRECT — an infrastructure failure must not
        // tell the user to retype it.
        assertTrue(outcome is UnlockOutcome.Failed)
        assertTrue((outcome as UnlockOutcome.Failed).reason.contains("Keystore error"))
        assertNull(device.cipher.activeSessionFingerprint(contact.id))
    }

    @Test
    fun `wrong passphrase on a fresh device is caught by authentication`() = runTest {
        val sender = TestDevice()
        val receiver = TestDevice()
        val (contact, era) = sender.newContact("Bob")
        val invite = InviteCodec.decode(era.inviteCode)
        val alice = receiver.contacts.createContact("Alice")
        receiver.sessionKeys.importEra(alice.id, invite)

        senderUnlock(sender, contact.id)
        val envelope =
            (sender.cipher.encryptDraft(contact.id, "top secret") as EncryptOutcome.Encrypted).envelope

        // Fresh device has no verifier yet: the wrong passphrase "unlocks"…
        assertTrue(
            receiver.cipher.unlockContact(alice.id, "wrong passphrase here".toCharArray())
                is UnlockOutcome.Unlocked,
        )
        // …but the AEAD authentication rejects the message.
        val outcome = receiver.cipher.decryptEnvelope(envelope)
        assertTrue(outcome is DecryptOutcome.Failed)
        assertEquals(CryptoError.AuthenticationFailed, (outcome as DecryptOutcome.Failed).error)
    }

    private suspend fun senderUnlock(device: TestDevice, contactId: com.turnypt.core.common.ContactId) {
        device.cipher.unlockContact(contactId, passphrase.toCharArray())
    }

    @Test
    fun `tampered ciphertext is rejected`() = runTest {
        val (sender, receiver) = pairedDevices()
        val contactId = sender.theSenderContact()
        val envelope = (sender.cipher.encryptDraft(contactId, "integrity check") as EncryptOutcome.Encrypted).envelope

        val tampered = flipEnvelopeByte(envelope, 60) // inside the ciphertext region
        val outcome = receiver.cipher.decryptEnvelope(tampered)
        assertTrue(outcome is DecryptOutcome.Failed)
        assertEquals(CryptoError.AuthenticationFailed, (outcome as DecryptOutcome.Failed).error)
    }

    @Test
    fun `tampered header is rejected through aad binding`() = runTest {
        val (sender, receiver) = pairedDevices()
        val contactId = sender.theSenderContact()
        val envelope = (sender.cipher.encryptDraft(contactId, "aad check") as EncryptOutcome.Encrypted).envelope

        // Byte 30 sits inside the salt field (part of the authenticated header).
        val tampered = flipEnvelopeByte(envelope, 30)
        val outcome = receiver.cipher.decryptEnvelope(tampered)
        assertTrue(outcome is DecryptOutcome.Failed)
        assertEquals(CryptoError.AuthenticationFailed, (outcome as DecryptOutcome.Failed).error)
    }

    @Test
    fun `unknown key id is reported`() = runTest {
        val sender = TestDevice()
        val receiver = TestDevice()
        val (contact, _) = sender.newContact("Bob")
        senderUnlock(sender, contact.id)
        val envelope = (sender.cipher.encryptDraft(contact.id, "hello?") as EncryptOutcome.Encrypted).envelope
        // Receiver never imported the invite -> key unknown.
        val outcome = receiver.cipher.decryptEnvelope(envelope)
        assertTrue(outcome is DecryptOutcome.UnknownKey)
    }

    @Test
    fun `locked contact yields needs unlock with contact details`() = runTest {
        val (sender, receiver) = pairedDevices()
        val contactId = sender.theSenderContact()
        val envelope = (sender.cipher.encryptDraft(contactId, "secret message") as EncryptOutcome.Encrypted).envelope

        receiver.cipher.lockAll()
        val outcome = receiver.cipher.decryptEnvelope(envelope)
        assertTrue(outcome is DecryptOutcome.NeedsUnlock)
        val needs = outcome as DecryptOutcome.NeedsUnlock
        assertEquals("Alice", needs.contactName)
        assertNotNull(needs.contactId)
    }

    @Test
    fun `old key era envelope asks for and unlocks with its era passphrase`() = runTest {
        val (sender, receiver) = pairedDevices()
        val contactId = sender.theSenderContact()
        val aliceId = receiver.theSenderContact()

        val envelope = (sender.cipher.encryptDraft(contactId, "old era message") as EncryptOutcome.Encrypted).envelope

        // The receiver drops its active era-1 session, then rotates: the
        // latest era is era-2 from here on.
        receiver.cipher.lockAll()
        receiver.time.now += 10_000
        receiver.sessionKeys.createEra(aliceId, TestDevice.FAST_PARAMS)

        // 1) The old envelope asks for ITS era — not the contact's current one.
        val needs = receiver.cipher.decryptEnvelope(envelope)
        assertTrue(needs is DecryptOutcome.NeedsEraUnlock)
        val eraUnlock = needs as DecryptOutcome.NeedsEraUnlock
        assertEquals("Alice", eraUnlock.contactName)

        // 2) A wrong passphrase is rejected by the era-1 verifier that the
        //    first unlock persisted on this device.
        assertTrue(
            receiver.cipher.unlockEra(aliceId, eraUnlock.eraSessionId, "wrong passphrase here".toCharArray())
                is UnlockOutcome.WrongPassphrase,
        )

        // 3) The right passphrase unlocks the era and reads the envelope.
        assertTrue(
            receiver.cipher.unlockEra(aliceId, eraUnlock.eraSessionId, passphrase.toCharArray())
                is UnlockOutcome.Unlocked,
        )
        // An old-era unlock never becomes a sending session.
        assertTrue(!receiver.cipher.isUnlocked(aliceId))
        val decrypted = receiver.cipher.decryptEnvelope(envelope)
        assertTrue(decrypted is DecryptOutcome.Decrypted)
        assertEquals("old era message", (decrypted as DecryptOutcome.Decrypted).plaintext)

        // 4) The cached historical session serves another old-era envelope
        //    without asking for the passphrase again.
        val envelopeB = (sender.cipher.encryptDraft(contactId, "second old") as EncryptOutcome.Encrypted).envelope
        assertTrue(receiver.cipher.decryptEnvelope(envelopeB) is DecryptOutcome.Decrypted)
    }

    @Test
    fun `an old era still active serves its own envelopes`() = runTest {
        val (sender, receiver) = pairedDevices()
        val contactId = sender.theSenderContact()
        val envelope = (sender.cipher.encryptDraft(contactId, "era1 msg") as EncryptOutcome.Encrypted).envelope

        // Rotate while era-1 is still the receiver's ACTIVE session.
        val aliceId = receiver.theSenderContact()
        receiver.time.now += 10_000
        receiver.sessionKeys.createEra(aliceId, TestDevice.FAST_PARAMS)

        // era-1 is superseded but still in memory: its own envelope reads
        // without a new passphrase prompt.
        val outcome = receiver.cipher.decryptEnvelope(envelope)
        assertTrue(outcome is DecryptOutcome.Decrypted)
        assertEquals("era1 msg", (outcome as DecryptOutcome.Decrypted).plaintext)
    }

    @Test
    fun `stale active session is not used for a latest-era envelope`() = runTest {
        val (sender, receiver) = pairedDevices()
        val aliceId = receiver.theSenderContact()

        // The receiver stays unlocked under era-1 while rotating to era-2.
        receiver.time.now += 10_000
        val era2 = receiver.sessionKeys.createEra(aliceId, TestDevice.FAST_PARAMS)

        // A peer holding era-2 sends under it.
        val peer = TestDevice()
        val peerContact = peer.contacts.createContact("Peer")
        peer.sessionKeys.importEra(peerContact.id, InviteCodec.decode(era2.inviteCode))
        assertTrue(peer.cipher.unlockContact(peerContact.id, passphrase.toCharArray()) is UnlockOutcome.Unlocked)
        val envelope2 = (peer.cipher.encryptDraft(peerContact.id, "under era2") as EncryptOutcome.Encrypted).envelope

        // The active session belongs to era-1: it must not be tried against
        // an era-2 envelope (that would guarantee an AEAD failure).
        val outcome = receiver.cipher.decryptEnvelope(envelope2)
        assertTrue(outcome is DecryptOutcome.NeedsUnlock)
    }

    @Test
    fun `encrypt without unlock asks for the passphrase`() = runTest {
        val device = TestDevice()
        val (contact, _) = device.newContact("Bob")
        val outcome = device.cipher.encryptDraft(contact.id, "hello")
        assertTrue(outcome is EncryptOutcome.NeedsUnlock)
    }

    @Test
    fun `encrypt of empty draft is rejected`() = runTest {
        val device = TestDevice()
        val (contact, _) = device.newContact("Bob")
        device.cipher.unlockContact(contact.id, passphrase.toCharArray())
        val outcome = device.cipher.encryptDraft(contact.id, "")
        assertTrue(outcome is EncryptOutcome.EmptyDraft)
    }

    @Test
    fun `oversized plaintext is rejected`() = runTest {
        val device = TestDevice()
        val (contact, _) = device.newContact("Bob")
        device.cipher.unlockContact(contact.id, passphrase.toCharArray())
        val outcome = device.cipher.encryptDraft(contact.id, "x".repeat(16 * 1024 + 1))
        assertTrue(outcome is EncryptOutcome.Failed)
        assertEquals(CryptoError.InputTooLarge, (outcome as EncryptOutcome.Failed).error)
    }

    @Test
    fun `non envelope text is rejected early`() = runTest {
        val (_, receiver) = pairedDevices()
        assertTrue(receiver.cipher.decryptEnvelope("just some text") is DecryptOutcome.NotAnEnvelope)
        assertTrue(receiver.cipher.decryptEnvelope("") is DecryptOutcome.NotAnEnvelope)
    }

    @Test
    fun `persian and unicode content round trips`() = runTest {
        val (sender, receiver) = pairedDevices()
        val contactId = sender.theSenderContact()
        val persian = "سلام دنیا! این یک پیام محرمانه است. 🌙 تعداد: ۱۲۳۴۵\n" +
            "Second line with émojis 🚀 and «quotes» — and RTL marks."
        val envelope = (sender.cipher.encryptDraft(contactId, persian) as EncryptOutcome.Encrypted).envelope
        val outcome = receiver.cipher.decryptEnvelope(envelope)
        assertTrue(outcome is DecryptOutcome.Decrypted)
        assertEquals(persian, (outcome as DecryptOutcome.Decrypted).plaintext)
    }

    @Test
    fun `session expiry forces a fresh unlock`() = runTest {
        val device = TestDevice()
        val (contact, _) = device.newContact("Bob")
        device.cipher.unlockContact(contact.id, passphrase.toCharArray())
        assertTrue(device.cipher.isUnlocked(contact.id))

        device.time.now += 300_001 // past the injected 5-minute timeout
        assertTrue(!device.cipher.isUnlocked(contact.id))
        val outcome = device.cipher.encryptDraft(contact.id, "hello")
        assertTrue(outcome is EncryptOutcome.NeedsUnlock)
    }

    @Test
    fun `contacts are cryptographically isolated`() = runTest {
        val device = TestDevice()
        val (bob, _) = device.newContact("Bob")
        device.time.now += 10
        val (carol, _) = device.newContact("Carol")
        // Same passphrase on both contacts...
        device.cipher.unlockContact(bob.id, passphrase.toCharArray())
        device.cipher.unlockContact(carol.id, passphrase.toCharArray())
        // ...but the fingerprints (hence the keys) differ.
        val bobPrint = device.cipher.activeSessionFingerprint(bob.id)
        val carolPrint = device.cipher.activeSessionFingerprint(carol.id)
        assertNotNull(bobPrint)
        assertNotNull(carolPrint)
        assertTrue(bobPrint != carolPrint)
        // Locking Carol leaves Bob alone.
        device.cipher.lockContact(carol.id)
        assertTrue(device.cipher.isUnlocked(bob.id))
        assertTrue(!device.cipher.isUnlocked(carol.id))
    }

    @Test
    fun `two contacts cannot read each other's envelopes`() = runTest {
        val sender = TestDevice()
        val receiver = TestDevice()
        val (bob, bobEra) = sender.newContact("Bob")
        val (carol, carolEra) = sender.newContact("Carol")
        // Receiver imports ONLY Carol's invite.
        val carolContact = receiver.contacts.createContact("Carol")
        receiver.sessionKeys.importEra(carolContact.id, InviteCodec.decode(carolEra.inviteCode))

        senderUnlock(sender, bob.id)
        senderUnlock(sender, carol.id)
        receiver.cipher.unlockContact(carolContact.id, passphrase.toCharArray())

        val bobEnvelope = (sender.cipher.encryptDraft(bob.id, "for bob only") as EncryptOutcome.Encrypted).envelope
        // Carol's device-side lookup cannot resolve Bob's keyId.
        val outcome = receiver.cipher.decryptEnvelope(bobEnvelope)
        assertTrue(outcome is DecryptOutcome.UnknownKey)
        // And the unused era proves the invites are distinct.
        assertTrue(!bobEra.keyId.contentEquals(carolEra.keyId))
    }

    @Test
    fun `lockAll clears every session`() = runTest {
        val device = TestDevice()
        val (bob, _) = device.newContact("Bob")
        device.time.now += 10
        val (carol, _) = device.newContact("Carol")
        device.cipher.unlockContact(bob.id, passphrase.toCharArray())
        device.cipher.unlockContact(carol.id, passphrase.toCharArray())
        assertEquals(2, device.cipher.lockAll())
        assertTrue(!device.cipher.isUnlocked(bob.id))
        assertTrue(!device.cipher.isUnlocked(carol.id))
    }

    // --------------------------------------------- v0.5.1 heap pre-flight

    @Test
    fun `oversized Argon2 profile reports MemoryHungry instead of crashing`() = runTest {
        // The reported crash: the 256 MiB PARANOID preset on a stock heap.
        // Emulated with a tiny heap; unlockContact must map the typed
        // KdfMemoryException to UnlockOutcome.MemoryHungry — never an
        // OutOfMemoryError escaping to the process.
        val device = TestDevice(
            derivator = com.turnypt.core.crypto.Argon2KeyDerivator(
                maxHeapBytes = { 256L * 1024 * 1024 },
            ),
        )
        val (contact, _) = device.newContact(
            "Bob",
            com.turnypt.core.crypto.Argon2Params(memoryExponent = 18, iterations = 4, parallelism = 1),
        )
        val outcome = device.cipher.unlockContact(contact.id, passphrase.toCharArray())
        assertTrue("expected MemoryHungry, was $outcome", outcome is UnlockOutcome.MemoryHungry)
        assertEquals(256L * 1024 * 1024, (outcome as UnlockOutcome.MemoryHungry).requiredBytes)
        // Nothing was derived, so nothing stayed unlocked.
        assertFalse(device.cipher.isUnlocked(contact.id))
    }

    @Test
    fun `oversized draft is rejected before any big allocation`() = runTest {
        // The second reported crash shape: pasting a huge text and tapping
        // Encrypt used to materialise the full UTF-8 encoding (OOM). The
        // char-count guard rejects it with the typed InputTooLarge error.
        val device = TestDevice()
        val (contact, _) = device.newContact("Bob")
        device.cipher.unlockContact(contact.id, passphrase.toCharArray())
        val wall = "a".repeat(1024 * 1024)
        val outcome = device.cipher.encryptDraft(contact.id, wall)
        assertTrue(outcome is EncryptOutcome.Failed)
        assertEquals(CryptoError.InputTooLarge, (outcome as EncryptOutcome.Failed).error)
    }

    @Test
    fun `envelope survives leading and trailing whitespace`() = runTest {
        val (sender, receiver) = pairedDevices()
        val contactId = sender.theSenderContact()
        val envelope = (sender.cipher.encryptDraft(contactId, "trimmed") as EncryptOutcome.Encrypted).envelope
        // Messaging apps frequently add newlines around copied text.
        val outcome = receiver.cipher.decryptEnvelope("  $envelope\n")
        assertTrue(outcome is DecryptOutcome.Decrypted)
    }

    /** The (single) contact of a paired sender device. */
    private suspend fun TestDevice.theSenderContact(): com.turnypt.core.common.ContactId =
        contacts.observeContacts().first().single().id

    /** Flips one bit of the decoded payload at [byteIndex], re-encodes. */
    private fun flipEnvelopeByte(envelope: String, byteIndex: Int): String {
        val body = envelope.substring(Ck1.MAGIC.length)
        val payload = java.util.Base64.getUrlDecoder().decode(body)
        payload[byteIndex] = (payload[byteIndex].toInt() xor 0x01).toByte()
        return Ck1.MAGIC + Ck1.encodeBase64Url(payload)
    }

    /** Wrap is the identity; unwrap always throws — a dead Keystore. */
    private object FailingKeystoreProtector : KeyProtector {
        override fun wrap(plainKey: ByteArray, keyId: ByteArray): ByteArray = plainKey.copyOf()
        override fun unwrap(wrapped: ByteArray, keyId: ByteArray): ByteArray =
            throw KeyProtectionException("master key invalidated")
    }
}
