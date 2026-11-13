package com.turnypt.data.repository

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import kotlinx.coroutines.withContext
import com.turnypt.core.common.ContactId
import com.turnypt.core.common.DispatcherProvider
import com.turnypt.core.common.SessionId
import com.turnypt.core.crypto.AeadFailure
import com.turnypt.core.crypto.CipherAlgorithm
import com.turnypt.core.crypto.Ckf1
import com.turnypt.core.crypto.CkfChunkedCipher
import com.turnypt.core.crypto.CryptoError
import com.turnypt.core.crypto.ProtocolRandom
import com.turnypt.core.sessions.KeySessionManager

/**
 * Supplies the cipher for NEW encryptions (v0.6.0). Abstracted like
 * [SessionTimeoutSource] so the facade stays JVM-unit-testable; the DI
 * binding resolves the user's preference (including Automatic's measured
 * choice) against the live settings.
 */
fun interface CipherAlgorithmSource {
    suspend fun algorithm(): CipherAlgorithm
}

/** Outcome of encrypting a file for a contact (v0.6.0). */
sealed interface MediaEncryptOutcome {
    /** The container was streamed to the target; [plaintextBytes] were read. */
    data class Encrypted(
        val contactId: ContactId,
        val algorithm: CipherAlgorithm,
        val plaintextBytes: Long,
    ) : MediaEncryptOutcome

    /** The contact's current era is not unlocked; ask for the passphrase. */
    data object NeedsUnlock : MediaEncryptOutcome

    /** The contact has no key era yet (create or import one first). */
    data object NoSession : MediaEncryptOutcome

    data class Failed(val error: CryptoError) : MediaEncryptOutcome
}

/** Outcome of decrypting a CKF1 container (v0.6.0). */
sealed interface MediaDecryptOutcome {
    data class Decrypted(
        val contactId: ContactId,
        val contactName: String,
        val algorithm: CipherAlgorithm,
        val plaintextBytes: Long,
    ) : MediaDecryptOutcome

    data class NeedsUnlock(val contactId: ContactId, val contactName: String) : MediaDecryptOutcome

    /**
     * The container belongs to a SUPERSEDED key era — unlock that era with
     * the passphrase it was created with (mirrors the text flow's
     * old-key-era decryption).
     */
    data class NeedsEraUnlock(
        val contactId: ContactId,
        val eraSessionId: SessionId,
        val contactName: String,
    ) : MediaDecryptOutcome

    /** The stream does not start with the CKF1 magic. */
    data object NotACipherFile : MediaDecryptOutcome

    /** The header's keyId matches no era on this device. */
    data object UnknownKey : MediaDecryptOutcome

    data class Failed(val error: CryptoError) : MediaDecryptOutcome
}

/**
 * The image/file twin of [MessageCipher] (v0.6.0) — the SAME contacts,
 * the SAME key eras, the SAME unlocked sessions and the SAME unlock
 * semantics. Nothing here derives or stores keys of its own: a contact
 * unlocked once (passphrase or fingerprint) encrypts both text and files.
 *
 * Streams CKF1 containers chunk-by-chunk (see Ckf1): peak memory is one
 * 64 KiB chunk regardless of file size — the 256 MiB OOM lesson of
 * v0.5.1, applied by construction.
 */
class MediaCipher(
    private val contacts: ContactRepository,
    private val sessionKeys: SessionKeyRepository,
    private val sessions: KeySessionManager,
    private val chunked: CkfChunkedCipher,
    private val random: ProtocolRandom,
    private val algorithmSource: CipherAlgorithmSource,
    private val dispatchers: DispatcherProvider,
) {

    /**
     * Streams [source] into a CKF1 container on [target], sealed for the
     * contact's CURRENT era. Mirrors MessageCipher.encryptDraft: a stale
     * active session (era rotated while unlocked) is refused — locked,
     * then [MediaEncryptOutcome.NeedsUnlock].
     */
    suspend fun encryptFile(
        contactId: ContactId,
        source: InputStream,
        target: OutputStream,
        onProgress: (plaintextBytes: Long) -> Unit = {},
    ): MediaEncryptOutcome = withContext(dispatchers.io) {
        val era = sessionKeys.latestEra(contactId)
            ?: return@withContext MediaEncryptOutcome.NoSession

        val session = sessions.activeSession(contactId)
            ?: return@withContext MediaEncryptOutcome.NeedsUnlock
        if (session.sessionId != era.sessionId) {
            // Same rule as the text path: never send under a stale key.
            sessions.lock(contactId)
            return@withContext MediaEncryptOutcome.NeedsUnlock
        }

        val algorithm = try {
            algorithmSource.algorithm()
        } catch (e: Exception) {
            // The preference source is settings + a cached benchmark; a
            // failure there must not fall back to something unauthenticated
            // — it stays on the protocol default.
            CipherAlgorithm.AES_256_GCM
        }

        var plaintextBytes = 0L
        try {
            chunked.encrypt(
                key = session.keyBytes,
                keyId = era.keyId,
                salt = era.salt,
                params = era.params,
                algorithm = algorithm,
                baseNonce = random.nonce(algorithm),
                input = source,
                output = target,
                onProgress = {
                    plaintextBytes = it
                    onProgress(it)
                },
            )
            sessionKeys.markUsed(era.sessionId)
            MediaEncryptOutcome.Encrypted(contactId, algorithm, plaintextBytes)
        } catch (e: AeadFailure) {
            MediaEncryptOutcome.Failed(e.error)
        }
    }

    /**
     * Streams a CKF1 container from [source] to [target], auto-resolving
     * the contact from the header's keyId. Mirrors
     * MessageCipher.decryptEnvelope, including the old-key-era path.
     */
    suspend fun decryptFile(
        source: InputStream,
        target: OutputStream,
        onProgress: (plaintextBytes: Long) -> Unit = {},
    ): MediaDecryptOutcome = withContext(dispatchers.io) {
        // Peek the prefix to resolve the era BEFORE streaming the body,
        // then replay the prefix into the chunk decryptor (streams are not
        // rewindable).
        val prefix = ByteArray(Ckf1.PREFIX_LENGTH)
        var filled = 0
        var plaintextBytes = 0L
        while (filled < prefix.size) {
            val read = try {
                source.read(prefix, filled, prefix.size - filled)
            } catch (e: Exception) {
                return@withContext MediaDecryptOutcome.Failed(CryptoError.MalformedMessage)
            }
            if (read == -1) break
            filled += read
        }
        val header = try {
            Ckf1.parsePrefix(prefix.copyOf(filled))
        } catch (e: AeadFailure) {
            return@withContext when (e.error) {
                CryptoError.NotACipherFile -> MediaDecryptOutcome.NotACipherFile
                else -> MediaDecryptOutcome.Failed(e.error)
            }
        }

        val era = sessionKeys.eraByKeyId(header.keyId)
            ?: return@withContext MediaDecryptOutcome.UnknownKey

        val latest = sessionKeys.latestEra(era.contactId)
        val contactName = contacts.contactName(era.contactId) ?: "unknown"

        val keyBytes: ByteArray
        if (latest?.sessionId == era.sessionId) {
            val session = sessions.activeSession(era.contactId)
                ?: return@withContext MediaDecryptOutcome.NeedsUnlock(era.contactId, contactName)
            if (session.sessionId != era.sessionId) {
                // The active session predates the current era — its stale
                // key cannot serve this file; ask for a fresh unlock.
                return@withContext MediaDecryptOutcome.NeedsUnlock(era.contactId, contactName)
            }
            keyBytes = session.keyBytes
        } else {
            val historical = sessions.historicalSession(era.sessionId)
                ?: sessions.activeSession(era.contactId)?.takeIf { it.sessionId == era.sessionId }
                ?: return@withContext MediaDecryptOutcome.NeedsEraUnlock(
                    era.contactId,
                    era.sessionId,
                    contactName,
                )
            keyBytes = historical.keyBytes
        }

        val replay = SequenceInputStream(ByteArrayInputStream(prefix.copyOf(filled)), source)
        try {
            chunked.decrypt(
                input = replay,
                output = target,
                key = keyBytes,
                onProgress = { plaintextBytes = it },
            )
            sessionKeys.markUsed(era.sessionId)
            MediaDecryptOutcome.Decrypted(era.contactId, contactName, header.algorithm, plaintextBytes)
        } catch (e: AeadFailure) {
            when (e.error) {
                CryptoError.FileTruncated -> MediaDecryptOutcome.Failed(CryptoError.FileTruncated)
                else -> MediaDecryptOutcome.Failed(e.error)
            }
        }
    }
}
