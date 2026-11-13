package com.turnypt.data.repository

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import com.turnypt.core.common.ContactId
import com.turnypt.core.crypto.CipherAlgorithm
import com.turnypt.core.crypto.Ckf1
import com.turnypt.core.crypto.CryptoError
import com.turnypt.core.messages.InviteCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The image/file facade over the SAME era, contact and session machinery
 * as the text path: two "devices" (see TestDevice) share a passphrase via
 * a CKS1 invite, then exchange a CKF1 container instead of a CK1 envelope.
 */
class MediaCipherTest {

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

    private fun imageBytes(size: Int): ByteArray =
        ByteArray(size) { ((it * 17 + 3) % 251).toByte() }

    @Test
    fun `two devices round trip an image as a ckf1 file`() = runTest {
        val (sender, receiver) = pairedDevices()
        val contactId = sender.theSenderContact()
        val image = imageBytes(200 * 1024 + 11) // multi-chunk

        val sealed = ByteArrayOutputStream()
        val outcome = sender.media.encryptFile(contactId, ByteArrayInputStream(image), sealed)
        assertTrue(outcome is MediaEncryptOutcome.Encrypted)
        assertEquals(CipherAlgorithm.AES_256_GCM, (outcome as MediaEncryptOutcome.Encrypted).algorithm)
        assertEquals(image.size.toLong(), outcome.plaintextBytes)
        assertTrue(Ckf1.hasMagic(sealed.toByteArray()))

        val opened = ByteArrayOutputStream()
        val decrypted = receiver.media.decryptFile(ByteArrayInputStream(sealed.toByteArray()), opened)
        assertTrue(decrypted is MediaDecryptOutcome.Decrypted)
        val result = decrypted as MediaDecryptOutcome.Decrypted
        assertArrayEquals(image, opened.toByteArray())
        // The receiver's LOCAL name for the sender is "Alice" (they created
        // the contact under that name when importing the invite).
        assertEquals("Alice", result.contactName)
        assertEquals(image.size.toLong(), result.plaintextBytes)
    }

    @Test
    fun `chacha preference is honored and recorded in the header`() = runTest {
        val (sender, receiver) = pairedDevices()
        val contactId = sender.theSenderContact()
        sender.mediaAlgorithm = CipherAlgorithm.CHACHA20_POLY1305

        val sealed = ByteArrayOutputStream()
        val outcome = sender.media.encryptFile(contactId, ByteArrayInputStream(imageBytes(1000)), sealed)
        assertTrue(outcome is MediaEncryptOutcome.Encrypted)
        assertEquals(CipherAlgorithm.CHACHA20_POLY1305, (outcome as MediaEncryptOutcome.Encrypted).algorithm)

        // The receiver's own preference is irrelevant — the header decides.
        val opened = ByteArrayOutputStream()
        assertTrue(
            receiver.media.decryptFile(ByteArrayInputStream(sealed.toByteArray()), opened)
                is MediaDecryptOutcome.Decrypted,
        )
    }

    @Test
    fun `locked contact asks for unlock before any crypto work`() = runTest {
        val device = TestDevice()
        val (contact, _) = device.newContact("Bob")
        // NOTE: never unlocked.
        val outcome = device.media.encryptFile(contact.id, ByteArrayInputStream(imageBytes(10)), ByteArrayOutputStream())
        assertEquals(MediaEncryptOutcome.NeedsUnlock, outcome)
    }

    @Test
    fun `stale unlocked era is refused and locked`() = runTest {
        val device = TestDevice()
        val (contact, _) = device.newContact("Bob")
        assertTrue(device.cipher.unlockContact(contact.id, passphrase.toCharArray()) is UnlockOutcome.Unlocked)
        // Rotate the era while the old session is still active (advance the
        // clock so the new era is unambiguously the latest).
        device.time.now += 10_000
        device.sessionKeys.createEra(contact.id, TestDevice.FAST_PARAMS)

        val outcome = device.media.encryptFile(contact.id, ByteArrayInputStream(imageBytes(10)), ByteArrayOutputStream())
        assertEquals(MediaEncryptOutcome.NeedsUnlock, outcome)
        assertTrue(!device.cipher.isUnlocked(contact.id))
    }

    @Test
    fun `no era yet is reported as no session`() = runTest {
        val device = TestDevice()
        val contact = device.contacts.createContact("Eve") // no era created
        val outcome = device.media.encryptFile(contact.id, ByteArrayInputStream(imageBytes(10)), ByteArrayOutputStream())
        assertEquals(MediaEncryptOutcome.NoSession, outcome)
    }

    @Test
    fun `non-ckf1 input is not a cipher file`() = runTest {
        val device = TestDevice()
        val outcome = device.media.decryptFile(
            ByteArrayInputStream("just a jpeg, not encrypted".toByteArray()),
            ByteArrayOutputStream(),
        )
        assertEquals(MediaDecryptOutcome.NotACipherFile, outcome)
    }

    @Test
    fun `unknown keyId is reported as unknown key`() = runTest {
        val sender = TestDevice()
        val (contact, _) = sender.newContact("Bob")
        assertTrue(sender.cipher.unlockContact(contact.id, passphrase.toCharArray()) is UnlockOutcome.Unlocked)
        val sealed = ByteArrayOutputStream()
        sender.media.encryptFile(contact.id, ByteArrayInputStream(imageBytes(10)), sealed)

        // A different device that never imported the invite.
        val stranger = TestDevice()
        val outcome = stranger.media.decryptFile(ByteArrayInputStream(sealed.toByteArray()), ByteArrayOutputStream())
        assertEquals(MediaDecryptOutcome.UnknownKey, outcome)
    }

    @Test
    fun `receiver locked means needs unlock with the contact resolved`() = runTest {
        val (sender, receiver) = pairedDevices()
        val contactId = sender.theSenderContact()
        val sealed = ByteArrayOutputStream()
        sender.media.encryptFile(contactId, ByteArrayInputStream(imageBytes(10)), sealed)

        receiver.cipher.lockAll()
        val outcome = receiver.media.decryptFile(ByteArrayInputStream(sealed.toByteArray()), ByteArrayOutputStream())
        assertTrue(outcome is MediaDecryptOutcome.NeedsUnlock)
        val needs = outcome as MediaDecryptOutcome.NeedsUnlock
        // The receiver's local contact name for the sender.
        assertEquals("Alice", needs.contactName)
    }

    @Test
    fun `tampered container fails authentication`() = runTest {
        val (sender, receiver) = pairedDevices()
        val contactId = sender.theSenderContact()
        val sealed = ByteArrayOutputStream()
        sender.media.encryptFile(contactId, ByteArrayInputStream(imageBytes(10)), sealed)

        val bytes = sealed.toByteArray().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 1).toByte() }
        val outcome = receiver.media.decryptFile(ByteArrayInputStream(bytes), ByteArrayOutputStream())
        assertTrue(outcome is MediaDecryptOutcome.Failed)
        assertEquals(CryptoError.AuthenticationFailed, (outcome as MediaDecryptOutcome.Failed).error)
    }

    @Test
    fun `same unlocked session serves text and files`() = runTest {
        val (sender, receiver) = pairedDevices()
        val contactId = sender.theSenderContact()

        // Text envelope through MessageCipher…
        val text = sender.cipher.encryptDraft(contactId, "the photo follows")
        assertTrue(text is EncryptOutcome.Encrypted)

        // …and a file through MediaCipher, with ONE unlock on each side.
        val sealed = ByteArrayOutputStream()
        assertTrue(sender.media.encryptFile(contactId, ByteArrayInputStream(imageBytes(5)), sealed) is MediaEncryptOutcome.Encrypted)
        assertTrue(
            receiver.media.decryptFile(ByteArrayInputStream(sealed.toByteArray()), ByteArrayOutputStream())
                is MediaDecryptOutcome.Decrypted,
        )
        assertTrue((receiver.cipher.decryptEnvelope((text as EncryptOutcome.Encrypted).envelope)) is DecryptOutcome.Decrypted)
    }
}

/** The sender device's only contact id (test sugar). */
private suspend fun TestDevice.theSenderContact(): ContactId =
    contacts.observeContacts().first()[0].id
