package com.turnypt.app.companion

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import com.turnypt.app.companion.CompanionUiState.Phase
import com.turnypt.app.security.BiometricKeyStore
import com.turnypt.app.ui.theme.TurnyptTheme
import javax.inject.Inject

/**
 * Extra used by Turnypt's own Home screen (and the floating assistant,
 * v0.3.0) to route a clipboard fallback through the same processing
 * activities (spec 003, User Story 3). When present, the activity reads its
 * text from the clipboard (explicit, user-initiated launch, via the
 * ClipboardAccess chokepoint) instead of the PROCESS_TEXT extra, and hides
 * the "Replace in field" affordance.
 */
const val EXTRA_FROM_CLIPBOARD = "com.turnypt.app.extra.FROM_CLIPBOARD"

/**
 * Shared skeleton for the companion processing activities.
 *
 * Hardening (FR-004): every input path funnels through
 * [ProcessTextContract] before any UI or crypto work happens; junk input
 * produces a graceful notice, never a crash.
 *
 * CLIPBOARD FOCUS CONTRACT (spec 004, Samsung device fix): on Android 10+
 * the system only serves the clipboard to the app that owns the *focused*
 * window — and a freshly created activity does not have focus yet while
 * [onCreate] runs. Reading the clipboard there returned null and produced
 * the false "clipboard is empty" notice on the Galaxy A54. Clipboard
 * launches are therefore deferred until [onWindowFocusChanged] reports that
 * this activity's window actually gained focus.
 *
 * v0.3.0: the base extends [FragmentActivity] (still a ComponentActivity)
 * because the system BiometricPrompt requires a FragmentActivity host —
 * the fingerprint unlock and remember flows run here too.
 */
abstract class BaseCompanionActivity : FragmentActivity() {

    protected val viewModel: CompanionViewModel by viewModels()

    /** v0.3.0: fingerprint-gated key store, injected for the prompt flows. */
    @Inject
    lateinit var biometrics: BiometricKeyStore

    /** The workflow this entry point hosts — drives notice headers (finding 4). */
    protected abstract val companionMode: CompanionMode

    /** True while a clipboard launch still awaits its first focused read. */
    private var pendingClipboardDispatch = false

    /** True once the deferred clipboard read has been performed. */
    private var clipboardDispatched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Deep-scan finding 3: these activities render decrypted plaintext,
        // so screenshots and screen recording must capture nothing. (The
        // manifest's noHistory + excludeFromRecents only protect the
        // recents preview.)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        if (savedInstanceState == null) {
            if (isClipboardLaunch(intent)) {
                // Do NOT read the clipboard here: the window has no focus
                // during onCreate and Android 10+ denies the read. Defer to
                // the first onWindowFocusChanged(true).
                pendingClipboardDispatch = true
            } else {
                dispatchIntent(intent)
            }
        }
        setContent {
            TurnyptTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                CompanionSheet(
                    state = state,
                    onContactSelected = viewModel::selectContact,
                    onEncrypt = viewModel::encryptNow,
                    onUnlockEncrypt = viewModel::unlockAndEncrypt,
                    onUnlockDecrypt = viewModel::unlockAndDecrypt,
                    onCopy = viewModel::copyResult,
                    onInsert = ::finishWithResult,
                    onClose = { finish() },
                    onRetryClipboard = if (isClipboardLaunch(intent)) {
                        { retryClipboardDispatch() }
                    } else {
                        null
                    },
                    biometrics = biometrics,
                    onRequestBiometricUnlock = viewModel::requestBiometricUnlock,
                    onBiometricUnlock = viewModel::onBiometricUnlockResult,
                    loadRememberKey = viewModel::consumeRememberKey,
                    onRememberDone = viewModel::onRememberDone,
                    onCopyPassphrase = viewModel::copyPassphrase,
                )
            }
        }
    }

    /**
     * The first moment the clipboard may legitimately be read on Android
     * 10+: this activity's window now owns focus.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val awaitingRead = pendingClipboardDispatch ||
            // Robustness for the tiny window where a configuration change
            // recreated the activity between launch and first focus.
            (viewModel.state.value.phase == Phase.Idle && isClipboardLaunch(intent))
        if (awaitingRead && !clipboardDispatched) {
            clipboardDispatched = true
            dispatchIntent(intent)
        }
    }

    /** Explicit "Try again" from the notice card: the window is focused. */
    private fun retryClipboardDispatch() {
        if (hasWindowFocus()) {
            clipboardDispatched = true
            viewModel.retryClipboard()
        }
    }

    /** True when this launch came from the Home-screen clipboard buttons. */
    protected fun isClipboardLaunch(intent: Intent): Boolean =
        intent.getBooleanExtra(EXTRA_FROM_CLIPBOARD, false)

    protected abstract fun dispatchIntent(intent: Intent)

    /** Returns the result text to the calling field (PROCESS_TEXT contract). */
    protected fun finishWithResult() {
        val result = viewModel.state.value.resultText
        if (result != null) {
            setResult(
                Activity.RESULT_OK,
                Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, result),
            )
        }
        finish()
    }

    /** Graceful dead-end notice for unusable input (FR-004). */
    protected fun showNotice(notice: UiNotice, retryable: Boolean = false) {
        viewModel.showNotice(companionMode, notice, retryable)
    }
}
