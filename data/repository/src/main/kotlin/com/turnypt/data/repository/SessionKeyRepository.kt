package com.turnypt.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import com.turnypt.core.common.ContactId
import com.turnypt.core.common.DispatcherProvider
import com.turnypt.core.common.SessionId
import com.turnypt.core.crypto.Argon2Params
import com.turnypt.core.crypto.ProtocolRandom
import com.turnypt.core.messages.SessionInvite
import com.turnypt.core.sessions.TimeSource
import com.turnypt.data.database.SessionKeyDao
import com.turnypt.data.database.SessionKeyEntity
import java.util.UUID

/** Outcome of importing a CKS1 invite. */
sealed interface EraImport {
    data class Imported(val era: ContactSession) : EraImport

    /** The same keyId is already known locally for the same contact. */
    data class AlreadyPresent(val era: ContactSession) : EraImport

    /** The same keyId exists under a different contact — refusing to alias. */
    data object KeyBelongsToOtherContact : EraImport
}

/**
 * Persistence and lookup of session eras (public key material + optional
 * Keystore-wrapped verifier).
 */
class SessionKeyRepository(
    private val sessionKeyDao: SessionKeyDao,
    private val random: ProtocolRandom,
    private val timeSource: TimeSource,
    private val dispatchers: DispatcherProvider,
) {

    /**
     * v0.4.2 (deep-scan N2): monotonic counter bumped whenever an era is
     * created, imported or deleted. A rotation changes which session key is
     * "current" without touching the in-memory session manager — open UIs
     * (e.g. a companion sheet in split screen) need this signal to drop
     * their stale "unlocked" snapshot and stop offering a fingerprint wrap
     * bound to the OLD era.
     */
    private val _eraChanges = MutableStateFlow(0L)
    val eraChanges: StateFlow<Long> = _eraChanges.asStateFlow()

    /** Creates a brand-new era (fresh keyId + salt) for the contact. */
    suspend fun createEra(contactId: ContactId, params: Argon2Params): ContactSession =
        withContext(dispatchers.io) {
            val now = timeSource.nowMillis()
            val entity = SessionKeyEntity(
                sessionId = UUID.randomUUID().toString(),
                contactId = contactId.value,
                keyId = random.keyId(),
                salt = random.salt(),
                memoryExponent = params.memoryExponent,
                iterations = params.iterations,
                parallelism = params.parallelism,
                wrappedKey = null,
                // v0.5.2: locally created — THIS side picks the shared
                // passphrase (see ContactSession.awaitingFirstPassphrase).
                imported = false,
                createdAt = now,
                lastUsedAt = null,
            )
            sessionKeyDao.upsert(entity)
            _eraChanges.value += 1L
            entity.toDomain()
        }

    /** Imports an era from a peer's CKS1 invite. */
    suspend fun importEra(contactId: ContactId, invite: SessionInvite): EraImport =
        withContext(dispatchers.io) {
            val existing = sessionKeyDao.byKeyId(invite.keyId)
            if (existing != null) {
                return@withContext if (existing.contactId == contactId.value) {
                    EraImport.AlreadyPresent(existing.toDomain())
                } else {
                    EraImport.KeyBelongsToOtherContact
                }
            }
            val now = timeSource.nowMillis()
            val entity = SessionKeyEntity(
                sessionId = UUID.randomUUID().toString(),
                contactId = contactId.value,
                keyId = invite.keyId.copyOf(),
                salt = invite.salt.copyOf(),
                memoryExponent = invite.params.memoryExponent,
                iterations = invite.params.iterations,
                parallelism = invite.params.parallelism,
                wrappedKey = null,
                // v0.5.2: imported from the peer's invite — the PEER already
                // picked the shared passphrase; never suggest a new one here.
                imported = true,
                createdAt = now,
                lastUsedAt = null,
            )
            try {
                sessionKeyDao.upsert(entity)
            } catch (e: Exception) {
                // Lost a check-then-act race on the unique keyId index
                // (deep-scan finding 16): a concurrent import of the same
                // invite inserted the keyId between our read and write.
                // Re-read and classify instead of crashing; rethrow when the
                // insert failed for some other reason.
                val winner = sessionKeyDao.byKeyId(invite.keyId) ?: throw e
                return@withContext if (winner.contactId == contactId.value) {
                    EraImport.AlreadyPresent(winner.toDomain())
                } else {
                    EraImport.KeyBelongsToOtherContact
                }
            }
            _eraChanges.value += 1L
            EraImport.Imported(entity.toDomain())
        }

    suspend fun latestEra(contactId: ContactId): ContactSession? = withContext(dispatchers.io) {
        sessionKeyDao.latestByContact(contactId.value)?.toDomain()
    }

    suspend fun eraByKeyId(keyId: ByteArray): ContactSession? = withContext(dispatchers.io) {
        sessionKeyDao.byKeyId(keyId)?.toDomain()
    }

    /**
     * v0.4.0 (old-key-era decryption): an era looked up by session id — the
     * inverse of the eraId the UI carried through a NeedsEraUnlock outcome.
     */
    suspend fun eraBySessionId(sessionId: SessionId): ContactSession? = withContext(dispatchers.io) {
        sessionKeyDao.bySessionId(sessionId.value)?.toDomain()
    }

    /**
     * v0.5.2: whether the passphrase field for this era should offer the
     * "suggest a strong passphrase" action — true only when THIS side
     * created the era (no friend invite entered) and never unlocked it here
     * (the shared passphrase is being chosen right now). [sessionId] null =
     * the contact's latest era.
     */
    suspend fun awaitingFirstPassphrase(contactId: ContactId, sessionId: SessionId? = null): Boolean =
        withContext(dispatchers.io) {
            val entity = if (sessionId != null) {
                sessionKeyDao.bySessionId(sessionId.value)
            } else {
                sessionKeyDao.latestByContact(contactId.value)
            }
            entity != null && !entity.imported && entity.wrappedKey == null
        }

    suspend fun erasForContact(contactId: ContactId): List<ContactSession> =
        withContext(dispatchers.io) {
            sessionKeyDao.observeByContact(contactId.value).first().map { it.toDomain() }
        }

    /** Snapshot flow of all eras of a contact (newest first). */
    fun observeEras(contactId: ContactId): Flow<List<ContactSession>> =
        sessionKeyDao.observeByContact(contactId.value)
            .map { list -> list.map { it.toDomain() } }

    /** Raw Keystore-wrapped verifier blob, if this device ever unlocked the era. */
    suspend fun wrappedKeyOf(sessionId: SessionId): ByteArray? = withContext(dispatchers.io) {
        sessionKeyDao.bySessionId(sessionId.value)?.wrappedKey
    }

    suspend fun storeWrappedKey(sessionId: SessionId, wrapped: ByteArray) =
        withContext(dispatchers.io) {
            sessionKeyDao.updateWrappedKey(sessionId.value, wrapped, timeSource.nowMillis())
        }

    suspend fun markUsed(sessionId: SessionId) = withContext(dispatchers.io) {
        sessionKeyDao.touch(sessionId.value, timeSource.nowMillis())
    }

    suspend fun deleteAllForContact(contactId: ContactId) = withContext(dispatchers.io) {
        sessionKeyDao.deleteByContact(contactId.value)
        _eraChanges.value += 1L
    }

    private fun SessionKeyEntity.toDomain() = ContactSession(
        sessionId = SessionId(sessionId),
        contactId = ContactId(contactId),
        keyId = keyId,
        salt = salt,
        params = Argon2Params(
            memoryExponent = memoryExponent,
            iterations = iterations,
            parallelism = parallelism,
        ),
        createdAt = createdAt,
        hasWrappedKey = wrappedKey != null,
        imported = imported,
        lastUsedAt = lastUsedAt,
    )
}
