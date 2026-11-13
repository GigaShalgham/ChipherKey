package com.turnypt.data.repository

import java.security.MessageDigest

/**
 * Format of the Keystore-wrapped on-device passphrase verifier
 * (deep-scan finding 5).
 *
 * History:
 *  - v1 (<= 0.2.1): the wrapped blob decrypted to the FULL 32-byte session
 *    key. The verifier is only ever read back for an equality check, so
 *    storing the raw key was pure added exposure: with the database plus the
 *    app's (non-exportable) Keystore key — e.g. a rooted/forensic device —
 *    all traffic decrypted without the passphrase.
 *  - v2 (this format): the wrapped blob decrypts to `0x02 || SHA-256(key)`.
 *    Wrong-passphrase rejection is identical (a constant-time digest
 *    comparison), but a database-plus-Keystore compromise now yields only a
 *    one-way digest of each key.
 *
 * v1 blobs are still accepted for comparison and transparently migrated to
 * v2 on the next successful unlock with the correct passphrase.
 */
internal object UnlockVerifier {

    private const val V2_PREFIX: Byte = 0x02
    private const val KEY_LENGTH = 32
    private const val DIGEST_LENGTH = 32

    /** The storable form of a verifier for [keyBytes]: `0x02 || SHA-256(key)`. */
    fun toStorable(keyBytes: ByteArray): ByteArray {
        val digest = sha256(keyBytes)
        return byteArrayOf(V2_PREFIX) + digest
    }

    /**
     * True when [keyBytes] matches the unwrapped verifier [stored], in
     * constant time regardless of which format the blob uses.
     */
    fun matches(stored: ByteArray, keyBytes: ByteArray): Boolean {
        return if (stored.size == 1 + DIGEST_LENGTH && stored[0] == V2_PREFIX) {
            MessageDigest.isEqual(stored.copyOfRange(1, stored.size), sha256(keyBytes))
        } else if (stored.size == KEY_LENGTH) {
            // v1 legacy: the blob is (or is compared against) the full key.
            MessageDigest.isEqual(stored, keyBytes)
        } else {
            false
        }
    }

    /** True when [stored] is a legacy v1 full-key verifier needing migration. */
    fun isLegacy(stored: ByteArray): Boolean = stored.size == KEY_LENGTH

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)
}
