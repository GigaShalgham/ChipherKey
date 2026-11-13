package com.turnypt.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import com.turnypt.core.common.DispatcherProvider
import com.turnypt.core.crypto.Argon2KeyDerivator
import com.turnypt.core.crypto.Argon2Params
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

/** In-memory ContactDao. */
class FakeContactDao : ContactDao {
    val state = MutableStateFlow<List<ContactEntity>>(emptyList())

    override suspend fun upsert(contact: ContactEntity) {
        state.value = state.value.filterNot { it.id == contact.id } + contact
    }

    override fun observeAll(): Flow<List<ContactEntity>> =
        state.map { list -> list.sortedBy { it.displayName } }

    override suspend fun byId(id: String): ContactEntity? = state.value.firstOrNull { it.id == id }

    override suspend fun count(): Int = state.value.size

    override suspend fun delete(id: String) {
        state.value = state.value.filterNot { it.id == id }
    }
}

/** In-memory SessionKeyDao (no FK cascade — that behaviour belongs to Room). */
class FakeSessionKeyDao : SessionKeyDao {
    val state = MutableStateFlow<List<SessionKeyEntity>>(emptyList())

    override suspend fun upsert(session: SessionKeyEntity) {
        state.value = state.value.filterNot { it.sessionId == session.sessionId } + session
    }

    // Mirrors the Room query's deterministic tiebreak (deep-scan finding
    // 16): same-millisecond eras order by sessionId DESC.
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

/** Settable wall clock for deterministic session-expiry tests. */
class MutableTimeSource(var now: Long = 1_000_000L) : TimeSource {
    override fun nowMillis(): Long = now
}

/**
 * v0.5.2: in-memory ClipboardHistoryDao — mirrors the Room queries the
 * repository relies on (delete-then-insert move-to-top, age pruning, cap).
 */
class FakeClipboardHistoryDao : ClipboardHistoryDao {
    private var nextId = 1L
    val state = MutableStateFlow<List<ClipboardHistoryEntity>>(emptyList())

    private fun newestFirst() = state.value.sortedWith(
        compareByDescending<ClipboardHistoryEntity> { it.capturedAt }.thenByDescending { it.id },
    )

    override suspend fun upsert(entry: ClipboardHistoryEntity): Long {
        val id = if (entry.id == 0L) nextId++ else entry.id
        state.value = state.value.filterNot { it.id == id } + entry.copy(id = id)
        return id
    }

    override fun observeAll(): Flow<List<ClipboardHistoryEntity>> =
        state.map { newestFirst() }

    override suspend fun all(): List<ClipboardHistoryEntity> = newestFirst()

    override suspend fun deleteById(id: Long) {
        state.value = state.value.filterNot { it.id == id }
    }

    override suspend fun deleteByText(text: String) {
        state.value = state.value.filterNot { it.text == text }
    }

    override suspend fun clearAll() {
        state.value = emptyList()
    }

    override suspend fun pruneOlderThan(cutoff: Long) {
        state.value = state.value.filterNot { it.capturedAt < cutoff }
    }

    override suspend fun keepNewest(keep: Int) {
        state.value = newestFirst().take(keep)
    }
}

/**
 * A fully wired "device": its own DAOs, its own key protector (emulating the
 * device-bound Android Keystore) and its own in-memory session manager.
 * Two devices in one test emulate the sender's and the receiver's phones.
 *
 * The protector and the derivator are injectable so tests can also emulate
 * a FAILING Keystore (deep-scan finding 2: infrastructure errors must not
 * be reported as "wrong passphrase") and a SMALL ANDROID HEAP (v0.5.1:
 * the Argon2id pre-flight must reject oversized profiles gracefully).
 */
class TestDevice(
    val protector: com.turnypt.core.keys.KeyProtector = FixedKeyProtector(),
    val derivator: Argon2KeyDerivator = Argon2KeyDerivator(),
) {
    val contactDao = FakeContactDao()
    val sessionKeyDao = FakeSessionKeyDao()
    val time = MutableTimeSource()
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

    /** Unconfined derive context: Argon2id runs inline with fast test params. */
    val sessions = KeySessionManager(
        derivator = derivator,
        timeSource = time,
        deriveContext = Dispatchers.Unconfined,
    )

    val cipher = MessageCipher(
        contacts = contacts,
        sessionKeys = sessionKeys,
        sessions = sessions,
        protector = protector,
        provider = PassphraseCryptoProvider(),
        sessionTimeout = { 300_000L },
        dispatchers = dispatchers,
    )

    /** v0.6.0: the file/image facade over the SAME eras and sessions. */
    var mediaAlgorithm = com.turnypt.core.crypto.CipherAlgorithm.AES_256_GCM

    val media = MediaCipher(
        contacts = contacts,
        sessionKeys = sessionKeys,
        sessions = sessions,
        chunked = CkfChunkedCipher(),
        random = ProtocolRandom(RandomSource.SECURE),
        algorithmSource = CipherAlgorithmSource { mediaAlgorithm },
        dispatchers = dispatchers,
    )

    suspend fun newContact(name: String, params: Argon2Params = FAST_PARAMS): Pair<Contact, ContactSession> {
        val contact = contacts.createContact(name)
        val era = sessionKeys.createEra(contact.id, params)
        return contact to era
    }

    companion object {
        /** 8 KiB / 1 pass: milliseconds, valid on the wire, RFC-compliant shape. */
        val FAST_PARAMS = Argon2Params(memoryExponent = 3, iterations = 1, parallelism = 1)
    }
}
