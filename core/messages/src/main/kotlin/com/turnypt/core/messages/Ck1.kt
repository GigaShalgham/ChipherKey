package com.turnypt.core.messages

/**
 * Hard limits enforced by the CK1 codec.
 *
 * Plaintext limit is 16 KiB — comfortably above a long chat message and
 * below what any mainstream messaging app would truncate. The envelope
 * limit leaves room for the ~1.37x base64 expansion plus header.
 */
object Ck1Limits {
    const val MAX_PLAINTEXT_BYTES: Int = 16 * 1024
    const val MAX_ENVELOPE_BYTES: Int = 256 * 1024
    const val MAX_ENCODED_CHARS: Int = 360_000
}

/**
 * Turnypt wire format (v1.1.0: the prefix moved off the retired CipherKey
 * brand — the format itself is unchanged):
 *
 * ```
 * "TNP1:" + base64url(header || ciphertext, unpadded)   ← written by this build
 * "CK1:"  + the same payload                            ← CipherKey-era, still decoded
 * ```
 *
 * The app auto-detects which prefix a message carries and never tells the
 * user — old and new envelopes look identical to every surface. The byte
 * layout behind the prefix (including the keyId that resolves the contact)
 * is byte-for-byte the same in both, so a legacy envelope decodes through
 * the exact same path.
 *
 * base64url uses the URL-safe alphabet ('-', '_'), no padding, and both
 * cases are significant. The decoder is strict: '=' padding, '+'/'/'
 * alphabet, whitespace and unknown prefixes are rejected.
 */
object Ck1 {

    /** Envelope prefix WRITTEN by this build (Turnypt, format v1). */
    const val MAGIC: String = "TNP1:"

    /** CipherKey-era envelope prefix — never written, always decoded. */
    const val LEGACY_MAGIC: String = "CK1:"

    /** Session-invite prefix WRITTEN by this build (Turnypt Session, v1). */
    const val INVITE_MAGIC: String = "TNS1:"

    /** CipherKey-era invite prefix — never written, always decoded. */
    const val LEGACY_INVITE_MAGIC: String = "CKS1:"

    /** Encodes bytes as unpadded base64url (encoder is always canonical). */
    fun encodeBase64Url(bytes: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** Strict base64url decoder: rejects padding and standard alphabet. */
    fun decodeBase64Url(text: String): ByteArray {
        if (text.isEmpty()) throw Ck1FormatException("empty payload")
        if (text.any { it == '=' || it == '+' || it == '/' || it == ' ' || it == '\n' || it == '\r' || it == '\t' }) {
            throw Ck1FormatException("invalid base64url character")
        }
        // Reject orphaned final bits that would decode leniently (e.g. "AB").
        return try {
            java.util.Base64.getUrlDecoder().decode(text)
        } catch (e: IllegalArgumentException) {
            throw Ck1FormatException("invalid base64url encoding")
        }
    }

    /** True when [text] starts with a Turnypt OR legacy CipherKey envelope prefix. */
    fun isEnvelope(text: String): Boolean =
        text.startsWith(MAGIC) || text.startsWith(LEGACY_MAGIC)

    /** True when [text] starts with a Turnypt OR legacy CipherKey invite prefix. */
    fun isInvite(text: String): Boolean =
        text.startsWith(INVITE_MAGIC) || text.startsWith(LEGACY_INVITE_MAGIC)

    /**
     * The base64url payload after the envelope prefix — new or legacy — or
     * null when [text] carries neither prefix.
     */
    fun envelopePayload(text: String): String? = when {
        text.startsWith(MAGIC) -> text.substring(MAGIC.length)
        text.startsWith(LEGACY_MAGIC) -> text.substring(LEGACY_MAGIC.length)
        else -> null
    }

    /**
     * The base64url payload after the invite prefix — new or legacy — or
     * null when [text] carries neither prefix.
     */
    fun invitePayload(text: String): String? = when {
        text.startsWith(INVITE_MAGIC) -> text.substring(INVITE_MAGIC.length)
        text.startsWith(LEGACY_INVITE_MAGIC) -> text.substring(LEGACY_INVITE_MAGIC.length)
        else -> null
    }
}

class Ck1FormatException(message: String, cause: Throwable? = null) : Exception(message, cause)
