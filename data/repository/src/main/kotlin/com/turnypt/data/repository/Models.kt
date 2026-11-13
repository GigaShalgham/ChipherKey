package com.turnypt.data.repository

import com.turnypt.core.common.ContactId
import com.turnypt.core.common.SessionId
import com.turnypt.core.crypto.Argon2Params
import com.turnypt.core.messages.InviteCodec
import com.turnypt.core.messages.SessionInvite

/**
 * Domain-level contact (no persistence annotations).
 */
data class Contact(
    val id: ContactId,
    val displayName: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * Domain-level key era belonging to a contact.
 *
 * Carries no secret: the passphrase lives only in the user's head, and the
 * derived key only in [com.turnypt.core.sessions.KeySessionManager] or in
 * its Keystore-wrapped form.
 */
data class ContactSession(
    val sessionId: SessionId,
    val contactId: ContactId,
    val keyId: ByteArray,
    val salt: ByteArray,
    val params: Argon2Params,
    val createdAt: Long,
    val hasWrappedKey: Boolean,
    /** v0.5.2: true when the era arrived via the peer's CKS1 invite. */
    val imported: Boolean,
    val lastUsedAt: Long?,
) {
    /** The public invite carrying this era's parameters. */
    val invite: SessionInvite
        get() = SessionInvite(keyId = keyId, salt = salt, params = params)

    /** Encoded CKS1 invite code, ready to share over any channel. */
    val inviteCode: String
        get() = InviteCodec.encode(invite)

    /**
     * v0.5.2: THIS device is choosing the shared passphrase right now — the
     * era was created locally (no friend invite was entered, so this side
     * invents the passphrase) and it has never been unlocked here (the
     * passphrase is not established yet). Only then is a "suggest a strong
     * passphrase" affordance honest; an imported era means the peer already
     * picked the passphrase the user must type.
     */
    val awaitingFirstPassphrase: Boolean
        get() = !imported && !hasWrappedKey

    override fun equals(other: Any?): Boolean =
        other is ContactSession &&
            sessionId == other.sessionId &&
            contactId == other.contactId &&
            keyId.contentEquals(other.keyId) &&
            salt.contentEquals(other.salt) &&
            params == other.params &&
            createdAt == other.createdAt &&
            hasWrappedKey == other.hasWrappedKey &&
            imported == other.imported &&
            lastUsedAt == other.lastUsedAt

    override fun hashCode(): Int {
        var result = sessionId.hashCode()
        result = 31 * result + contactId.hashCode()
        result = 31 * result + keyId.contentHashCode()
        result = 31 * result + salt.contentHashCode()
        result = 31 * result + params.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + hasWrappedKey.hashCode()
        result = 31 * result + imported.hashCode()
        result = 31 * result + (lastUsedAt?.hashCode() ?: 0)
        return result
    }
}
