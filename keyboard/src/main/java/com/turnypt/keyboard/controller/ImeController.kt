package com.turnypt.keyboard.controller

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.turnypt.core.common.ContactId
import com.turnypt.core.common.SessionId
import com.turnypt.core.crypto.CryptoError
import com.turnypt.core.sessions.wipe
import com.turnypt.data.repository.Contact
import com.turnypt.data.repository.ContactRepository
import com.turnypt.data.repository.DecryptOutcome
import com.turnypt.data.repository.EncryptOutcome
import com.turnypt.data.repository.MessageCipher
import com.turnypt.data.repository.UnlockOutcome
import com.turnypt.keyboard.input.DraftRead
import com.turnypt.keyboard.input.TextFieldAccess
import com.turnypt.security.clipboard.ClipboardAccess

/**
 * UI-state controller for the input method.
 *
 * Pure state orchestration: every cryptographic operation goes through
 * [MessageCipher]; this class never touches key material. Designed to be
 * driven from the IME's main-thread scope and read from Compose via
 * [uiState]/[passphrasePrompt]/[preview]/[notice].
 */
class ImeController(
    private val messageCipher: MessageCipher,
    private val contactsRepository: ContactRepository,
    private val clipboard: ClipboardAccess,
    private val textField: TextFieldAccess,
    private val scope: kotlinx.coroutines.CoroutineScope,
    /** Tests disable the auto-clear to inspect transient notices deterministically. */
    private val autoClearNotices: Boolean = true,
) {

    enum class PromptReason { SEND, RECEIVE }

    data class PassphrasePrompt(
        val contactId: ContactId,
        val contactName: String,
        val reason: PromptReason,
        /**
         * v0.4.0: non-null when the prompt serves a SUPERSEDED key era
         * (old-era envelope) — submitPassphrase then unlocks THAT era via
         * [MessageCipher.unlockEra] instead of the contact's current one.
         */
        val eraSessionId: SessionId? = null,
    )

    data class DecryptedPreview(
        val contactId: ContactId,
        val contactName: String,
        val plaintext: String,
    )

    data class UiState(
        val contacts: List<Contact> = emptyList(),
        val activeContactId: ContactId? = null,
        val activeContactName: String? = null,
        val unlockedContactIds: Set<ContactId> = emptySet(),
        val sensitiveField: Boolean = false,
        val busy: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _passphrasePrompt = MutableStateFlow<PassphrasePrompt?>(null)
    val passphrasePrompt: StateFlow<PassphrasePrompt?> = _passphrasePrompt.asStateFlow()

    private val _preview = MutableStateFlow<DecryptedPreview?>(null)
    val preview: StateFlow<DecryptedPreview?> = _preview.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    init {
        scope.launch {
            contactsRepository.observeContacts().collect { list ->
                _uiState.update { state ->
                    val active = state.activeContactId
                        ?.takeIf { id -> list.any { it.id == id } }
                        ?: list.firstOrNull()?.id
                    state.copy(
                        contacts = list,
                        activeContactId = active,
                        activeContactName = list.firstOrNull { it.id == active }?.displayName,
                    )
                }
                refreshUnlocked()
            }
        }
    }

    /** Called on every input-field change; drops secrets when a sensitive field gains focus. */
    fun onInputChanged(sensitive: Boolean) {
        _uiState.update { it.copy(sensitiveField = sensitive) }
        if (sensitive) {
            _preview.value = null
            _passphrasePrompt.value = null
        }
    }

    fun selectContact(contactId: ContactId) {
        _uiState.update { state ->
            state.copy(
                activeContactId = contactId,
                activeContactName = state.contacts.firstOrNull { it.id == contactId }?.displayName,
            )
        }
    }

    /** "Encrypt draft" button: draft -> envelope -> replace the field content. */
    fun requestEncryptDraft() {
        val state = _uiState.value
        // Deep-scan finding 26: re-check the field LIVE at action time in
        // addition to the onStartInputView-derived state — defense in depth
        // against an editor that changed characteristics without a restart.
        if (state.sensitiveField || textField.isSensitiveField || state.busy) return
        val contactId = state.activeContactId
            ?: run { notify("No contact yet — add one in the Turnypt app"); return }
        scope.launch {
            setBusy(true)
            try {
                // Deep-scan finding 22: an unreadable draft is NOT an empty
                // draft — distinct outcomes, distinct notices.
                when (val draft = textField.readDraft()) {
                    DraftRead.Unreadable -> notify("Could not read this field — copy the text and use the app's Encrypt clipboard")
                    DraftRead.TooLong -> notify("The draft is longer than 16 KiB — split it before encrypting")
                    is DraftRead.Draft -> when (
                        val outcome = messageCipher.encryptDraft(contactId, draft.text)
                    ) {
                        is EncryptOutcome.Encrypted -> {
                            when {
                                textField.replaceAll(outcome.envelope) ->
                                    notify("Encrypted for ${state.activeContactName}")
                                clipboard.setText(outcome.envelope) ->
                                    notify("Field not editable — envelope copied to clipboard")
                                else ->
                                    notify("Could not replace the field text")
                            }
                        }
                        EncryptOutcome.NeedsUnlock ->
                            _passphrasePrompt.value = PassphrasePrompt(
                                contactId = contactId,
                                contactName = state.activeContactName ?: "?",
                                reason = PromptReason.SEND,
                            )
                        EncryptOutcome.NoSession -> notify("No key for this contact — open the Turnypt app")
                        EncryptOutcome.EmptyDraft -> notify("Nothing to encrypt — type a message first")
                        is EncryptOutcome.Failed -> notify(describe(outcome.error))
                    }
                }
            } finally {
                setBusy(false)
            }
        }
    }

    /** "Decrypt clipboard" button: explicit clipboard read only. */
    fun requestDecryptClipboard() {
        val state = _uiState.value
        if (state.sensitiveField || textField.isSensitiveField || state.busy) return
        scope.launch {
            setBusy(true)
            try {
                val text = clipboard.currentText()
                if (text == null) {
                    notify("Clipboard is empty")
                    return@launch
                }
                when (val outcome = messageCipher.decryptEnvelope(text)) {
                    is DecryptOutcome.Decrypted ->
                        _preview.value = DecryptedPreview(
                            contactId = outcome.contactId,
                            contactName = outcome.contactName,
                            plaintext = outcome.plaintext,
                        )
                    is DecryptOutcome.NeedsUnlock ->
                        _passphrasePrompt.value = PassphrasePrompt(
                            contactId = outcome.contactId,
                            contactName = outcome.contactName,
                            reason = PromptReason.RECEIVE,
                        )
                    DecryptOutcome.NotAnEnvelope -> notify("Clipboard has no CK1 message")
                    DecryptOutcome.UnknownKey -> notify("Unknown key — no matching contact here")
                    is DecryptOutcome.NeedsEraUnlock ->
                        _passphrasePrompt.value = PassphrasePrompt(
                            contactId = outcome.contactId,
                            contactName = outcome.contactName,
                            reason = PromptReason.RECEIVE,
                            eraSessionId = outcome.eraSessionId,
                        )
                    is DecryptOutcome.Failed -> notify(describe(outcome.error))
                }
            } finally {
                setBusy(false)
            }
        }
    }

    /** Submits the passphrase entered in the IME dialog and retries the pending action. */
    fun submitPassphrase(passphrase: CharArray) {
        val prompt = _passphrasePrompt.value ?: return
        scope.launch {
            setBusy(true)
            var retry: PromptReason? = null
            try {
                // v0.4.0: a superseded-era prompt unlocks THAT era — the
                // current one is never touched by an old-era envelope.
                val eraId = prompt.eraSessionId
                val outcome = if (eraId != null) {
                    messageCipher.unlockEra(prompt.contactId, eraId, passphrase)
                } else {
                    messageCipher.unlockContact(prompt.contactId, passphrase)
                }
                passphrase.wipe()
                when (outcome) {
                    UnlockOutcome.Unlocked, UnlockOutcome.AlreadyUnlocked -> {
                        _passphrasePrompt.value = null
                        refreshUnlocked()
                        notify(
                            if (eraId != null) {
                                "Older key era unlocked for ${prompt.contactName}"
                            } else {
                                "Unlocked ${prompt.contactName}"
                            },
                        )
                        retry = prompt.reason
                    }
                    UnlockOutcome.WrongPassphrase -> notify("Wrong passphrase")
                    UnlockOutcome.NoSession -> notify("No key for this contact — open the app")
                    is UnlockOutcome.Rejected -> notify(outcome.reason)
                    is UnlockOutcome.Failed -> notify(outcome.reason)
                    // v0.5.1: the era's Argon2id profile cannot fit this
                    // device's heap — honest message, no OOM crash.
                    is UnlockOutcome.MemoryHungry -> notify(
                        "This key era needs ${outcome.requiredBytes / (1024L * 1024L)} MiB " +
                            "of memory for Argon2id — this device cannot provide it",
                    )
                }
            } finally {
                // Clear busy BEFORE retrying: the retry handlers are guarded
                // by the busy flag and would otherwise be no-ops.
                setBusy(false)
            }
            when (retry) {
                PromptReason.SEND -> requestEncryptDraft()
                PromptReason.RECEIVE -> requestDecryptClipboard()
                null -> Unit
            }
        }
    }

    fun cancelPassphrasePrompt() {
        _passphrasePrompt.value = null
    }

    fun insertPreviewedText() {
        val current = _preview.value ?: return
        if (textField.isSensitiveField) {
            // Never insert decrypted plaintext into a credential field.
            notify("This is a password field — insertion blocked")
            return
        }
        if (textField.commitText(current.plaintext)) {
            _preview.value = null
        } else {
            notify("Could not insert into this field")
        }
    }

    fun dismissPreview() {
        _preview.value = null
    }

    fun lockActiveContact() {
        val contactId = _uiState.value.activeContactId ?: return
        scope.launch {
            messageCipher.lockContact(contactId)
            refreshUnlocked()
            // Deep-scan finding 24: locking must also drop the decrypted
            // preview — the key that produced it is gone.
            _preview.value = null
            notify("Locked")
        }
    }

    private suspend fun refreshUnlocked() {
        val unlocked = _uiState.value.contacts
            .map { it.id }
            .filter { messageCipher.isUnlocked(it) }
            .toSet()
        _uiState.update { it.copy(unlockedContactIds = unlocked) }
    }

    private fun setBusy(busy: Boolean) {
        _uiState.update { it.copy(busy = busy) }
    }

    private fun describe(error: CryptoError): String = when (error) {
        CryptoError.AuthenticationFailed -> "Wrong passphrase or tampered message"
        CryptoError.InputTooLarge -> "Message too long (max 16 KiB)"
        CryptoError.MessageTooLarge -> "Message too large"
        is CryptoError.UnsupportedVersion -> "Unsupported protocol version"
        is CryptoError.UnsupportedAlgorithm -> "Unsupported cipher"
        is CryptoError.UnsupportedKdf -> "Unsupported key derivation"
        is CryptoError.UnsupportedMode -> "Unsupported encryption mode"
        CryptoError.MalformedMessage -> "Malformed message"
        else -> error.message
    }

    private fun notify(message: String) {
        _notice.value = message
        if (!autoClearNotices) return
        scope.launch {
            delay(NOTICE_MILLIS)
            if (_notice.value == message) _notice.value = null
        }
    }

    private companion object {
        const val NOTICE_MILLIS = 4_000L
    }
}
