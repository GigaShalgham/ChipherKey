package com.turnypt.core.messages

import com.turnypt.core.crypto.Argon2Params
import com.turnypt.core.crypto.KdfAlgorithm

/**
 * A CKS1 session invite: the public parameters two devices must share to
 * derive the same session key from a shared passphrase.
 *
 * The invite carries NO secret: keyId and salt are public values (the salt
 * already travels inside every CK1 envelope). The shared passphrase is
 * exchanged out of band — ideally in person — and never inside the invite.
 */
data class SessionInvite(
    val keyId: ByteArray,
    val salt: ByteArray,
    val params: Argon2Params,
) {
    override fun equals(other: Any?): Boolean =
        other is SessionInvite &&
            keyId.contentEquals(other.keyId) &&
            salt.contentEquals(other.salt) &&
            params == other.params

    override fun hashCode(): Int = 31 * (31 * keyId.contentHashCode() + salt.contentHashCode()) + params.hashCode()
}

/**
 * CKS1 wire format:
 *
 * ```
 * "CKS1:" + base64url(payload, unpadded)
 *
 * payload:
 *   offset  size  field
 *   0       1     invite version (0x01)
 *   1       1     KDF algorithm id (0x01 = Argon2id)
 *   2       7     Argon2id parameters (canonical encoding)
 *   9       16    session key identifier
 *   25      16    Argon2id salt
 * ```
 */
object InviteCodec {

    const val VERSION: Int = 0x01
    const val PAYLOAD_LENGTH: Int = 1 + 1 + Argon2Params.ENCODED_LENGTH + 16 + 16

    fun encode(invite: SessionInvite): String {
        require(invite.keyId.size == 16) { "keyId must be 16 bytes" }
        require(invite.salt.size == 16) { "salt must be 16 bytes" }

        val payload = ByteArray(PAYLOAD_LENGTH)
        payload[0] = VERSION.toByte()
        payload[1] = KdfAlgorithm.ARGON2ID.id.toByte()
        invite.params.encode().copyInto(payload, 2)
        invite.keyId.copyInto(payload, 9)
        invite.salt.copyInto(payload, 25)
        return Ck1.INVITE_MAGIC + Ck1.encodeBase64Url(payload)
    }

    /** Strict decoder: rejects wrong prefix, non-canonical base64, unknown versions/KDFs. */
    fun decode(text: String): SessionInvite {
        if (!Ck1.isInvite(text)) throw Ck1FormatException("missing CKS1: prefix")
        val payload = Ck1.decodeBase64Url(text.substring(Ck1.INVITE_MAGIC.length))
        if (payload.size != PAYLOAD_LENGTH) throw Ck1FormatException("invite payload must be $PAYLOAD_LENGTH bytes")

        if ((payload[0].toInt() and 0xFF) != VERSION) {
            throw Ck1FormatException("unsupported invite version ${payload[0].toInt() and 0xFF}")
        }
        val kdfId = payload[1].toInt() and 0xFF
        if (kdfId != KdfAlgorithm.ARGON2ID.id) {
            throw Ck1FormatException("unsupported KDF id $kdfId")
        }
        val params = try {
            Argon2Params.decode(payload.copyOfRange(2, 9))
        } catch (e: IllegalArgumentException) {
            throw Ck1FormatException("invalid Argon2id parameters", e)
        }
        return SessionInvite(
            keyId = payload.copyOfRange(9, 25),
            salt = payload.copyOfRange(25, 41),
            params = params,
        )
    }
}
