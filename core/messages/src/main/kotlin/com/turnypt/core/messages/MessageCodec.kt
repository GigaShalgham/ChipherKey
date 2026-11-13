package com.turnypt.core.messages

import com.turnypt.core.crypto.CryptoError
import com.turnypt.core.crypto.EncryptedMessage
import com.turnypt.core.crypto.ProtocolHeader
import com.turnypt.core.crypto.toByteArray

/**
 * Encodes and decodes the CK1 message envelope.
 *
 * The encoder produces the canonical form. The decoder is strict and
 * performs NO cryptographic work: it validates the prefix, encoding,
 * size limits and header consistency, and maps wire values to typed
 * enums (rejecting unknown versions/algorithms/modes/kdfs) before the
 * caller ever attempts authentication.
 */
object MessageEncoder {

    fun encode(message: EncryptedMessage): String {
        val payload = message.toByteArray()
        if (payload.size > Ck1Limits.MAX_ENVELOPE_BYTES) {
            throw Ck1FormatException("envelope exceeds ${Ck1Limits.MAX_ENVELOPE_BYTES} bytes")
        }
        return Ck1.MAGIC + Ck1.encodeBase64Url(payload)
    }
}

object MessageDecoder {

    fun decode(text: String): EncryptedMessage {
        if (text.length > Ck1Limits.MAX_ENCODED_CHARS) {
            throw Ck1FormatException("encoded message too large")
        }
        if (!Ck1.isEnvelope(text)) {
            throw Ck1FormatException("missing CK1: prefix")
        }
        val payload = try {
            Ck1.decodeBase64Url(text.substring(Ck1.MAGIC.length))
        } catch (e: Ck1FormatException) {
            throw e
        }
        if (payload.size > Ck1Limits.MAX_ENVELOPE_BYTES) {
            throw Ck1FormatException("payload too large")
        }
        return try {
            ProtocolHeader.parse(payload)
        } catch (e: com.turnypt.core.crypto.AeadFailure) {
            throw Ck1FormatException(e.error.message, e)
        }
    }

    /** Convenience mapping from codec failures to the crypto error taxonomy. */
    fun errorOf(e: Ck1FormatException): CryptoError = when (e.message) {
        "envelope exceeds ${Ck1Limits.MAX_ENVELOPE_BYTES} bytes",
        "encoded message too large",
        "payload too large" -> CryptoError.MessageTooLarge
        else -> CryptoError.MalformedMessage
    }
}
