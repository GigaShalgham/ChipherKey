package com.turnypt.app

import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.Cipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.turnypt.app.clipboard.ClipboardHistoryRecorder
import com.turnypt.app.companion.ContentResolverStreams
import com.turnypt.app.security.BiometricKeyStore
import com.turnypt.core.common.DispatcherProvider
import com.turnypt.core.crypto.Argon2KeyDerivator
import com.turnypt.core.crypto.Argon2Params
import com.turnypt.core.crypto.CipherAlgorithm
import com.turnypt.core.crypto.CkfChunkedCipher
import com.turnypt.core.crypto.PassphraseCryptoProvider
import com.turnypt.core.crypto.ProtocolRandom
import com.turnypt.core.crypto.RandomSource
import com.turnypt.core.keys.FixedKeyProtector
import com.turnypt.core.sessions.KeySessionManager
import com.turnypt.core.sessions.TimeSource
import com.turnypt.data.database.ClipboardHistoryDao
import com.turnypt.data.database.ClipboardHistoryEntity
import com.turnypt.data.database.ContactDao
import com.turnypt.data.database.ContactEntity
import com.turnypt.data.database.SessionKeyDao
import com.turnypt.data.database.SessionKeyEntity
import com.turnypt.data.preferences.SettingsRepository
import com.turnypt.data.repository.CipherAlgorithmSource
import com.turnypt.data.repository.ClipboardHistoryRepository
import com.turnypt.data.repository.Contact
import com.turnypt.data.repository.ContactRepository
import com.turnypt.data.repository.ContactSession
import com.turnypt.data.repository.MediaCipher
import com.turnypt.data.repository.MessageCipher
import com.turnypt.data.repository.SessionKeyRepository
import com.turnypt.security.clipboard.ClipboardAccess
import com.turnypt.security.clipboard.ClipboardParser
import com.turnypt.security.clipboard.ClipboardRead

/**
 * v1.2.0 regression tests for the user-reported unlock bugs, driven through
 * the REAL ToolsViewModel (real crypto stack, fake edges — the same faking
 * style as CompanionWorkflowRoundTripTest):
 *
 *  1. the standalone password unlock left `busy` stuck true — the Encrypt
 *     button stayed disabled forever after typing the passphrase;
 *  2. the fingerprint offer was never computed on the initial screen load —
 *     only after the user hand-picked a contact again;
 *  3. a finished draft was never consumed — a later unlock silently
 *     re-encrypted an old message;
 *  4. switching contacts kept a queued draft aimed at the OLD contact (it
 *     would encrypt for the NEW one).
 *
 * The ContentResolverStreams seam (made an interface for exactly this)
 * keeps the ViewModel free of Android framework classes, so these run as
 * plain JVM tests. The settings DataStore runs on the test scheduler, and
 * the ViewModel is built only AFTER the scenario seeded it — every state
 * transition is deterministic under advanceUntilIdle().
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ToolsViewModelUnlockTest {

    private val passphrase = "correct horse battery staple"

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------ scenarios

    @Test
    fun `standalone password unlock resets busy and unlocks the contact`() = runTest {
        val env = Env(StandardTestDispatcher(testScheduler))
        val (contact, _) = env.newContact("Alice")
        val viewModel = env.buildViewModel()
        advanceUntilIdle()

        // Initial load restored the (locked) contact: the unlock card must
        // be armed immediately — this is the "open with password" shortcut
        // state the Encrypt tab shows on FIRST appearance.
        assertEquals(contact.id, viewModel.state.value.selectedContactId)
        assertTrue(viewModel.state.value.unlockNeeded)
        assertEquals(contact.id, viewModel.state.value.pendingContactId)

        // Submit the passphrase with nothing queued — the shortcut itself.
        viewModel.unlockAndContinue(passphrase.toCharArray())
        advanceUntilIdle()

        // THE regression: busy must return to false (it used to stay true
        // forever — every action on the tab was disabled after this).
        assertFalse(viewModel.state.value.busy)
        assertTrue(contact.id in viewModel.state.value.unlocked)
        assertFalse(viewModel.state.value.unlockNeeded)
        env.cleanup()
    }

    @Test
    fun `fingerprint offer is armed on the initial load`() = runTest {
        val env = Env(StandardTestDispatcher(testScheduler))
        env.newContact("Bob")
        env.seedBiometricUnlockEnabled()
        val viewModel = env.buildViewModel(
            biometrics = FakeBiometrics(capable = true, wrapped = true),
        )
        advanceUntilIdle() // the ViewModel's own init refresh()

        // The unlock card is armed AND the fingerprint button is offered
        // from the very first composition — no contact click, no second
        // loading cycle (the exact user report).
        assertTrue(viewModel.state.value.unlockNeeded)
        assertTrue(viewModel.state.value.biometricOffered)
        env.cleanup()
    }

    @Test
    fun `a finished draft is never re encrypted by a later unlock`() = runTest {
        val env = Env(StandardTestDispatcher(testScheduler))
        val (contact, _) = env.newContact("Alice")
        val viewModel = env.buildViewModel()
        advanceUntilIdle()

        // Type a message, hit Encrypt with the contact locked: the unlock
        // card appears, then the passphrase continues the encrypt.
        viewModel.encrypt("first message")
        advanceUntilIdle()
        assertTrue(viewModel.state.value.unlockNeeded)

        viewModel.unlockAndContinue(passphrase.toCharArray())
        advanceUntilIdle()
        val envelope = viewModel.state.value.resultText
        assertNotNull(envelope)
        assertTrue(viewModel.state.value.resultIsEnvelope)
        assertFalse(viewModel.state.value.busy)

        // Later: the session re-locks (timeout / manual lock), the user
        // selects the locked contact again and unlocks it — the OLD draft
        // must NOT re-encrypt itself (a fresh envelope has a fresh nonce,
        // so any re-run would change resultText).
        env.cipher.lockAll()
        advanceUntilIdle()
        viewModel.selectContact(contact.id)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.unlockNeeded)

        viewModel.unlockAndContinue(passphrase.toCharArray())
        advanceUntilIdle()

        assertEquals(envelope, viewModel.state.value.resultText)
        assertFalse(viewModel.state.value.busy)
        assertTrue(contact.id in viewModel.state.value.unlocked)
        env.cleanup()
    }

    @Test
    fun `switching contacts drops the queued draft`() = runTest {
        val env = Env(StandardTestDispatcher(testScheduler))
        val (alice, _) = env.newContact("Alice")
        val (bob, _) = env.newContact("Bob")
        val viewModel = env.buildViewModel()
        advanceUntilIdle()

        // A draft queued for Alice's locked contact…
        viewModel.selectContact(alice.id)
        advanceUntilIdle()
        viewModel.encrypt("draft meant for Alice")
        advanceUntilIdle()
        assertTrue(viewModel.state.value.unlockNeeded)
        assertEquals(alice.id, viewModel.state.value.pendingContactId)

        // …then the user picks Bob instead and unlocks HIM. The stale draft
        // must be gone: Bob gets a plain standalone open, and nothing is
        // ever encrypted for Bob with Alice's words.
        viewModel.selectContact(bob.id)
        advanceUntilIdle()
        assertEquals(bob.id, viewModel.state.value.pendingContactId)

        viewModel.unlockAndContinue(passphrase.toCharArray())
        advanceUntilIdle()

        assertEquals(null, viewModel.state.value.resultText)
        assertTrue(bob.id in viewModel.state.value.unlocked)
        assertFalse(viewModel.state.value.busy)
        assertFalse(alice.id in viewModel.state.value.unlocked)
        env.cleanup()
    }

    // ------------------------------------------------------ harness

    /** In-memory history DAO (same faking style as the round-trip test). */
    private class FakeHistoryDao : ClipboardHistoryDao {
        val entries = MutableStateFlow<List<ClipboardHistoryEntity>>(emptyList())
        override suspend fun upsert(entry: ClipboardHistoryEntity): Long {
            entries.value = entries.value + entry
            return entries.value.size.toLong()
        }
        override fun observeAll() = flowOf(entries.value)
        override suspend fun all(): List<ClipboardHistoryEntity> = entries.value
        override suspend fun deleteById(id: Long) {
            entries.value = entries.value.filterNot { it.id == id }
        }
        override suspend fun deleteByText(text: String) {
            entries.value = entries.value.filterNot { it.text == text }
        }
        override suspend fun clearAll() {
            entries.value = emptyList()
        }
        override suspend fun pruneOlderThan(cutoff: Long) {}
        override suspend fun keepNewest(keep: Int) {}
    }

    private class FakeContactDao : ContactDao {
        val state = MutableStateFlow<List<ContactEntity>>(emptyList())
        override suspend fun upsert(contact: ContactEntity) {
            state.value = state.value.filterNot { it.id == contact.id } + contact
        }
        override fun observeAll() = flowOf(state.value.sortedBy { it.displayName })
        override suspend fun byId(id: String): ContactEntity? = state.value.firstOrNull { it.id == id }
        override suspend fun count(): Int = state.value.size
        override suspend fun delete(id: String) {
            state.value = state.value.filterNot { it.id == id }
        }
    }

    private class FakeSessionKeyDao : SessionKeyDao {
        val state = MutableStateFlow<List<SessionKeyEntity>>(emptyList())
        override suspend fun upsert(session: SessionKeyEntity) {
            state.value = state.value.filterNot { it.sessionId == session.sessionId } + session
        }
        override fun observeByContact(contactId: String) = flowOf(
            state.value
                .filter { it.contactId == contactId }
                .sortedWith(
                    compareByDescending<SessionKeyEntity> { it.createdAt }.thenByDescending { it.sessionId },
                ),
        )
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

    /** Scriptable clipboard (never written in these scenarios). */
    private class FakeClipboard : ClipboardAccess {
        override fun read(): ClipboardRead = ClipboardParser.parse(null)
        override fun currentText(): String? = null
        override fun setText(text: String): Boolean = false
    }

    /** Device-capable / wrap-present switch, exactly what the offer needs. */
    private class FakeBiometrics(
        private val capable: Boolean,
        private val wrapped: Boolean,
    ) : BiometricKeyStore {
        override fun isDeviceCapable(): Boolean = capable
        override fun strongAuthResult(): Int = 0
        override fun hasWrapped(contactKey: String): Boolean = wrapped
        override fun readWrapped(contactKey: String): ByteArray? = null
        override fun persistWrapped(contactKey: String, blob: ByteArray) {}
        override fun clear(contactKey: String) {}
        override fun clearAll() {}
        override fun newWrapCipher(): Cipher? = null
        override fun newUnwrapCipher(blob: ByteArray): Cipher? = null
        override fun finishWrap(cipher: Cipher, plain: ByteArray, aad: ByteArray): ByteArray = plain
        override fun finishUnwrap(cipher: Cipher, blob: ByteArray, aad: ByteArray): ByteArray = blob
    }

    /** Image-free streams: reads fail softly, exactly like the text flows. */
    private class FakeStreams : ContentResolverStreams {
        override fun read(uri: Uri): InputStream? = null
        override fun write(uri: Uri): OutputStream? = null
        override fun queryDisplayName(uri: Uri): String? = null
        override fun newDecryptCacheFile(): File = File.createTempFile("ck_decrypt", ".bin")
    }

    /**
     * One fully wired environment over the real crypto stack. Build the
     * ViewModel only after seeding the scenario (contacts, settings) so the
     * init refresh() observes exactly the state the test intends.
     */
    private class Env(private val testDispatcher: TestDispatcher) {
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
        private val algorithmSource = CipherAlgorithmSource { CipherAlgorithm.AES_256_GCM }
        val cipher = MessageCipher(
            contacts = contacts,
            sessionKeys = sessionKeys,
            sessions = sessions,
            protector = FixedKeyProtector(),
            provider = PassphraseCryptoProvider(),
            sessionTimeout = { 300_000L },
            dispatchers = dispatchers,
            algorithmSource = algorithmSource,
        )
        private val mediaCipher = MediaCipher(
            contacts = contacts,
            sessionKeys = sessionKeys,
            sessions = sessions,
            chunked = CkfChunkedCipher(),
            random = ProtocolRandom(RandomSource.SECURE),
            algorithmSource = algorithmSource,
            dispatchers = dispatchers,
        )
        private val tmpDir = File.createTempFile("turnypt_env", "").let { dir ->
            dir.delete()
            dir.mkdirs()
            dir
        }
        private val dataStoreScope = CoroutineScope(SupervisorJob() + testDispatcher)
        val settings = SettingsRepository(
            PreferenceDataStoreFactory.create(
                scope = dataStoreScope,
                produceFile = { File(tmpDir, "settings.preferences_pb") },
            ),
        )
        private val history = ClipboardHistoryRepository(FakeHistoryDao(), time, dispatchers)

        var viewModel: ToolsViewModel? = null
            private set

        suspend fun newContact(name: String): Pair<Contact, ContactSession> {
            val contact = contacts.createContact(name)
            val era = sessionKeys.createEra(contact.id, FAST_PARAMS)
            return contact to era
        }

        suspend fun seedBiometricUnlockEnabled() {
            settings.setBiometricUnlockEnabled(true)
        }

        fun buildViewModel(
            biometrics: FakeBiometrics = FakeBiometrics(capable = false, wrapped = false),
        ): ToolsViewModel {
            val vm = ToolsViewModel(
                messageCipher = cipher,
                mediaCipher = mediaCipher,
                contactsRepository = contacts,
                sessionKeys = sessionKeys,
                settings = settings,
                clipboard = FakeClipboard(),
                clipboardHistoryRepository = history,
                clipboardRecorder = ClipboardHistoryRecorder(settings, FakeClipboard(), history),
                biometricStore = biometrics,
                algorithmSource = algorithmSource,
                streams = FakeStreams(),
            )
            viewModel = vm
            return vm
        }

        fun cleanup() {
            viewModel?.viewModelScope?.cancel()
            dataStoreScope.cancel()
        }
    }

    private companion object {
        val FAST_PARAMS = Argon2Params(memoryExponent = 3, iterations = 1, parallelism = 1)
    }
}
