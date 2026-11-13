package com.turnypt.data.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A locally managed contact ("key era owner").
 *
 * Contacts are purely local: no phone-book integration, no identifiers that
 * could leak to other apps. The id is a random UUID string.
 */
@Entity(
    tableName = "contacts",
    indices = [Index("displayName")],
)
data class ContactEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * v0.5.2: one captured clipboard clip — the user-facing clipboard
 * history ("the list of things you copied and had copied before").
 *
 * SECURITY CONTRACT:
 *  - entries are created ONLY from a focus-gated or user-initiated read
 *   (never a background listener — the app cannot and does not monitor the
 *   clipboard while another app owns it);
 *  - clips Turnypt itself copies as SENSITIVE (decrypted plaintext,
 *   suggested passphrases) are NEVER recorded, and the recorder treats
 *   them as already-seen so a later focus read cannot pick them up either;
 *  - [text] is capped at [com.turnypt.data.repository.ClipboardHistoryRepository.MAX_STORED_CHARS]
 *   characters ([truncated] says the original clip was longer) so a
 *   multi-megabyte copy cannot bloat the database;
 *  - the whole history is pruned to the newest [MAX_ENTRIES_BY_POLICY]
 *   entries and expires after a week (repository policy), and sits in the
 *   app's private, non-backup-up storage (allowBackup=false).
 */
@Entity(tableName = "clipboard_history")
data class ClipboardHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val text: String,
    /** True when the clip looks like a CK1 envelope (monospace rendering). */
    val isEnvelope: Boolean,
    /** True when [text] was cut short by the storage cap. */
    val truncated: Boolean,
    val capturedAt: Long,
) {
    companion object {
        /** Storage-side safety net; the repository enforces the real cap. */
        const val MAX_ENTRIES_BY_POLICY = 25
    }
}

/**
 * One key era belonging to a contact.
 *
 * A "key era" is the tuple (keyId, salt, Argon2id params). The passphrase is
 * never stored. `wrappedKey` holds the Android-Keystore-wrapped derived key
 * after the first successful unlock on this device — it is device-bound,
 * never leaves the device, and serves as the on-device passphrase verifier.
 * Older eras of a contact may coexist (rotation creates a new era); only the
 * newest era is used for sending.
 *
 * v0.5.2: `imported` records HOW the era arrived — created locally (the
 * user left the invite field empty, so THIS side picks the shared
 * passphrase) or imported from the peer's CKS1 invite (the PEER already
 * picked the passphrase). It gates the "suggest a strong passphrase"
 * affordance at the passphrase-entry point (user report: offering a fresh
 * random passphrase to someone who must type their contact's existing one
 * is misleading).
 */
@Entity(
    tableName = "session_keys",
    foreignKeys = [
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["id"],
            childColumns = ["contactId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("contactId"),
        Index(value = ["keyId"], unique = true),
    ],
)
data class SessionKeyEntity(
    @PrimaryKey val sessionId: String,
    val contactId: String,
    /** 16-byte public session key identifier (CK1 envelope binding). */
    val keyId: ByteArray,
    /** 16-byte Argon2id salt. Public: also present in every envelope/invite. */
    val salt: ByteArray,
    val memoryExponent: Int,
    val iterations: Int,
    val parallelism: Int,
    /** Keystore-wrapped derived key (device-bound verifier), or null until first unlock. */
    val wrappedKey: ByteArray?,
    /** v0.5.2: true when the era arrived via the peer's invite (friend chose the passphrase). */
    val imported: Boolean,
    val createdAt: Long,
    val lastUsedAt: Long?,
) {
    override fun equals(other: Any?): Boolean =
        other is SessionKeyEntity &&
            sessionId == other.sessionId &&
            contactId == other.contactId &&
            keyId.contentEquals(other.keyId) &&
            salt.contentEquals(other.salt) &&
            memoryExponent == other.memoryExponent &&
            iterations == other.iterations &&
            parallelism == other.parallelism &&
            (wrappedKey?.contentEquals(other.wrappedKey) ?: (other.wrappedKey == null)) &&
            imported == other.imported &&
            createdAt == other.createdAt &&
            lastUsedAt == other.lastUsedAt

    override fun hashCode(): Int {
        var result = sessionId.hashCode()
        result = 31 * result + contactId.hashCode()
        result = 31 * result + keyId.contentHashCode()
        result = 31 * result + salt.contentHashCode()
        result = 31 * result + memoryExponent
        result = 31 * result + iterations
        result = 31 * result + parallelism
        result = 31 * result + (wrappedKey?.contentHashCode() ?: 0)
        result = 31 * result + imported.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + (lastUsedAt?.hashCode() ?: 0)
        return result
    }
}
