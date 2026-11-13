package com.turnypt.keyboard

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import com.turnypt.data.repository.ContactRepository
import com.turnypt.data.repository.MessageCipher
import com.turnypt.keyboard.compose.KeyboardLifecycleOwner
import com.turnypt.keyboard.controller.ImeController
import com.turnypt.keyboard.input.InputConnectionTextField
import com.turnypt.keyboard.input.SensitiveFieldPolicy
import com.turnypt.keyboard.input.TextFieldAccess
import com.turnypt.keyboard.ui.BaseKeyboard
import com.turnypt.keyboard.ui.CryptoToolbar
import com.turnypt.keyboard.ui.DecryptPreviewPanel
import com.turnypt.keyboard.ui.Key
import com.turnypt.keyboard.ui.KeyAction
import com.turnypt.keyboard.ui.KeyboardTheme
import com.turnypt.keyboard.ui.PassphraseDialog
import com.turnypt.keyboard.ui.SymbolsKeyboard
import com.turnypt.security.clipboard.ClipboardAccess
import javax.inject.Inject

/**
 * Turnypt input method service.
 *
 * Lifecycle of the Compose content hosted inside the IME is driven by
 * [KeyboardLifecycleOwner]; the service itself follows the standard
 * InputMethodService contract.
 *
 * Security invariants:
 *  - no field content is ever logged;
 *  - the clipboard is only read after an explicit user action
 *    (ImeController.requestDecryptClipboard);
 *  - encryption features are disabled on password/sensitive fields
 *    (see [SensitiveFieldPolicy]) — the crypto toolbar is not rendered;
 *  - this module contains NO cryptographic implementation; all crypto work
 *    flows through the MessageCipher facade from :data:repository.
 */
@AndroidEntryPoint
class TurnyptKeyboardService : InputMethodService() {

    @Inject lateinit var messageCipher: MessageCipher
    @Inject lateinit var contactRepository: ContactRepository
    @Inject lateinit var clipboardAccess: ClipboardAccess

    private val keyboardLifecycleOwner = KeyboardLifecycleOwner()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var controller: ImeController

    private var shiftOn by mutableStateOf(false)
    private var symbolsMode by mutableStateOf(false)
    private var sensitiveField by mutableStateOf(false)

    private lateinit var textField: TextFieldAccess

    override fun onCreate() {
        super.onCreate()
        keyboardLifecycleOwner.performRestore(null)
        keyboardLifecycleOwner.dispatchEvent(Lifecycle.Event.ON_CREATE)
        textField = InputConnectionTextField(
            connectionProvider = { currentInputConnection },
            editorInfoProvider = { currentInputEditorInfo },
        )
        controller = ImeController(
            messageCipher = messageCipher,
            contactsRepository = contactRepository,
            clipboard = clipboardAccess,
            textField = textField,
            scope = serviceScope,
        )
    }

    override fun onCreateInputView(): View {
        val view = ComposeView(this)
        // The ViewTree owner extensions are the documented way to prepare a
        // ComposeView outside an Activity (an IME has no activity lifecycle).
        view.setViewTreeLifecycleOwner(keyboardLifecycleOwner)
        view.setViewTreeViewModelStoreOwner(keyboardLifecycleOwner)
        view.setViewTreeSavedStateRegistryOwner(keyboardLifecycleOwner)
        view.setContent {
            KeyboardTheme {
                ImeContent()
            }
        }
        return view
    }

    override fun onStartInputView(editorInfo: EditorInfo, restarting: Boolean) {
        super.onStartInputView(editorInfo, restarting)
        val sensitive = SensitiveFieldPolicy.isSensitive(editorInfo)
        sensitiveField = sensitive
        controller.onInputChanged(sensitive)
        // Never carry composition state across fields.
        shiftOn = false
        symbolsMode = false
    }

    override fun onDestroy() {
        serviceScope.cancel()
        keyboardLifecycleOwner.dispatchEvent(Lifecycle.Event.ON_DESTROY)
        keyboardLifecycleOwner.clearViewModels()
        super.onDestroy()
    }

    /** Fullscreen extract mode is disabled; Turnypt always renders its own view. */
    override fun onEvaluateFullscreenMode(): Boolean = false

    @Composable
    private fun ImeContent() {
        val uiState by controller.uiState.collectAsState()
        val notice by controller.notice.collectAsState()
        val prompt by controller.passphrasePrompt.collectAsState()
        val preview by controller.preview.collectAsState()

        Column(modifier = Modifier.fillMaxWidth()) {
            if (!sensitiveField) {
                CryptoToolbar(
                    state = uiState,
                    notice = notice,
                    onSelectContact = { id -> controller.selectContact(com.turnypt.core.common.ContactId(id)) },
                    onEncrypt = controller::requestEncryptDraft,
                    onDecrypt = controller::requestDecryptClipboard,
                    onLock = controller::lockActiveContact,
                )
            }

            preview?.let { current ->
                DecryptPreviewPanel(
                    preview = current,
                    onInsert = controller::insertPreviewedText,
                    onDismiss = controller::dismissPreview,
                )
            }

            if (symbolsMode) {
                SymbolsKeyboard(
                    onText = { textField.commitText(it) },
                    onBackspace = { textField.deleteBackward() },
                    onSpace = { textField.commitText(" ") },
                    onEnter = { sendDefaultEditorAction(true) },
                    onBackToLetters = { symbolsMode = false },
                )
            } else {
                BaseKeyboard(
                    shiftOn = shiftOn,
                    onKey = ::handleKey,
                )
            }
        }

        prompt?.let { current ->
            PassphraseDialog(
                prompt = current,
                busy = uiState.busy,
                onSubmit = controller::submitPassphrase,
                onDismiss = controller::cancelPassphrasePrompt,
            )
        }
    }

    private fun handleKey(key: Key) {
        when (key.action) {
            KeyAction.Text -> {
                val text = if (shiftOn) key.text.uppercase() else key.text
                textField.commitText(text)
                shiftOn = false
            }
            KeyAction.Shift -> shiftOn = !shiftOn
            KeyAction.Backspace -> textField.deleteBackward()
            KeyAction.Space -> textField.commitText(" ")
            KeyAction.Enter -> sendDefaultEditorAction(true)
            KeyAction.SymbolToggle -> symbolsMode = !symbolsMode
        }
    }
}
