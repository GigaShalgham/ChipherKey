package com.turnypt.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import com.turnypt.core.common.ContactId
import com.turnypt.core.common.DispatcherProvider
import com.turnypt.core.common.SessionId
import com.turnypt.core.crypto.CryptoError
import com.turnypt.core.crypto.CryptoProvider
import com.turnypt.core.crypto.DecryptionContext
import com.turnypt.core.crypto.EncryptionContext
import com.turnypt.core.crypto.EncryptionMode
import com.turnypt.core.crypto.EncryptionResult
import com.turnypt.core.crypto.DecryptionResult
import com.turnypt.core.crypto.KdfMemoryException
import com.turnypt.core.keys.KeyProtector
import com.turnypt.core.keys.KeyProtectionException
import com.turnypt.core.messages.Ck1
import com.turnypt.core.messages.Ck1Limits
import com.turnypt.core.messages.MessageDecoder
import com.turnypt.core.messages.MessageEncoder
import com.turnypt.core.sessions.KeySessionManager
import com.turnypt.security.policies.KeyPolicy

/** Outcome of unlocking a contact with a shared passphrase. */
sealed interface UnlockOutcome {
    data object Unlocked : UnlockOutcome
    data object AlreadyUnlocked : UnlockOutcome
    data object WrongPassphrase : UnlockOutcome

    /** The contact has no key era yet (create or import one first). */
    data object NoSession : UnlockOutcome

    /** The passphrase violates [KeyPolicy]. */
    data class Rejected(val reason: String) : UnlockOutcome

    /** Unexpected internal failure (e.g. Keystore unavailable). */
    data class Failed(val reason: String) : UnlockOutcome

    /**
     * v0.5.1: the era's Argon2id profile needs [requiredBytes] of heap —
     * more than this device's process can provide. The passphrase was NOT
     * wrong and nothing failed: the profile simply does not fit (reported
     * with the 256 MiB PARANOID preset, which OOM-crashed before this
     * outcome existed). The UI answers with a localized "pick a lower
     * strength" notice instead of a crash.
     */
    data class MemoryHungry(val requiredBytes: Long) : UnlockOutcome
}

/** Outcome of encrypting a draft for a contact. */
sealed interface EncryptOutcome {
    data class Encrypted(val envelope: String) : EncryptOutcome
    data object NeedsUnlock : EncryptOutcome
    data object NoSession : EncryptOutcome
    data object EmptyDraft : EncryptOutcome
    data class Failed(val error: CryptoError) : EncryptOutcome
}

/** Outcome of decrypting a CK1 envelope from the clipboard. */
sealed interface DecryptOutcome {
    data class Decrypted(
        val plaintext: String,
        val contactId: ContactId,
        val contactName: String,
    ) : DecryptOutcome

    /** The matching contact is not unlocked; prompt for the passphrase. */
    data class NeedsUnlock(val contactId: ContactId, val contactName: String) : DecryptOutcome

    /** The text is not a CK1 envelope at all. */
    data object NotAnEnvelope : DecryptOutcome

    /** The keyId is unknown on this device. */
    data object UnknownKey : DecryptOutcome

    /**
     * The envelope belongs to a superseded key era of the contact (v0.4.0:
     * old-key-era decryption). It becomes readable after that era is
     * unlocked with [unlockEra] — using the passphrase the era was created
     * with, which may predate the current one.
     */
    data class NeedsEraUnlock(
        val contactId: ContactId,
        val eraSessionId: SessionId,
        val contactName: String,
    ) : DecryptOutcome

    /** Decoding or authentication failure (includes tampered ciphertext). */
    data class Failed(val error: CryptoError) : DecryptOutcome
}

/**
 * Supplies the configured session timeout. Abstracted so the facade is
 * unit-testable on the JVM without DataStore/Android.
 */
fun interface SessionTimeoutSource {
    suspend fun timeoutMillis(): Long
}

/**
 * Composition facade used by the keyboard and the management UI.
 *
 * Orchestrates: session material lookup (Room), key derivation and in-memory
 * session lifetime (KeySessionManager), at-rest wrapping / on-device
 * passphrase verification (KeyProtector) and the actual AEAD work
 * (CryptoProvider).
 *
 * This is the ONLY class allowed to move raw key bytes between layers; the
 * keyboard module never touches key material.
 */
class MessageCipher(
    private val contacts: ContactRepository,
    private val sessionKeys: SessionKeyRepository,
    private val sessions: KeySessionManager,
    private val protector: KeyProtector,
    private val provider: CryptoProvider,
    private val sessionTimeout: SessionTimeoutSource,
    private val dispatchers: DispatcherProvider,
    /** v0.6.0: the cipher for new TEXT envelopes (default keeps AES-256-GCM). */
    private val algorithmSource: CipherAlgorithmSource =
        CipherAlgorithmSource { com.turnypt.core.crypto.CipherAlgorithm.AES_256_GCM },
) {

    /**
     * v0.4.2 (deep-scan N1/N2): emits after every unlock, lock, expiry sweep
     * and key-era rotation (create / import / delete) — the single signal
     * open screens collect to re-read WHO is actually unlocked instead of
     * trusting a snapshot taken when their screen opened. Before this, a
     * lockAll() or a timeout in another surface left the Encrypt tab showing
     * its button with no unlock card, and a companion sheet kept offering a
     * fingerprint wrap of a rotated era.
     */
    val sessionChanges: Flow<Unit> = combine(
        sessions.sessionChanges,
        sessionKeys.eraChanges,
    ) { _, _ -> }

    /**
     * Unlocks the contact's current era with the shared passphrase.
     *
     * Verification path: if this device ever unlocked the era before, the
     * Keystore-wrapped verifier (v2: a digest, see [UnlockVerifier]; v1
     * legacy: the full key — migrated on first successful unlock) is
     * unwrapped and compared — a wrong passphrase is rejected WITHOUT being
     * activated. On the very first unlock on a device there is no verifier
     * yet (wrong passphrases are then detected at decryption time by AEAD
     * authentication).
     *
     * Error honesty (deep-scan finding 2): a [KeyProtectionException] from
     * the Keystore unwrap is an INFRASTRUCTURE failure (master key
     * invalidated, KeyStore unavailable) — the passphrase plays no part in
     * the unwrap, so it is reported as [UnlockOutcome.Failed], never as a
     * false "wrong passphrase".
     */
    suspend fun unlockContact(contactId: ContactId, passphrase: CharArray): UnlockOutcome =
        withContext(dispatchers.io) {
            KeyPolicy.passphraseProblem(passphrase)?.let { return@withContext UnlockOutcome.Rejected(it) }

            val era = sessionKeys.latestEra(contactId)
                ?: return@withContext UnlockOutcome.NoSession

            if (sessions.activeSession(contactId)?.sessionId == era.sessionId) {
                return@withContext UnlockOutcome.AlreadyUnlocked
            }

            val timeout = sessionTimeout.timeoutMillis()
            var derived: ByteArray? = null
            try {
                val bytes = sessions.deriveKey(passphrase, era.salt, era.params)
                derived = bytes
                verifyAndPersistVerifier(era, bytes)?.let { verdict ->
                    derived?.fill(0)
                    if (verdict is UnlockOutcome.Failed) {
                        // Keystore infrastructure failed mid-verify: the
                        // original path locked the contact here — kept.
                        sessions.lock(contactId)
                    }
                    return@withContext verdict
                }
                sessions.activate(
                    contactId = contactId,
                    sessionId = era.sessionId,
                    keyId = era.keyId,
                    salt = era.salt,
                    params = era.params,
                    keyBytes = bytes,
                    timeoutMillis = timeout,
                )
                // activate() copies the buffer (deep-scan finding 1): the
                // session owns its key material from here on, so this local
                // reference can be wiped on every path.
                derived?.fill(0)
                UnlockOutcome.Unlocked
            } catch (e: KdfMemoryException) {
                // v0.5.1: the profile does not fit the heap — nothing was
                // derived, so there is nothing to wipe. Not a passphrase
                // problem and not an infrastructure failure.
                UnlockOutcome.MemoryHungry(e.requiredBytes)
            } catch (e: KeyProtectionException) {
                derived?.fill(0)
                sessions.lock(contactId)
                UnlockOutcome.Failed("Keystore error — ${e.message ?: "key protection failed"}")
            } catch (e: Exception) {
                derived?.fill(0)
                sessions.lock(contactId)
                UnlockOutcome.Failed(e.message ?: "unlock failed")
            }
        }

    /** Encrypts [plaintext] for the contact's current era and returns the CK1 envelope. */
    suspend fun encryptDraft(contactId: ContactId, plaintext: String): EncryptOutcome =
        withContext(dispatchers.io) {
            if (plaintext.isEmpty()) return@withContext EncryptOutcome.EmptyDraft
            // v0.5.1: a UTF-8 byte is never shorter than a char, so a
            // character count over the cap is DEFINITELY oversized — reject
            // before the toByteArray below materialises the full encoding.
            // A pasted multi-hundred-MiB draft used to OOM-crash right here.
            if (plaintext.length > Ck1Limits.MAX_PLAINTEXT_BYTES) {
                return@withContext EncryptOutcome.Failed(CryptoError.InputTooLarge)
            }

            val era = sessionKeys.latestEra(contactId)
                ?: return@withContext EncryptOutcome.NoSession

            val session = sessions.activeSession(contactId)
                ?: return@withContext EncryptOutcome.NeedsUnlock
            if (session.sessionId != era.sessionId) {
                // The active session belongs to an older era: refuse to send
                // under a stale key and ask for a fresh unlock.
                sessions.lock(contactId)
                return@withContext EncryptOutcome.NeedsUnlock
            }

            when (val result = provider.encrypt(
                plaintext = plaintext.toByteArray(Charsets.UTF_8),
                context = EncryptionContext(
                    mode = EncryptionMode.PASSPHRASE,
                    key = session.keyBytes,
                    keyId = era.keyId,
                    salt = era.salt,
                    kdfParams = era.params,
                    maxPlaintextBytes = Ck1Limits.MAX_PLAINTEXT_BYTES,
                    algorithm = algorithmSource.algorithm(),
                ),
            )) {
                is EncryptionResult.Success -> EncryptOutcome.Encrypted(MessageEncoder.encode(result.message))
                is EncryptionResult.Failure -> EncryptOutcome.Failed(result.error)
            }
        }

    /**
     * Decrypts a CK1 envelope, auto-resolving the contact from the envelope's keyId.
     *
     * v0.4.0: envelopes of superseded key eras are readable again. The era is
     * resolved from the envelope's keyId; when it is not the contact's latest
     * era, decryption uses that era's HISTORICAL session — and when none is
     * cached yet, the outcome is [DecryptOutcome.NeedsEraUnlock], which the
     * UI answers by collecting the passphrase and calling [unlockEra].
     */
    suspend fun decryptEnvelope(text: String): DecryptOutcome = withContext(dispatchers.io) {
        val trimmed = text.trim()
        if (!Ck1.isEnvelope(trimmed)) return@withContext DecryptOutcome.NotAnEnvelope

        val message = try {
            MessageDecoder.decode(trimmed)
        } catch (e: com.turnypt.core.messages.Ck1FormatException) {
            return@withContext DecryptOutcome.Failed(MessageDecoder.errorOf(e))
        }

        val era = sessionKeys.eraByKeyId(message.keyId)
            ?: return@withContext DecryptOutcome.UnknownKey

        val latest = sessionKeys.latestEra(era.contactId)
        val contactName = contacts.contactName(era.contactId) ?: "unknown"

        val keyBytes: ByteArray
        if (latest?.sessionId == era.sessionId) {
            val session = sessions.activeSession(era.contactId)
                ?: return@withContext DecryptOutcome.NeedsUnlock(era.contactId, contactName)
            if (session.sessionId != era.sessionId) {
                // The active session predates the current era (the contact
                // rotated while it was unlocked). Its stale key cannot serve
                // a latest-era envelope — ask for a fresh unlock instead of
                // guaranteeing an AEAD failure.
                return@withContext DecryptOutcome.NeedsUnlock(era.contactId, contactName)
            }
            keyBytes = session.keyBytes
        } else {
            val historical = sessions.historicalSession(era.sessionId)
                // Rare edge: this superseded era may still be the contact's
                // ACTIVE session (unlocked before the rotation, never
                // replaced since) — its key serves its own envelopes.
                ?: sessions.activeSession(era.contactId)?.takeIf { it.sessionId == era.sessionId }
                ?: return@withContext DecryptOutcome.NeedsEraUnlock(era.contactId, era.sessionId, contactName)
            keyBytes = historical.keyBytes
        }

        when (val result = provider.decrypt(
            message = message,
            context = DecryptionContext(
                mode = EncryptionMode.PASSPHRASE,
                key = keyBytes,
                maxSealedBytes = Ck1Limits.MAX_ENVELOPE_BYTES,
            ),
        )) {
            is DecryptionResult.Success -> {
                sessionKeys.markUsed(era.sessionId)
                DecryptOutcome.Decrypted(
                    plaintext = String(result.plaintext, Charsets.UTF_8),
                    contactId = era.contactId,
                    contactName = contactName,
                )
            }
            is DecryptionResult.Failure -> DecryptOutcome.Failed(result.error)
        }
    }

    /**
     * v0.4.0 (old-key-era decryption): unlocks a SUPERSEDED key era of the
     * contact with the passphrase that era was created with. The re-derived
     * key lands in the session manager's bounded HISTORICAL cache — it never
     * becomes the contact's sending key, so unlocking an old era can never
     * degrade encryption under the current one.
     *
     * Asking for the LATEST era here is not an error: it delegates to
     * [unlockContact] with identical semantics.
     *
     * Verification and error honesty mirror [unlockContact]: the
     * Keystore-wrapped digest verifier rejects wrong passphrases BEFORE
     * activation; a [KeyProtectionException] is infrastructure and is
     * reported as Failed, never as a false "wrong passphrase". Unlike
     * [unlockContact], a failure here does NOT lock the contact's active
     * session — the active (latest-era) session was never part of this
     * attempt.
     */
    suspend fun unlockEra(contactId: ContactId, eraSessionId: SessionId, passphrase: CharArray): UnlockOutcome =
        withContext(dispatchers.io) {
            KeyPolicy.passphraseProblem(passphrase)?.let { return@withContext UnlockOutcome.Rejected(it) }

            val era = sessionKeys.eraBySessionId(eraSessionId)
                ?: return@withContext UnlockOutcome.NoSession
            if (era.contactId != contactId) {
                return@withContext UnlockOutcome.Failed("era does not belong to this contact")
            }
            val latest = sessionKeys.latestEra(contactId)
            if (latest?.sessionId == eraSessionId) {
                // Not actually superseded — same path as a normal unlock.
                return@withContext unlockContact(contactId, passphrase)
            }

            if (sessions.historicalSession(eraSessionId) != null) {
                return@withContext UnlockOutcome.AlreadyUnlocked
            }

            val timeout = sessionTimeout.timeoutMillis()
            var derived: ByteArray? = null
            try {
                val bytes = sessions.deriveKey(passphrase, era.salt, era.params)
                derived = bytes
                verifyAndPersistVerifier(era, bytes)?.let { verdict ->
                    derived?.fill(0)
                    return@withContext verdict
                }
                sessions.activateHistorical(
                    contactId = contactId,
                    sessionId = era.sessionId,
                    keyId = era.keyId,
                    salt = era.salt,
                    params = era.params,
                    keyBytes = bytes,
                    timeoutMillis = timeout,
                )
                // activateHistorical() copies the buffer, like activate():
                // the historical session owns its key material from here on.
                derived?.fill(0)
                UnlockOutcome.Unlocked
            } catch (e: KdfMemoryException) {
                // v0.5.1: mirror unlockContact — the old era's profile does
                // not fit this device's heap; honest outcome, no crash.
                UnlockOutcome.MemoryHungry(e.requiredBytes)
            } catch (e: KeyProtectionException) {
                derived?.fill(0)
                UnlockOutcome.Failed("Keystore error — ${e.message ?: "key protection failed"}")
            } catch (e: Exception) {
                derived?.fill(0)
                UnlockOutcome.Failed(e.message ?: "era unlock failed")
            }
        }

    suspend fun lockContact(contactId: ContactId): Boolean = sessions.lock(contactId)

    suspend fun lockAll(): Int = sessions.lockAll()

    suspend fun isUnlocked(contactId: ContactId): Boolean = sessions.activeSession(contactId) != null

    suspend fun activeSessionFingerprint(contactId: ContactId): String? =
        sessions.activeSession(contactId)?.fingerprint

    /**
     * v0.3.0 (biometric unlock): the session id of the contact's LATEST key
     * era, or null when the contact has no era yet. Combined with the
     * contact id it forms the biometric "contact key"
     * (`"$contactId:$eraId"`) used as wrap AAD — when the era rotates, the
     * AAD changes, the wrapped key no longer unwraps, and the stale wrap is
     * discarded instead of unlocking the wrong era.
     */
    suspend fun currentEraId(contactId: ContactId): SessionId? =
        sessionKeys.latestEra(contactId)?.sessionId

    /**
     * v0.3.0 (biometric unlock): returns a COPY of the active session's raw
     * key bytes so the caller can wrap them into the fingerprint-gated
     * BiometricKeyStore, or null when the contact is locked. The copy is
     * deliberately explicit — the session keeps owning its own buffer.
     */
    suspend fun exportSessionKey(contactId: ContactId): ByteArray? {
        val session = sessions.activeSession(contactId) ?: return null
        val keyBytes = session.keyBytes ?: return null
        return keyBytes.copyOf()
    }

    /**
     * v0.3.0 (biometric unlock): activates the current era with key material
     * that was remembered behind a fingerprint wrap. The bytes were derived
     * and verified when the user first unlocked with the passphrase — no
     * re-derivation and no passphrase verification happen here; the GCM
     * authentication of the wrap (AAD = "contactId:eraId") is what binds the
     * bytes to THIS era. The caller's buffer is wiped on every path.
     */
    suspend fun unlockContactWithKey(contactId: ContactId, keyBytes: ByteArray): UnlockOutcome =
        withContext(dispatchers.io) {
            try {
                val era = sessionKeys.latestEra(contactId)
                if (era == null) {
                    keyBytes.fill(0)
                    return@withContext UnlockOutcome.NoSession
                }
                if (sessions.activeSession(contactId)?.sessionId == era.sessionId) {
                    keyBytes.fill(0)
                    return@withContext UnlockOutcome.AlreadyUnlocked
                }
                val timeout = sessionTimeout.timeoutMillis()
                sessions.activate(
                    contactId = contactId,
                    sessionId = era.sessionId,
                    keyId = era.keyId,
                    salt = era.salt,
                    params = era.params,
                    keyBytes = keyBytes,
                    timeoutMillis = timeout,
                )
                UnlockOutcome.Unlocked
            } catch (e: Exception) {
                sessions.lock(contactId)
                UnlockOutcome.Failed(e.message ?: "unlock failed")
            } finally {
                keyBytes.fill(0)
            }
        }

    /**
     * The shared verifier step of [unlockContact] and [unlockEra] (extracted
     * in v0.4.0 so the two paths cannot drift):
     *
     *  - wrapped verifier present: unwrap it (device-bound), compare against
     *    [derived] — mismatch → [UnlockOutcome.WrongPassphrase]. A v1
     *    full-key verifier is transparently rewritten to the v2 digest form
     *    (best-effort, like the initial persist).
     *  - no verifier yet (first unlock of this era on this device): persist
     *    the digest verifier best-effort so future wrong passphrases can be
     *    rejected at unlock time. Verification is defence in depth, not a
     *    functional requirement.
     *  - a [KeyProtectionException] from the unwrap is INFRASTRUCTURE failure
     *    (the passphrase plays no part in the unwrap): reported as
     *    [UnlockOutcome.Failed], never as a false "wrong passphrase"
     *    (deep-scan finding 2).
     *
     * Returns null when verification passed. The caller owns [derived] and
     * wipes it on every path.
     */
    private suspend fun verifyAndPersistVerifier(era: ContactSession, derived: ByteArray): UnlockOutcome? {
        val wrapped = sessionKeys.wrappedKeyOf(era.sessionId)
        if (wrapped == null) {
            runCatching {
                sessionKeys.storeWrappedKey(
                    era.sessionId,
                    protector.wrap(UnlockVerifier.toStorable(derived), era.keyId),
                )
            }
            return null
        }
        return try {
            val stored = protector.unwrap(wrapped, era.keyId)
            val matched = UnlockVerifier.matches(stored, derived)
            val legacy = UnlockVerifier.isLegacy(stored)
            stored.fill(0) // the unwrapped verifier never outlives the check
            if (!matched) {
                UnlockOutcome.WrongPassphrase
            } else {
                if (legacy) {
                    // v1 -> v2 migration: replace the full-key verifier with
                    // the digest form. Best-effort, like the initial persist.
                    runCatching {
                        sessionKeys.storeWrappedKey(
                            era.sessionId,
                            protector.wrap(UnlockVerifier.toStorable(derived), era.keyId),
                        )
                    }
                }
                null
            }
        } catch (e: KeyProtectionException) {
            UnlockOutcome.Failed("Keystore error — ${e.message ?: "key protection failed"}")
        }
    }
}
