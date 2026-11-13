package com.turnypt.core.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters

/**
 * HKDF-SHA256 (RFC 5869), lightweight BouncyCastle API (v0.6.0).
 *
 * Used by the CKF1 file format to derive a per-file subkey from the
 * contact-era session key, so streaming chunk nonces can never collide
 * across files under the same era key (the Tink streaming-AEAD argument,
 * see Ckf1 docs).
 */
object Hkdf {

    fun sha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, lengthBytes: Int): ByteArray {
        require(lengthBytes > 0) { "HKDF output length must be positive" }
        val generator = HKDFBytesGenerator(SHA256Digest())
        generator.init(HKDFParameters(ikm, salt, info))
        val out = ByteArray(lengthBytes)
        generator.generateBytes(out, 0, lengthBytes)
        return out
    }
}
