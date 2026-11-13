package com.turnypt.app.security

/**
 * What the system biometric prompt is being asked to do (v0.3.0).
 *
 * The request carries the "contact key" (`"$contactId:$eraId"`) so the UI can
 * render the right title and the store can bind the wrap to the exact key
 * era: when the era rotates, the AAD no longer matches, the wrap no longer
 * opens, and the stale entry is discarded instead of unlocking the wrong era.
 */
sealed interface BiometricPromptRequest {

    /** The contact key the prompt should unwrap. */
    val contactKey: String

    /** Re-open a remembered key to unlock a session. */
    data class Unlock(override val contactKey: String) : BiometricPromptRequest

    /** Wrap the just-unlocked key so it can be re-opened by fingerprint. */
    data class Remember(override val contactKey: String) : BiometricPromptRequest
}
