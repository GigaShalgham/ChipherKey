package com.turnypt.core.crypto

/**
 * Wire identifiers for the CK1 protocol.
 *
 * Every identifier is a single byte. Unknown values MUST be rejected at
 * decode time; adding a value therefore requires a protocol version bump
 * (see docs/PROTOCOL.md).
 */
enum class EncryptionMode(val id: Int) {
    /** Shared-passphrase communication. */
    PASSPHRASE(0x01),

    /** Public-key communication. RESERVED — no implementation yet (see docs). */
    PUBLIC_KEY(0x02);

    companion object {
        fun fromId(id: Int): EncryptionMode? = entries.firstOrNull { it.id == id }
    }
}

enum class CipherAlgorithm(val id: Int, val keyLengthBytes: Int, val nonceLengthBytes: Int, val tagLengthBytes: Int) {
    /** AES-256-GCM with a 96-bit random nonce and a 128-bit tag. */
    AES_256_GCM(0x01, keyLengthBytes = 32, nonceLengthBytes = 12, tagLengthBytes = 16),

    /**
     * ChaCha20-Poly1305 (RFC 8439) with a 96-bit nonce and a 128-bit tag.
     *
     * v0.6.0: added as the second CK1 cipher. Wire-compatible addition —
     * old builds reject id 0x02 with `UnsupportedAlgorithm`, the protocol's
     * designed forward-compatible behavior (PROTOCOL.md §receiver policy).
     * Shares the AES layout constants (12-byte nonce, 16-byte tag), so the
     * CK1/CKF1 header geometry is unchanged.
     */
    CHACHA20_POLY1305(0x02, keyLengthBytes = 32, nonceLengthBytes = 12, tagLengthBytes = 16);

    companion object {
        fun fromId(id: Int): CipherAlgorithm? = entries.firstOrNull { it.id == id }
    }
}

/**
 * v0.6.0: every algorithm the CK1 v1 header can carry MUST share this
 * header geometry (nonce length and tag length). The header layout is
 * frozen at 55 bytes with a 12-byte nonce at offset 43; an algorithm that
 * cannot live inside it needs a protocol version bump, not an entry here.
 */
val CipherAlgorithm.ck1Compatible: Boolean
    get() = nonceLengthBytes == 12 && tagLengthBytes == 16

enum class KdfAlgorithm(val id: Int) {
    /** Argon2id, version 0x13, per RFC 9106. */
    ARGON2ID(0x01);

    companion object {
        fun fromId(id: Int): KdfAlgorithm? = entries.firstOrNull { it.id == id }
    }
}
