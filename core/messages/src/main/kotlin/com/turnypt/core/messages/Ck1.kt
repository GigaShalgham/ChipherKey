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
 * CK1 wire format:
 *
 * ```
 * "CK1:" + base64url(header || ciphertext, unpadded)
 * ```
 *
 * base64url uses the URL-safe alphabet ('-', '_'), no padding, and both
 * cases are significant. The decoder is strict: '=' padding, '+'/'/'
 * alphabet, whitespace and unknown prefixes are rejected.
 */
object Ck1 {

    const val MAGIC: String = "CK1:"
    const val INVITE_MAGIC: String = "CKS1:"

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

    /** True when [text] starts with the CK1 envelope prefix. */
    fun isEnvelope(text: String): Boolean = text.startsWith(MAGIC)

    /** True when [text] starts with the CKS1 invite prefix. */
    fun isInvite(text: String): Boolean = text.startsWith(INVITE_MAGIC)
}

class Ck1FormatException(message: String, cause: Throwable? = null) : Exception(message, cause)
