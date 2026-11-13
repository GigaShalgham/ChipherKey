package com.turnypt.app.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Bridges the UI to the system fingerprint prompt (v0.3.0).
 *
 * Every operation goes through a [BiometricPrompt.CryptoObject] so the
 * Keystore cipher itself is what the fingerprint authorizes — Turnypt
 * never receives or stores biometric data, and a failed/expired
 * authorization simply yields no key bytes.
 *
 * Results are delivered as callbacks on the main thread carrying an
 * OPTIONAL failure reason (v0.5.1):
 *  - [unlockKey]: the unwrapped session key, or null (nothing remembered,
 *    stale wrap, user cancel, or hardware failure — the passphrase path
 *    always remains available). The reason string is null for a plain
 *    user cancel and carries the system's own message otherwise;
 *  - [rememberKey]: whether the wrap persisted. The plaintext buffer
 *    is zeroed on EVERY path, success or failure.
 *
 * v0.5.1 hardening (reported: "fingerprint didn't work anywhere"):
 *  - a canAuthenticate(BIOMETRIC_STRONG) pre-flight runs BEFORE the prompt
 *    is even constructed — a device that lost its strong enrolment (or
 *    never had it) now returns immediately with a localized reason instead
 *    of relying on the prompt to fail in OEM-specific ways;
 *  - the STRONG-gate reason is localized via [BiometricDiagnostics];
 *  - the prompt library was moved to the maintained 1.2.0-alpha05 line
 *    (1.1.0 silently failed to show the prompt on Android 12+ with
 *    BIOMETRIC_STRONG + CryptoObject on several OEM builds).
 */
class BiometricPromptHelper(
    private val activity: FragmentActivity,
    private val store: BiometricKeyStore,
) {

    private val executor = ContextCompat.getMainExecutor(activity)

    fun canPrompt(): Boolean = store.isDeviceCapable()

    /**
     * Localized "why not" for a device that cannot do Strong-class auth
     * right now, or null when it can.
     */
    private fun gateReason(): String? {
        val gate = BiometricDiagnostics.gateFor(store.strongAuthResult())
        return if (gate == BiometricGate.AVAILABLE) {
            null
        } else {
            activity.getString(BiometricDiagnostics.reasonRes(gate))
        }
    }

    /** Unwrap the remembered key for [contactKey] behind the fingerprint. */
    fun unlockKey(contactKey: String, onResult: (ByteArray?, String?) -> Unit) {
        val wrapped = store.readWrapped(contactKey)
        if (wrapped == null) {
            onResult(null, null)
            return
        }
        val cipher = store.newUnwrapCipher(wrapped)
        if (cipher == null) {
            // Blob too short or Keystore unavailable: the entry can never
            // open again — discard it so the UI stops offering it.
            store.clear(contactKey)
            onResult(null, null)
            return
        }
        gateReason()?.let { reason ->
            // v0.5.1: do not even build the prompt when the STRONG gate is
            // closed — report the honest, localized reason immediately.
            onResult(null, reason)
            return
        }
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    onResult(null, reasonOrNull(code, message))
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authorized = result.cryptoObject?.cipher
                    if (authorized == null) {
                        onResult(null, null)
                        return
                    }
                    try {
                        val keyBytes = store.finishUnwrap(authorized, wrapped, aad(contactKey))
                        if (keyBytes.isEmpty()) {
                            onResult(null, null)
                        } else {
                            onResult(keyBytes, null)
                        }
                    } catch (e: Exception) {
                        // GCM tag mismatch: the era rotated since the wrap
                        // (AAD changed) or the blob is corrupt — discard.
                        store.clear(contactKey)
                        onResult(null, null)
                    }
                }
            },
        )
        prompt.authenticate(
            promptInfo(
                titleRes = com.turnypt.app.R.string.biometric_prompt_title_unlock,
            ),
            BiometricPrompt.CryptoObject(cipher),
        )
    }

    /**
     * Wrap [plain] for [contactKey] behind the fingerprint and persist it.
     * [plain] is wiped on every path — the caller never has to do it.
     */
    fun rememberKey(contactKey: String, plain: ByteArray, onDone: (Boolean, String?) -> Unit) {
        val cipher = store.newWrapCipher()
        if (cipher == null) {
            plain.fill(0)
            onDone(false, null)
            return
        }
        gateReason()?.let { reason ->
            plain.fill(0)
            onDone(false, reason)
            return
        }
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    plain.fill(0)
                    onDone(false, reasonOrNull(code, message))
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authorized = result.cryptoObject?.cipher
                    if (authorized == null) {
                        plain.fill(0)
                        onDone(false, null)
                        return
                    }
                    try {
                        val blob = store.finishWrap(authorized, plain, aad(contactKey))
                        plain.fill(0)
                        store.persistWrapped(contactKey, blob)
                        onDone(true, null)
                    } catch (e: Exception) {
                        plain.fill(0)
                        onDone(false, null)
                    }
                }
            },
        )
        prompt.authenticate(
            promptInfo(
                titleRes = com.turnypt.app.R.string.biometric_prompt_title_remember,
            ),
            BiometricPrompt.CryptoObject(cipher),
        )
    }

    /**
     * A user cancel / "Not now" is not an error worth a banner — null.
     * Every other authentication error carries the system's own localized
     * message so the notice can say what actually happened.
     */
    private fun reasonOrNull(code: Int, message: CharSequence): String? = when (code) {
        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
        BiometricPrompt.ERROR_USER_CANCELED,
        -> null
        else -> message.toString().ifBlank { "authentication error $code" }
    }

    private fun promptInfo(titleRes: Int): BiometricPrompt.PromptInfo =
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(titleRes))
            .setSubtitle(activity.getString(com.turnypt.app.R.string.biometric_prompt_subtitle))
            .setNegativeButtonText(
                activity.getString(com.turnypt.app.R.string.biometric_prompt_negative),
            )
            // v0.4.1: STRONG only — a CryptoObject authorizes a Keystore key
            // created with AUTH_BIOMETRIC_STRONG, so a Class 2 sensor must
            // never be offered the prompt (v0.4.0 allowed WEAK|STRONG here,
            // which let weak sensors reach a cipher they can never authorize).
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setConfirmationRequired(false)
            .build()

    private fun aad(contactKey: String): ByteArray = contactKey.toByteArray(Charsets.UTF_8)
}
