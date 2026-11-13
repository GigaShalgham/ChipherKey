package com.turnypt.app.companion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import com.turnypt.core.common.DispatcherProvider
import com.turnypt.core.crypto.Argon2KeyDerivator
import com.turnypt.core.crypto.Argon2Params
import com.turnypt.core.crypto.PassphraseCryptoProvider
import com.turnypt.core.crypto.ProtocolRandom
import com.turnypt.core.crypto.RandomSource
import com.turnypt.core.keys.FixedKeyProtector
import com.turnypt.core.sessions.KeySessionManager
import com.turnypt.core.sessions.TimeSource
import com.turnypt.data.database.ContactDao
import com.turnypt.data.database.ContactEntity
import com.turnypt.data.database.SessionKeyDao
import com.turnypt.data.database.SessionKeyEntity
import com.turnypt.data.repository.Contact
import com.turnypt.data.repository.ContactRepository
import com.turnypt.data.repository.ContactSession
import com.turnypt.data.repository.DecryptOutcome
import com.turnypt.data.repository.EncryptOutcome
import com.turnypt.data.repository.MessageCipher
import com.turnypt.data.repository.SessionKeyRepository
import com.turnypt.data.repository.UnlockOutcome
import com.turnypt.app.companion.ProcessTextContract.SelectedText
import com.turnypt.security.clipboard.ClipboardAccess
import com.turnypt.security.clipboard.ClipboardRead
import com.turnypt.security.clipboard.ClipboardParser
import com.turnypt.security.clipboard.ClipSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 004: end-to-end round trips through BOTH companion workflows —
 * the selection toolbar path (ACTION_PROCESS_TEXT extras) and the
 * clipboard path (ClipboardAccess chokepoint) — over the real crypto
 * stack (Argon2id + AES-256-GCM) with two independent "devices".
 *
 * This is the seam-level twin of the on-device emulator procedure: text
 * that the OS hands to Turnypt must survive both doors byte-exact.
 */
class CompanionWorkflowRoundTripTest {

    private val passphrase = "correct horse battery staple"

    // ------------------------------------------------------ harness

    /** Minimal in-memory devices (same faking style as data:repository tests). */
    private class FakeContactDao : ContactDao {
        val state = kotlinx.coroutines.flow.MutableStateFlow<List<ContactEntity>>(emptyList())
        override suspend fun upsert(contact: ContactEntity) {
            state.value = state.value.filterNot { it.id == contact.id } + contact
        }
        override fun observeAll() = state.value.let { list ->
            kotlinx.coroutines.flow.flowOf(list.sortedBy { it.displayName })
        }
        override suspend fun byId(id: String): ContactEntity? = state.value.firstOrNull { it.id == id }
        override suspend fun count(): Int = state.value.size
        override suspend fun delete(id: String) {
            state.value = state.value.filterNot { it.id == id }
        }
    }

    private class FakeSessionKeyDao : SessionKeyDao {
        val state = kotlinx.coroutines.flow.MutableStateFlow<List<SessionKeyEntity>>(emptyList())
        override suspend fun upsert(session: SessionKeyEntity) {
            state.value = state.value.filterNot { it.sessionId == session.sessionId } + session
        }
        override fun observeByContact(contactId: String) = state.value.let { list ->
            kotlinx.coroutines.flow.flowOf(
                list.filter { it.contactId == contactId }
                    .sortedWith(
                        compareByDescending<SessionKeyEntity> { it.createdAt }.thenByDescending { it.sessionId },
                    ),
            )
        }
        override suspend fun latestByContact(contactId: String): SessionKeyEntity? =
            state.value
                .filter { it.contactId == contactId }
                .sortedWith(
                    compareByDescending<SessionKeyEntity> { it.createdAt }.thenByDescending { it.sessionId },
                )
                .firstOrNull()
        override suspend fun byKeyId(keyId: ByteArray): SessionKeyEntity? =
            state.value.firstOrNull { it.keyId.contentEquals(keyId) }
        override suspend fun bySessionId(sessionId: String): SessionKeyEntity? =
            state.value.firstOrNull { it.sessionId == sessionId }
        override suspend fun updateWrappedKey(sessionId: String, wrapped: ByteArray?, now: Long) {
            state.value = state.value.map {
                if (it.sessionId == sessionId) it.copy(wrappedKey = wrapped, lastUsedAt = now) else it
            }
        }
        override suspend fun touch(sessionId: String, now: Long) {
            state.value = state.value.map {
                if (it.sessionId == sessionId) it.copy(lastUsedAt = now) else it
            }
        }
        override suspend fun deleteByContact(contactId: String) {
            state.value = state.value.filterNot { it.contactId == contactId }
        }
        override suspend fun countByContact(contactId: String): Int =
            state.value.count { it.contactId == contactId }
    }

    /** Scriptable ClipboardAccess (the chokepoint under test). */
    private class FakeClipboard(var snapshot: ClipSnapshot? = null) : ClipboardAccess {
        var reads = 0
        override fun read(): ClipboardRead {
            reads++
            return ClipboardParser.parse(snapshot)
        }
        override fun currentText(): String? = (read() as? ClipboardRead.Text)?.text
        override fun setText(text: String): Boolean {
            snapshot = ClipSnapshot(mimeTypes = listOf("text/plain"), items = listOf(text))
            return true
        }
    }

    private class TestDevice {
        val contactDao = FakeContactDao()
        val sessionKeyDao = FakeSessionKeyDao()
        val time = TimeSource { 1_000_000L }
        val dispatchers = DispatcherProvider(
            io = Dispatchers.Unconfined,
            default = Dispatchers.Unconfined,
            main = Dispatchers.Unconfined,
        )
        val contacts = ContactRepository(contactDao, time, dispatchers)
        val sessionKeys = SessionKeyRepository(
            sessionKeyDao,
            ProtocolRandom(RandomSource.SECURE),
            time,
            dispatchers,
        )
        val sessions = KeySessionManager(
            derivator = Argon2KeyDerivator(),
            timeSource = time,
            deriveContext = Dispatchers.Unconfined,
        )
        val protector = FixedKeyProtector()
        val cipher = MessageCipher(
            contacts = contacts,
            sessionKeys = sessionKeys,
            sessions = sessions,
            protector = protector,
            provider = PassphraseCryptoProvider(),
            sessionTimeout = { 300_000L },
            dispatchers = dispatchers,
        )

        suspend fun newContact(name: String): Pair<Contact, ContactSession> {
            val contact = contacts.createContact(name)
            val era = sessionKeys.createEra(contact.id, FAST_PARAMS)
            return contact to era
        }

        companion object {
            val FAST_PARAMS = Argon2Params(memoryExponent = 3, iterations = 1, parallelism = 1)
        }
    }

    // ------------------------------------------------------ scenarios

    /** Selection-toolbar workflow in, clipboard workflow out. */
    @Test
    fun `selection encrypt to clipboard decrypt round trips`() = runTest {
        val (sender, receiver, contactId) = pairedDevices()
        val draft = "meet me at the usual place"

        // Sending side: the OS delivered the selection via PROCESS_TEXT.
        val selection = ProcessTextContract.parsePlaintext(draft)
        assertTrue(selection is SelectedText.Valid)
        val envelope = encrypt(sender, contactId, (selection as SelectedText.Valid).text)

        // Receiving side: the user copied the message and pressed
        // "Decrypt clipboard" — the clip goes through the chokepoint.
        val clipboard = FakeClipboard(
            ClipSnapshot(mimeTypes = listOf("text/plain"), items = listOf(envelope)),
        )
        val read = clipboard.read()
        assertTrue(read is ClipboardRead.Text)
        val parsed = ProcessTextContract.parseEnvelope((read as ClipboardRead.Text).text)
        assertTrue(parsed is SelectedText.Valid)
        val decrypted = receiver.cipher.decryptEnvelope((parsed as SelectedText.Valid).text)

        assertTrue(decrypted is DecryptOutcome.Decrypted)
        assertEquals(draft, (decrypted as DecryptOutcome.Decrypted).plaintext)
    }

    /** Clipboard workflow in (encrypt), selection workflow out (decrypt). */
    @Test
    fun `clipboard encrypt to selection decrypt round trips`() = runTest {
        val (sender, receiver, contactId) = pairedDevices()
        val draft = "clipboard door both ways"

        // Sending side: draft came from the clipboard.
        val clipboard = FakeClipboard(
            ClipSnapshot(mimeTypes = listOf("text/plain"), items = listOf(draft)),
        )
        val read = clipboard.read() as ClipboardRead.Text
        val parsed = ProcessTextContract.parsePlaintext(read.text) as SelectedText.Valid
        val envelope = encrypt(sender, contactId, parsed.text)

        // Receiving side: the user selected the CK1 message in the app.
        val selection = ProcessTextContract.parseEnvelope(envelope) as SelectedText.Valid
        val decrypted = receiver.cipher.decryptEnvelope(selection.text)

        assertTrue(decrypted is DecryptOutcome.Decrypted)
        assertEquals(draft, (decrypted as DecryptOutcome.Decrypted).plaintext)
    }

    /** The full user matrix: Persian, emoji, multiline, mixed. */
    @Test
    fun `persian emoji and multiline drafts survive both doors`() = runTest {
        val (sender, receiver, contactId) = pairedDevices()
        val drafts = listOf(
            "سلام دنیا، این یک پیام آزمایشی است",
            "😀🎉🔐 emoji only",
            "line one\nline two\r\nline three",
            "سلام 🔐\nHow are you?\r\nخوبم، ممنون — OK 45,000",
            " Turnypt with surrounding whitespace  ",
        )

        for (draft in drafts) {
            // Mirror the real flow: the OS text is sanitized by the
            // contract BEFORE encryption (trim), and the envelope reaches
            // the receiver through the clipboard chokepoint.
            val prepared =
                (ProcessTextContract.parsePlaintext(draft) as SelectedText.Valid).text
            val envelope = encrypt(sender, contactId, prepared)
            val clipboard = FakeClipboard(
                ClipSnapshot(mimeTypes = listOf("text/plain"), items = listOf(envelope)),
            )
            val read = clipboard.read() as ClipboardRead.Text
            val decrypted = receiver.cipher.decryptEnvelope(read.text)
            assertTrue("round trip failed for: $draft", decrypted is DecryptOutcome.Decrypted)
            // Byte-exact after the contract's documented trim.
            assertEquals(
                draft.trim(),
                (decrypted as DecryptOutcome.Decrypted).plaintext,
            )
        }
    }

    /** Non-text clipboard content is classified, never crashed on. */
    @Test
    fun `image clipboard is reported as non text`() {
        val clipboard = FakeClipboard(
            ClipSnapshot(mimeTypes = listOf("image/png"), items = listOf(null)),
        )
        assertEquals(ClipboardRead.NonText("image/png"), clipboard.read())
    }

    /** Blocked clipboard access (OEM denial) is distinguishable from empty. */
    @Test
    fun `blocked clipboard is distinguishable from empty`() {
        val empty = FakeClipboard(ClipSnapshot(mimeTypes = listOf("text/plain"), items = listOf(null)))
        assertEquals(ClipboardRead.Empty, empty.read())

        val blocked = object : ClipboardAccess {
            override fun read(): ClipboardRead =
                ClipboardRead.Blocked("Denying clipboard access to com.turnypt.app")
            override fun currentText(): String? = null
            override fun setText(text: String): Boolean = false
        }
        assertTrue(blocked.read() is ClipboardRead.Blocked)
    }

    /** PROCESS_TEXT with a null/blank extra is rejected before crypto. */
    @Test
    fun `missing process text extra is empty not crash`() {
        assertEquals(SelectedText.Empty, ProcessTextContract.parsePlaintext(null))
        assertEquals(SelectedText.Empty, ProcessTextContract.parseEnvelope(null))
        assertEquals(SelectedText.Empty, ProcessTextContract.parsePlaintext("   \n\t"))
    }

    /** Malformed PROCESS_TEXT input is rejected before crypto. */
    @Test
    fun `oversized process text input is rejected`() {
        val huge = "a".repeat(17 * 1024)
        assertTrue(ProcessTextContract.parsePlaintext(huge) is SelectedText.Oversized)
        assertTrue(
            ProcessTextContract.parseEnvelope("CK1:" + "a".repeat(300 * 1024)) is SelectedText.Oversized,
        )
    }

    /** A non-envelope text handed to decrypt fails gracefully downstream. */
    @Test
    fun `non envelope text decrypts to a notice outcome`() = runTest {
        val (_, receiver, _) = pairedDevices()
        val parsed = ProcessTextContract.parseEnvelope("just some words") as SelectedText.Valid
        assertTrue(receiver.cipher.decryptEnvelope(parsed.text) is DecryptOutcome.NotAnEnvelope)
    }

    // ------------------------------------------------------ helpers

    private suspend fun pairedDevices(): Triple<TestDevice, TestDevice, com.turnypt.core.common.ContactId> {
        val sender = TestDevice()
        val receiver = TestDevice()
        val (contact, era) = sender.newContact("Bob")
        val invite = com.turnypt.core.messages.InviteCodec.decode(era.inviteCode)
        val alice = receiver.contacts.createContact("Alice")
        receiver.sessionKeys.importEra(alice.id, invite)
        assertTrue(
            sender.cipher.unlockContact(contact.id, passphrase.toCharArray()) is UnlockOutcome.Unlocked,
        )
        assertTrue(
            receiver.cipher.unlockContact(alice.id, passphrase.toCharArray()) is UnlockOutcome.Unlocked,
        )
        return Triple(sender, receiver, contact.id)
    }

    private suspend fun encrypt(device: TestDevice, contactId: com.turnypt.core.common.ContactId, text: String): String {
        val outcome = device.cipher.encryptDraft(contactId, text)
        assertTrue(outcome is EncryptOutcome.Encrypted)
        val envelope = (outcome as EncryptOutcome.Encrypted).envelope
        // v1.1.0: written envelopes carry the Turnypt prefix.
        assertTrue(envelope.startsWith("TNP1:"))
        return envelope
    }
}
