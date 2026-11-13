package com.turnypt.core.messages

import com.turnypt.core.crypto.Argon2Params
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InviteCodecTest {

    private fun invite() = SessionInvite(
        keyId = ByteArray(16) { (it + 30).toByte() },
        salt = ByteArray(16) { (it + 90).toByte() },
        params = Argon2Params(14, 3, 1),
    )

    @Test
    fun `encode decode roundtrip`() {
        val code = InviteCodec.encode(invite())
        assertTrue(code.startsWith("CKS1:"))
        val decoded = InviteCodec.decode(code)
        val original = invite()
        assertTrue(decoded.keyId.contentEquals(original.keyId))
        assertTrue(decoded.salt.contentEquals(original.salt))
        assertEquals(original.params, decoded.params)
    }

    @Test
    fun `invite does not leak through equals mismatch on tampered key`() {
        val code = InviteCodec.encode(invite())
        val body = code.removePrefix("CKS1:")
        val bytes = Ck1.decodeBase64Url(body)
        bytes[9] = (bytes[9].toInt() xor 0x01).toByte()
        val tampered = "CKS1:" + Ck1.encodeBase64Url(bytes)
        val decoded = InviteCodec.decode(tampered)
        assertTrue(!decoded.keyId.contentEquals(invite().keyId))
    }

    @Test
    fun `wrong prefix is rejected`() {
        assertThrows(Ck1FormatException::class.java) {
            InviteCodec.decode("CK1:" + InviteCodec.encode(invite()).removePrefix("CKS1:"))
        }
    }

    @Test
    fun `padded or invalid base64 is rejected`() {
        assertThrows(Ck1FormatException::class.java) {
            InviteCodec.decode(InviteCodec.encode(invite()) + "=")
        }
    }

    @Test
    fun `payload of wrong length is rejected`() {
        assertThrows(Ck1FormatException::class.java) {
            InviteCodec.decode("CKS1:" + Ck1.encodeBase64Url(ByteArray(40)))
        }
    }

    @Test
    fun `unknown invite version is rejected`() {
        val code = InviteCodec.encode(invite())
        val bytes = Ck1.decodeBase64Url(code.removePrefix("CKS1:"))
        bytes[0] = 0x02
        assertThrows(Ck1FormatException::class.java) {
            InviteCodec.decode("CKS1:" + Ck1.encodeBase64Url(bytes))
        }
    }

    @Test
    fun `unknown kdf id is rejected`() {
        val code = InviteCodec.encode(invite())
        val bytes = Ck1.decodeBase64Url(code.removePrefix("CKS1:"))
        bytes[1] = 0x7F
        assertThrows(Ck1FormatException::class.java) {
            InviteCodec.decode("CKS1:" + Ck1.encodeBase64Url(bytes))
        }
    }

    @Test
    fun `out of range argon2 parameters are rejected`() {
        val code = InviteCodec.encode(invite())
        val bytes = Ck1.decodeBase64Url(code.removePrefix("CKS1:"))
        bytes[2] = 40 // memory exponent beyond MAX_MEMORY_EXPONENT
        assertThrows(Ck1FormatException::class.java) {
            InviteCodec.decode("CKS1:" + Ck1.encodeBase64Url(bytes))
        }
    }

    @Test
    fun `invite length is compact`() {
        // 41 bytes -> 55 base64url chars + prefix.
        assertEquals("CKS1:".length + 55, InviteCodec.encode(invite()).length)
    }
}
