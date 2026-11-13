package com.turnypt.plugins.api

/**
 * Mode B (public-key) protocol contract.
 *
 * STATUS (v0.1): INTERFACE ONLY — no implementation is bundled. The CK1
 * wire format reserves mode 0x02 for public-key envelopes; nothing in the
 * shipped application produces or consumes them. See docs/PLUGINS.md for
 * the evaluation of candidate protocols (Signal/libsignal and friends) and
 * the reasons integration was deferred.
 *
 * A plugin implementing this interface must:
 *  - authenticate every protocol field it serializes (AAD binding),
 *  - never emit or accept unauthenticated plaintext,
 *  - keep private keys inside the Android Keystore where technically
 *    possible.
 */
interface PublicKeyCipherPlugin {

    /** Stable plugin identifier, e.g. "signal-protocol". */
    val id: String

    /** Human-readable name shown in the UI. */
    val displayName: String

    /** Version of the plugin's own protocol semantics. */
    val protocolVersion: Int

    /** Generates a new identity key pair for this device. */
    suspend fun generateKeyPair(): PluginKeyPair

    /** Seals [plaintext] for the recipient's public key. */
    suspend fun encrypt(plaintext: ByteArray, recipientPublicKey: ByteArray): PluginSealedMessage

    /** Opens a sealed message with this device's private key. */
    suspend fun decrypt(sealed: PluginSealedMessage, privateKey: ByteArray): ByteArray
}

/** A generated identity key pair. Private material must be treated as sensitive. */
data class PluginKeyPair(
    val publicKey: ByteArray,
    val privateKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is PluginKeyPair &&
            publicKey.contentEquals(other.publicKey) &&
            privateKey.contentEquals(other.privateKey)

    override fun hashCode(): Int = 31 * publicKey.contentHashCode() + privateKey.contentHashCode()
}

/** Plugin-produced sealed payload (the body of a future CK1 mode-0x02 envelope). */
data class PluginSealedMessage(
    val algorithmId: Int,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is PluginSealedMessage &&
            algorithmId == other.algorithmId &&
            payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * algorithmId + payload.contentHashCode()
}
