package org.cipherkey.app.companion

/**
 * Route names of the main-app destinations (v0.3.0 five-tab navigation).
 * Kept next to the companion code so both worlds share one vocabulary.
 *
 * v0.6.0: the ENCRYPT/DECRYPT routes take an optional `mode` argument
 * ("text" | "image") so Quick Access and the floating assistant can
 * deep-link straight into the image segments of the tabs.
 */
object DestinationRoute {
    const val HOME = "home"
    const val ENCRYPT = "encrypt"
    const val DECRYPT = "decrypt"
    const val CONTACTS = "contacts"
    const val SETTINGS = "settings"

    const val ARG_MODE = "mode"
    const val MODE_TEXT = "text"
    const val MODE_IMAGE = "image"

    /** v0.6.0: typed deep-link routes into the Text | Image segments. */
    const val ENCRYPT_IMAGE = "$ENCRYPT?$ARG_MODE=$MODE_IMAGE"
    const val DECRYPT_IMAGE = "$DECRYPT?$ARG_MODE=$MODE_IMAGE"
}
