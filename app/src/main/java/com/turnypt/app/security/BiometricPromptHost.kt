package com.turnypt.app.security

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity

/**
 * Creates the prompt helper for the current screen (v0.3.0), or null when
 * the host is not a [FragmentActivity] (BiometricPrompt's requirement —
 * MainActivity and every companion activity extend it).
 */
@Composable
fun rememberBiometricPromptHelper(store: BiometricKeyStore): BiometricPromptHelper? {
    val activity = LocalContext.current as? FragmentActivity ?: return null
    return remember(activity, store) { BiometricPromptHelper(activity, store) }
}

/**
 * Renders nothing. While [request] is non-null it launches the matching
 * system fingerprint prompt exactly once:
 *  - [BiometricPromptRequest.Unlock] unwraps the remembered key and returns
 *    the raw bytes (or null on cancel/stale) via [onUnlock], with an
 *    optional failure reason (v0.5.1);
 *  - [BiometricPromptRequest.Remember] loads the session key with the
 *    SUSPEND loader [loadRememberKey] (deep-scan F1: the key export is an
 *    async DB call — the loader must be awaited INSIDE this effect, never
 *    pre-loaded into composition state, or the prompt would race an
 *    always-null buffer and fail instantly), wraps it and reports whether
 *    the wrap persisted via [onRememberDone]. The helper wipes the buffer
 *    itself on every path.
 *
 * ViewModels own the request state; this host exists because BiometricPrompt
 * is an imperative Android API that needs an Activity, not because any state
 * lives here.
 */
@Composable
fun BiometricPromptHost(
    helper: BiometricPromptHelper?,
    request: BiometricPromptRequest?,
    loadRememberKey: (suspend () -> ByteArray?)?,
    onUnlock: (ByteArray?, String?) -> Unit,
    onRememberDone: (Boolean, String?) -> Unit,
) {
    LaunchedEffect(request, helper) {
        val prompt = helper ?: return@LaunchedEffect
        when (val current = request) {
            null -> Unit
            is BiometricPromptRequest.Unlock ->
                prompt.unlockKey(current.contactKey) { keyBytes, reason ->
                    onUnlock(keyBytes, reason)
                }
            is BiometricPromptRequest.Remember -> {
                val plain = loadRememberKey?.invoke()
                if (plain == null) {
                    onRememberDone(false, null)
                } else {
                    prompt.rememberKey(current.contactKey, plain) { done, reason ->
                        onRememberDone(done, reason)
                    }
                }
            }
        }
    }
}
