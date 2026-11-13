package com.turnypt.keyboard.controller

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import com.turnypt.core.common.ContactId
import com.turnypt.core.common.DispatcherProvider
import com.turnypt.core.crypto.Argon2KeyDerivator
import com.turnypt.core.crypto.Argon2Params
import com.turnypt.core.crypto.PassphraseCryptoProvider
import com.turnypt.core.crypto.ProtocolRandom
import com.turnypt.core.crypto.RandomSource
import com.turnypt.core.keys.FixedKeyProtector
import com.turnypt.core.messages.Ck1Limits
import com.turnypt.core.sessions.KeySessionManager
import com.turnypt.core.sessions.TimeSource
import com.turnypt.data.database.ContactDao
import com.turnypt.data.database.ContactEntity
import com.turnypt.data.database.SessionKeyDao
import com.turnypt.data.database.SessionKeyEntity
import com.turnypt.data.repository.ContactRepository
import com.turnypt.data.repository.EncryptOutcome
import com.turnypt.data.repository.MessageCipher
import com.turnypt.data.repository.SessionKeyRepository
import com.turnypt.keyboard.input.DraftRead
import com.turnypt.keyboard.input.TextFieldAccess
import com.turnypt.security.clipboard.ClipboardAccess
import com.turnypt.security.clipboard.ClipboardRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Minimal in-memory ContactDao for the controller tests. */
private class FakeContactDao : ContactDao {
    val state = MutableStateFlow<List<ContactEntity>>(emptyList())
    override suspend fun upsert(contact: ContactEntity) {
        state.value = state.value.filterNot { it.id == contact.id } + contact
    }
    override fun observeAll(): Flow<List<ContactEntity>> = state
    override suspend fun byId(id: String): ContactEntity? = state.value.firstOrNull { it.id == id }
    override suspend fun count(): Int = state.value.size
    override suspend fun delete(id: String) {
        state.value = state.value.filterNot { it.id == id }
    }
}

/** Minimal in-memory SessionKeyDao for the controller tests. */
private class FakeSessionKeyDao : SessionKeyDao {
    val state = MutableStateFlow<List<SessionKeyEntity>>(emptyList())
    override suspend fun upsert(session: SessionKeyEntity) {
        state.value = state.value.filterNot { it.sessionId == session.sessionId } + session
    }
    override fun observeByContact(contactId: String): Flow<List<SessionKeyEntity>> =
        state.map { list ->
            list.filter { it.contactId == contactId }
                .sortedWith(compareByDescending<SessionKeyEntity> { it.createdAt }.thenByDescending { it.sessionId })
        }
    override suspend fun latestByContact(contactId: String): SessionKeyEntity? =
        state.value
            .filter { it.contactId == contactId }
            .sortedWith(compareByDescending<SessionKeyEntity> { it.createdAt }.thenByDescending { it.sessionId })
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

/** In-memory text field: records everything the controller inserts. */
private class FakeTextField(
    var text: String = "",
    /** Scriptable: emulates an editor whose text cannot be read at all. */
    var unreadable: Boolean = false,
) : TextFieldAccess {
    override fun commitText(text: String): Boolean {
        this.text += text
        return true
    }
    override fun deleteBackward(): Boolean {
        if (text.isEmpty()) return false
        text = text.dropLast(1)
        return true
    }
    override fun replaceAll(newText: String): Boolean {
        text = newText
        return true
    }
    override fun readDraft(): DraftRead = when {
        unreadable -> DraftRead.Unreadable
        text.length > Ck1Limits.MAX_PLAINTEXT_BYTES -> DraftRead.TooLong
        else -> DraftRead.Draft(text)
    }
    override val isSensitiveField: Boolean get() = false
}

/** Scriptable clipboard that counts reads (the security-relevant operation). */
private class FakeClipboard(var current: String? = null) : ClipboardAccess {
    var readCount = 0
    override fun read(): ClipboardRead {
        readCount++
        return current?.let { ClipboardRead.Text(it) } ?: ClipboardRead.Empty
    }
    override fun currentText(): String? {
        readCount++
        return current
    }
    override fun setText(text: String): Boolean {
        current = text
        return true
    }
}

class ImeControllerTest {

    private val passphrase = "correct horse battery staple"

    private class Harness(scope: CoroutineScope) {
        val textField = FakeTextField()
        val clipboard = FakeClipboard()
        val contactDao = FakeContactDao()
        val sessionKeyDao = FakeSessionKeyDao()
        private val time = TimeSource { 1_000_000L }
        private val dispatchers = DispatcherProvider(
            io = Dispatchers.Unconfined,
            default = Dispatchers.Unconfined,
            main = Dispatchers.Unconfined,
        )
        val contacts = ContactRepository(contactDao, time, dispatchers)
        val sessionKeys = SessionKeyRepository(sessionKeyDao, ProtocolRandom(RandomSource.SECURE), time, dispatchers)
        val cipher = MessageCipher(
            contacts = contacts,
            sessionKeys = sessionKeys,
            sessions = KeySessionManager(Argon2KeyDerivator(), time, Dispatchers.Unconfined),
            protector = FixedKeyProtector(),
            provider = PassphraseCryptoProvider(),
            sessionTimeout = { 300_000L },
            dispatchers = dispatchers,
        )
        val controller = ImeController(cipher, contacts, clipboard, textField, scope, autoClearNotices = false)
    }

    private suspend fun Harness.seedContact(): ContactId {
        val contact = contacts.createContact("Bob")
        sessionKeys.createEra(contact.id, Argon2Params(memoryExponent = 3, iterations = 1, parallelism = 1))
        return contact.id
    }

    @Test
    fun `encrypt without unlock prompts for the passphrase`() = runTest {
        val harness = Harness(CoroutineScope(StandardTestDispatcher(testScheduler)))
        harness.seedContact()
        advanceUntilIdle()
        harness.textField.text = "hello bob"

        harness.controller.requestEncryptDraft()
        advanceUntilIdle()

        val prompt = harness.controller.passphrasePrompt.value
        assertNotNull(prompt)
        assertEquals(ImeController.PromptReason.SEND, prompt!!.reason)
        assertEquals("Bob", prompt.contactName)
        // The draft was NOT replaced while locked.
        assertEquals("hello bob", harness.textField.text)
    }

    @Test
    fun `submitting the passphrase unlocks and encrypts the draft`() = runTest {
        val harness = Harness(CoroutineScope(StandardTestDispatcher(testScheduler)))
        harness.seedContact()
        advanceUntilIdle()
        harness.textField.text = "hello bob"

        harness.controller.requestEncryptDraft()
        advanceUntilIdle()
        harness.controller.submitPassphrase(passphrase.toCharArray())
        advanceUntilIdle()

        assertNull(harness.controller.passphrasePrompt.value)
        assertTrue(harness.textField.text.startsWith("CK1:"))
        assertTrue(!harness.textField.text.contains("hello bob"))
        assertTrue(harness.controller.notice.value?.contains("Encrypted") == true)
    }

    @Test
    fun `decrypt clipboard shows an authenticated preview and insert commits`() = runTest {
        val harness = Harness(CoroutineScope(StandardTestDispatcher(testScheduler)))
        val contactId = harness.seedContact()
        advanceUntilIdle()
        harness.cipher.unlockContact(contactId, passphrase.toCharArray())

        val envelope = (harness.cipher.encryptDraft(contactId, "سلام — secret reply") as EncryptOutcome.Encrypted).envelope
        harness.clipboard.current = envelope
        harness.textField.text = ""

        harness.controller.requestDecryptClipboard()
        advanceUntilIdle()

        val preview = harness.controller.preview.value
        assertNotNull(preview)
        assertEquals("Bob", preview!!.contactName)
        assertEquals("سلام — secret reply", preview.plaintext)

        harness.controller.insertPreviewedText()
        assertTrue(harness.textField.text.contains("سلام — secret reply"))
        assertNull(harness.controller.preview.value)
    }

    @Test
    fun `sensitive fields suppress clipboard access entirely`() = runTest {
        val harness = Harness(CoroutineScope(StandardTestDispatcher(testScheduler)))
        harness.seedContact()
        advanceUntilIdle()
        harness.clipboard.current = "CK1:whatever"

        harness.controller.onInputChanged(true)
        harness.controller.requestDecryptClipboard()
        advanceUntilIdle()

        assertEquals(0, harness.clipboard.readCount)
        assertNull(harness.controller.preview.value)
    }

    @Test
    fun `non envelope clipboard is reported without a preview`() = runTest {
        val harness = Harness(CoroutineScope(StandardTestDispatcher(testScheduler)))
        harness.seedContact()
        advanceUntilIdle()
        harness.clipboard.current = "plain text"

        harness.controller.requestDecryptClipboard()
        advanceUntilIdle()

        assertNull(harness.controller.preview.value)
        assertNotNull(harness.controller.notice.value)
    }

    @Test
    fun `contacts list drives the active contact selection`() = runTest {
        val harness = Harness(CoroutineScope(StandardTestDispatcher(testScheduler)))
        val first = harness.seedContact()
        advanceUntilIdle()

        assertEquals("Bob", harness.controller.uiState.value.activeContactName)
        harness.controller.selectContact(first)
        assertEquals(first, harness.controller.uiState.value.activeContactId)
    }

    @Test
    fun `locking the active contact clears the unlocked badge and the preview`() = runTest {
        val harness = Harness(CoroutineScope(StandardTestDispatcher(testScheduler)))
        val contactId = harness.seedContact()
        advanceUntilIdle()

        harness.cipher.unlockContact(contactId, passphrase.toCharArray())
        harness.controller.selectContact(contactId)
        advanceUntilIdle()

        // Produce a decrypted preview first (deep-scan finding 24).
        val envelope = (harness.cipher.encryptDraft(contactId, "secret reply") as EncryptOutcome.Encrypted).envelope
        harness.clipboard.current = envelope
        harness.controller.requestDecryptClipboard()
        advanceUntilIdle()
        assertNotNull(harness.controller.preview.value)

        harness.controller.lockActiveContact()
        advanceUntilIdle()
        assertTrue(harness.controller.uiState.value.unlockedContactIds.isEmpty())
        assertNull(harness.controller.preview.value)
    }

    @Test
    fun `unreadable draft is reported, not treated as empty`() = runTest {
        val harness = Harness(CoroutineScope(StandardTestDispatcher(testScheduler)))
        val contactId = harness.seedContact()
        advanceUntilIdle()
        harness.cipher.unlockContact(contactId, passphrase.toCharArray())
        harness.textField.text = "hello bob"
        harness.textField.unreadable = true

        harness.controller.requestEncryptDraft()
        advanceUntilIdle()

        // Deep-scan finding 22: unreadable must not collapse into "nothing
        // to encrypt".
        val notice = harness.controller.notice.value
        assertNotNull(notice)
        assertTrue(notice!!.contains("Could not read"))
        // The field was left untouched.
        assertEquals("hello bob", harness.textField.text)
    }

    @Test
    fun `oversized draft is rejected without truncation`() = runTest {
        val harness = Harness(CoroutineScope(StandardTestDispatcher(testScheduler)))
        val contactId = harness.seedContact()
        advanceUntilIdle()
        harness.cipher.unlockContact(contactId, passphrase.toCharArray())
        harness.textField.text = "x".repeat(Ck1Limits.MAX_PLAINTEXT_BYTES + 1)

        harness.controller.requestEncryptDraft()
        advanceUntilIdle()

        // Deep-scan finding 23: honest rejection, never a silent truncation
        // into a shorter encrypted message.
        val notice = harness.controller.notice.value
        assertNotNull(notice)
        assertTrue(notice!!.contains("16 KiB"))
        assertFalse(harness.textField.text.startsWith("CK1:"))
    }
}
