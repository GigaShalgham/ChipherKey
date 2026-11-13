package com.turnypt.app.companion

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.turnypt.app.R
import com.turnypt.app.security.BiometricKeyStore
import com.turnypt.app.security.BiometricPromptRequest
import com.turnypt.core.common.ContactId
import com.turnypt.core.common.SessionId
import com.turnypt.core.crypto.CryptoError
import com.turnypt.core.messages.Ck1Limits
import com.turnypt.core.sessions.wipe
import com.turnypt.data.preferences.SettingsRepository
import com.turnypt.data.repository.Contact
import com.turnypt.data.repository.ContactRepository
import com.turnypt.data.repository.DecryptOutcome
import com.turnypt.data.repository.EncryptOutcome
import com.turnypt.data.repository.MessageCipher
import com.turnypt.data.repository.SessionKeyRepository
import com.turnypt.data.repository.UnlockOutcome
import com.turnypt.app.clipboard.ClipboardHistoryRecorder
import com.turnypt.security.clipboard.ClipboardAccess
import com.turnypt.security.clipboard.ClipboardRead

/**
 * Mode of the companion flow currently hosted by an activity.
 */
enum class CompanionMode { ENCRYPT, DECRYPT }

/**
 * Where the text came from — drives result affordances and labels.
 */
enum class TextSource { SELECTION, SHARE, CLIPBOARD }

/**
 * State machine behind all three companion entry points (spec 003):
 * the PROCESS_TEXT encrypt activity, the PROCESS_TEXT decrypt activity,
 * the ACTION_SEND decrypt activity — plus the clipboard entry used from
 * the main app's Home screen and the floating assistant (v0.3.0).
 *
 * Everything cryptographic is delegated to the existing [MessageCipher]
 * facade; this class owns ONLY flow state and UX outcomes.
 */
data class CompanionUiState(
    val mode: CompanionMode = CompanionMode.ENCRYPT,
    val source: TextSource = TextSource.SELECTION,
    val phase: Phase = Phase.Idle,
    /** Sanitized input text (draft or envelope candidate). */
    val incoming: String? = null,
    /** True when the calling context was read-only (no insert offer). */
    val readOnly: Boolean = false,
    val contacts: List<Contact> = emptyList(),
    val unlocked: Set<ContactId> = emptySet(),
    val selectedContactId: ContactId? = null,
    /** Contact resolved from a decrypt outcome (name display + unlock). */
    val pendingContactId: ContactId? = null,
    val pendingContactName: String? = null,
    /**
     * v0.4.0: non-null while the pending passphrase unlock serves a
     * SUPERSEDED key era (old-era envelope). Biometric offers stay off —
     * a fingerprint wrap is bound to the contact's latest era only.
     */
    val pendingEraSessionId: SessionId? = null,
    /** Final output: envelope (encrypt) or plaintext (decrypt). */
    val resultText: String? = null,
    val resultContactName: String? = null,
    val copied: Boolean = false,
    /** Typed notice for edge outcomes; null = none (v0.3.0: resource id). */
    val notice: UiNotice? = null,
    /** True when the notice offers a clipboard "Try again" (spec 004). */
    val retryable: Boolean = false,
    val busy: Boolean = false,
    /** v0.3.0: a remembered fingerprint wrap exists for the pending contact. */
    val biometricOffered: Boolean = false,
    /** v0.3.0: non-null while the system biometric prompt should be shown. */
    val biometricPrompt: BiometricPromptRequest? = null,
    /**
     * v0.5.2: the passphrase field should offer the strong-passphrase
     * suggestion — true only when the era being unlocked was created
     * LOCALLY (no friend invite entered) and never unlocked on this device,
     * i.e. THIS side is choosing the shared passphrase right now.
     */
    val suggestPassphrase: Boolean = false,
) {
    enum class Phase { Idle, Prepare, NeedsUnlock, Result, Notice }

    val selectedContact: Contact?
        get() = contacts.firstOrNull { it.id == selectedContactId }

    val selectedContactUnlocked: Boolean
        get() = selectedContactId in unlocked

    /**
     * v0.3.0: the result can go back into the calling field — only for
     * PROCESS_TEXT launches into an editable field (both directions now:
     * an inserted envelope on encrypt, an inserted plaintext on decrypt).
     */
    val insertable: Boolean
        get() = source == TextSource.SELECTION && !readOnly
}

@HiltViewModel
class CompanionViewModel @Inject constructor(
    private val messageCipher: MessageCipher,
    private val contactsRepository: ContactRepository,
    private val sessionKeys: SessionKeyRepository,
    private val settings: SettingsRepository,
    private val clipboard: ClipboardAccess,
    private val clipboardRecorder: ClipboardHistoryRecorder,
    private val biometrics: BiometricKeyStore,
) : ViewModel() {

    private val _state = MutableStateFlow(CompanionUiState())
    val state = _state.asStateFlow()

    /** True once a flow has begun (idempotence across activity recreation). */
    private val begun = MutableStateFlow(false)

    /** v0.3.0: contact unlocked most recently (biometric remember target). */
    private var lastUnlockedContactId: ContactId? = null

    /** v0.3.0: what to run after a successful biometric unlock. */
    private var afterUnlock: (suspend () -> Unit)? = null

    /**
     * v0.4.2 (deep-scan N2 guard): the "contactId:eraId" key the outstanding
     * fingerprint prompt unwraps — checked again when the sensor confirms, so
     * a wrap of a ROTATED era can never be activated as the new era's key.
     */
    private var pendingPromptKey: String? = null

    init {
        // v0.4.2 (deep-scan N2): the sheet used to snapshot `unlocked` when
        // the flow began and keep it forever. A lockAll(), a session timeout
        // or a key-era rotation in another surface (split screen, the
        // floating assistant) then left it claiming the contact was open —
        // and offering a fingerprint wrap bound to the OLD era. Re-read the
        // live state on every session/era change instead.
        viewModelScope.launch {
            messageCipher.sessionChanges.collect { resyncSessionState() }
        }
    }

    /**
     * v0.4.2 (deep-scan N2): drops stale `unlocked` entries and recomputes
     * the fingerprint offer for the phase that is on screen.
     */
    private fun resyncSessionState() {
        launchSafely {
            val live = _state.value.contacts.map { it.id }
                .filter { messageCipher.isUnlocked(it) }
                .toSet()
            _state.value = _state.value.copy(unlocked = live)
            val phase = _state.value.phase
            if (phase == CompanionUiState.Phase.NeedsUnlock ||
                phase == CompanionUiState.Phase.Prepare
            ) {
                refreshBiometricOffer()
            }
        }
    }

    // ------------------------------------------------------------ entry

    /**
     * Encrypt flow: show prepare screen (sticky contact preselected,
     * passphrase only if the session is locked).
     *
     * [readOnly] is true when the selection came from a non-editable view
     * (EXTRA_PROCESS_TEXT_READONLY): the sheet then hides "Replace in
     * field" because the calling app would ignore the result.
     */
    fun beginEncrypt(text: String, source: TextSource, readOnly: Boolean = false) {
        if (!begun.compareAndSet(false, true)) return
        startEncryptFlow(text, source, readOnly)
    }

    /** Decrypt flow: auto-resolve the contact from the envelope's keyId. */
    fun beginDecrypt(text: String, source: TextSource, readOnly: Boolean) {
        if (!begun.compareAndSet(false, true)) return
        startDecryptFlow(text, source, readOnly)
    }

    fun selectContact(id: ContactId) {
        _state.value = _state.value.copy(
            selectedContactId = id,
            notice = null,
        )
        // v0.5.2: the prepare card's passphrase field follows the selected
        // contact — recompute whether the suggestion is honest for it.
        refreshSuggestPassphrase()
    }

    fun dismissNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    /** Dead-end notice for unusable input (activity-level hardening). */
    fun showNotice(mode: CompanionMode, notice: UiNotice, retryable: Boolean = false) {
        if (!begun.compareAndSet(false, true)) return
        _state.value = CompanionUiState(
            mode = mode,
            source = _state.value.source,
            phase = CompanionUiState.Phase.Notice,
            notice = notice,
            retryable = retryable,
        )
    }

    // ------------------------------------------------------------ actions

    /** Encrypt with the selected contact (session already unlocked). */
    fun encryptNow() {
        val s = _state.value
        val id = s.selectedContactId ?: return
        val draft = s.incoming ?: return
        launchSafely {
            _state.value = _state.value.copy(busy = true, notice = null)
            when (val out = messageCipher.encryptDraft(id, draft)) {
                is EncryptOutcome.Encrypted -> {
                    settings.setLastContactId(id.value)
                    _state.value = _state.value.copy(
                        phase = CompanionUiState.Phase.Result,
                        resultText = out.envelope,
                        resultContactName = s.selectedContact?.displayName,
                        busy = false,
                        copied = false,
                    )
                }
                is EncryptOutcome.NeedsUnlock -> _state.value = _state.value.copy(
                    busy = false,
                    phase = CompanionUiState.Phase.NeedsUnlock,
                    pendingContactId = id,
                    pendingContactName = s.selectedContact?.displayName,
                ).also {
                    refreshBiometricOffer()
                    refreshSuggestPassphrase()
                }
                is EncryptOutcome.NoSession -> _state.value = _state.value.copy(
                    busy = false,
                    notice = UiNotice(R.string.notice_no_era),
                )
                is EncryptOutcome.EmptyDraft -> _state.value = _state.value.copy(
                    busy = false,
                    notice = UiNotice(R.string.notice_nothing_to_encrypt),
                )
                is EncryptOutcome.Failed -> _state.value = _state.value.copy(
                    busy = false,
                    notice = if (out.error == CryptoError.InputTooLarge) {
                        UiNotice(R.string.notice_input_too_long, "${Ck1Limits.MAX_PLAINTEXT_BYTES / 1024}")
                    } else {
                        UiNotice(R.string.notice_encrypt_failed, out.error.toString())
                    },
                )
            }
        }
    }

    /** Unlock (if needed) and encrypt in one step, from the prepare card. */
    fun unlockAndEncrypt(passphrase: CharArray) {
        val id = _state.value.selectedContactId ?: run {
            passphrase.wipe(); return
        }
        unlockThen(id, passphrase) { encryptNow() }
    }

    /** Unlock the contact resolved from a decrypt NeedsUnlock outcome.
     *
     * v0.4.0: when the envelope belongs to a superseded era, the unlock
     * goes through [MessageCipher.unlockEra] — the contact is NOT marked
     * unlocked for sending and no fingerprint remember offer is made.
     */
    fun unlockAndDecrypt(passphrase: CharArray) {
        val id = _state.value.pendingContactId ?: run {
            passphrase.wipe(); return
        }
        val envelope = _state.value.incoming ?: run {
            passphrase.wipe(); return
        }
        val eraSessionId = _state.value.pendingEraSessionId
        if (eraSessionId != null) {
            unlockEraThen(id, eraSessionId, passphrase) { decryptInternal(envelope) }
        } else {
            unlockThen(id, passphrase) { decryptInternal(envelope) }
        }
    }

    /** Copy the current result to the clipboard (explicit user action). */
    fun copyResult() {
        val result = _state.value.resultText ?: return
        val sensitive = _state.value.mode == CompanionMode.DECRYPT
        // Decrypted plaintext is marked sensitive (deep-scan finding 17):
        // Android 13+ then hides the clipboard preview. Envelopes are not
        // secret by definition.
        val copied = if (sensitive) clipboard.setSensitiveText(result) else clipboard.setText(result)
        _state.value = if (copied) {
            // v0.5.2: envelopes join the clipboard history (re-pasteable);
            // decrypted plaintext NEVER does.
            if (sensitive) {
                clipboardRecorder.onSensitiveCopied(result)
            } else {
                clipboardRecorder.onCopied(result)
            }
            _state.value.copy(copied = true)
        } else {
            _state.value.copy(notice = UiNotice(R.string.notice_clipboard_access_failed))
        }
    }

    /**
     * v0.5.2: copies a SUGGESTED PASSPHRASE — sensitive clipboard path
     * (hidden preview on Android 13+), never recorded in the history.
     */
    fun copyPassphrase(text: String): Boolean {
        val copied = clipboard.setSensitiveText(text)
        if (copied) {
            clipboardRecorder.onSensitiveCopied(text)
        }
        return copied
    }

    // ------------------------------------------------------------ biometric

    /**
     * v0.5.2: recomputes `suggestPassphrase` for the passphrase field that
     * is currently on screen (prepare card = selected contact; unlock card
     * = pending contact, optionally a superseded era).
     */
    private fun refreshSuggestPassphrase() {
        launchSafely {
            val s = _state.value
            val target: Pair<ContactId, SessionId?>? = when (s.phase) {
                CompanionUiState.Phase.NeedsUnlock -> {
                    val contactId = s.pendingContactId ?: s.selectedContactId
                    if (contactId != null) contactId to s.pendingEraSessionId else null
                }
                CompanionUiState.Phase.Prepare ->
                    if (s.selectedContactId != null && !s.selectedContactUnlocked) {
                        s.selectedContactId to null
                    } else {
                        null
                    }
                else -> null
            }
            if (target == null) {
                if (s.suggestPassphrase) {
                    _state.value = s.copy(suggestPassphrase = false)
                }
                return@launchSafely
            }
            val (id, era) = target
            _state.value = _state.value.copy(
                suggestPassphrase = sessionKeys.awaitingFirstPassphrase(id, era),
            )
        }
    }

    /**
     * v0.3.0: recompute whether the unlock card should offer the
     * fingerprint button (feature enabled + device capable + a wrap
     * exists for the pending contact's current era).
     */
    fun refreshBiometricOffer() {
        launchSafely {
            val enabled = settings.settings.first().biometricUnlockEnabled
            if (!enabled || !biometrics.isDeviceCapable()) {
                _state.value = _state.value.copy(biometricOffered = false)
                return@launchSafely
            }
            val contactKey = currentBiometricContactKey() ?: run {
                _state.value = _state.value.copy(biometricOffered = false)
                return@launchSafely
            }
            _state.value = _state.value.copy(
                biometricOffered = biometrics.hasWrapped(contactKey),
            )
        }
    }

    /** v0.3.0: "Use fingerprint" on the unlock card — show the system prompt. */
    fun requestBiometricUnlock() {
        launchSafely {
            val contactKey = currentBiometricContactKey() ?: return@launchSafely
            if (!biometrics.hasWrapped(contactKey)) {
                // v0.4.2 (deep-scan N2): the visible offer was computed for
                // an older era — retract it instead of dead-tapping silently.
                _state.value = _state.value.copy(biometricOffered = false)
                return@launchSafely
            }
            pendingPromptKey = contactKey
            _state.value = _state.value.copy(
                biometricPrompt = BiometricPromptRequest.Unlock(contactKey),
            )
        }
    }

    /**
     * v0.3.0: outcome of the system prompt. Null = canceled or failed —
     * the passphrase path remains. Non-null = unlock the era with the
     * remembered key and continue the interrupted flow.
     *
     * v0.5.1: [reason] carries the system's own localized message for
     * non-cancel failures (sensor busy, enrolment gone, …) so the notice
     * says what actually happened instead of a blanket "did not complete".
     */
    fun onBiometricUnlockResult(keyBytes: ByteArray?, reason: String? = null) {
        _state.value = _state.value.copy(biometricPrompt = null)
        if (keyBytes == null) {
            _state.value = _state.value.copy(
                notice = reason?.let {
                    UiNotice(R.string.notice_biometric_failed_reason, it)
                } ?: UiNotice(R.string.notice_biometric_failed),
            )
            return
        }
        val contactId = _state.value.pendingContactId ?: _state.value.selectedContactId
        if (contactId == null) {
            keyBytes.fill(0)
            return
        }
        launchSafely {
            // v0.4.2 (deep-scan N2 guard): the era may have rotated between
            // showing the prompt and touching the sensor — the unwrapped
            // bytes then belong to the OLD era and must never activate as
            // the new era's session key. Discard the wrap, say so honestly.
            val requestedKey = pendingPromptKey
            pendingPromptKey = null
            val currentKey = biometricContactKeyFor(contactId)
            if (requestedKey != null && currentKey != null && requestedKey != currentKey) {
                keyBytes.fill(0)
                biometrics.clear(requestedKey)
                afterUnlock = null
                _state.value = _state.value.copy(
                    busy = false,
                    biometricOffered = false,
                    phase = CompanionUiState.Phase.NeedsUnlock,
                    notice = UiNotice(R.string.notice_biometric_stale),
                )
                return@launchSafely
            }
            val contactKey = biometricContactKeyFor(contactId)
            val outcome = messageCipher.unlockContactWithKey(contactId, keyBytes)
            when (outcome) {
                UnlockOutcome.Unlocked, UnlockOutcome.AlreadyUnlocked -> {
                    _state.value = _state.value.copy(
                        unlocked = _state.value.unlocked + contactId,
                        notice = null,
                    )
                    afterUnlock?.invoke()
                }
                UnlockOutcome.NoSession -> {
                    _state.value = _state.value.copy(
                        notice = UiNotice(R.string.notice_no_era),
                    )
                }
                else -> {
                    // The wrap opened but the era no longer matches (rotated
                    // since remember) — discard the stale wrap so it is never
                    // offered again, and fall back to the passphrase.
                    contactKey?.let { biometrics.clear(it) }
                    afterUnlock = null
                    _state.value = _state.value.copy(
                        notice = UiNotice(R.string.notice_biometric_stale),
                        phase = CompanionUiState.Phase.NeedsUnlock,
                        busy = false,
                    )
                }
            }
        }
    }

    /**
     * v0.3.0: after a passphrase unlock, offer to remember the key behind
     * the fingerprint.
     *
     * v0.5.1 (reported "fingerprint didn't work anywhere"): the Settings
     * toggle no longer HIDES this offer. The system prompt itself — titled
     * "Remember this key", dismissed with "Not now" — is the consent; on
     * success the toggle is persisted ON so Settings reflects reality.
     * Turning the toggle OFF still wipes every wrap (unchanged semantics).
     */
    fun offerRemember(contactId: ContactId) {
        launchSafely {
            if (!biometrics.isDeviceCapable()) return@launchSafely
            val contactKey = biometricContactKeyFor(contactId) ?: return@launchSafely
            if (!biometrics.hasWrapped(contactKey)) {
                _state.value = _state.value.copy(
                    biometricPrompt = BiometricPromptRequest.Remember(contactKey),
                )
            }
        }
    }

    /**
     * v0.3.0: the key bytes the Remember prompt should wrap — the active
     * session key of [lastUnlockedContactId], exported once for the prompt.
     */
    suspend fun consumeRememberKey(): ByteArray? {
        if (_state.value.biometricPrompt !is BiometricPromptRequest.Remember) return null
        val id = lastUnlockedContactId ?: return null
        return messageCipher.exportSessionKey(id)
    }

    /**
     * v0.3.0: prompt finished — [success] says whether the wrap persisted.
     *
     * v0.5.1: a persisted wrap turns the Settings toggle ON (the user just
     * authenticated "Remember this key" — that IS the opt-in); a non-cancel
     * failure surfaces the system's reason instead of failing silently.
     */
    fun onRememberDone(success: Boolean, reason: String? = null) {
        _state.value = _state.value.copy(
            biometricPrompt = null,
            biometricOffered = _state.value.biometricOffered || success,
        )
        if (success) {
            launchSafely { settings.setBiometricUnlockEnabled(true) }
        } else if (reason != null) {
            _state.value = _state.value.copy(
                notice = UiNotice(R.string.notice_biometric_failed_reason, reason),
            )
        }
    }

    /** The pending-or-selected contact's biometric key, or null. */
    private suspend fun currentBiometricContactKey(): String? {
        val id = _state.value.pendingContactId ?: _state.value.selectedContactId
            ?: return null
        return biometricContactKeyFor(id)
    }

    /** `"contactId:eraId"` — the AAD and storage key for a remembered wrap. */
    private suspend fun biometricContactKeyFor(contactId: ContactId): String? {
        val eraId = messageCipher.currentEraId(contactId)?.value ?: return null
        return "${contactId.value}:$eraId"
    }

    // ------------------------------------------------------------ clipboard

    /**
     * Clipboard entry point (spec 004): reads the current clip through the
     * ClipboardAccess chokepoint and starts the flow for [mode].
     *
     * MUST be called only while the hosting activity's window has focus
     * (Android 10+ clipboard focus contract — see BaseCompanionActivity).
     * The distinct [ClipboardRead] outcomes map to distinct user-facing
     * notices; only genuinely empty/blank clips claim emptiness.
     */
    fun beginFromClipboard(mode: CompanionMode) {
        if (!begun.compareAndSet(false, true)) return
        // Set the mode BEFORE the first clipboard read: every early-exit
        // notice below must be headed "Decrypt" on decrypt launches, not
        // with the default "Encrypt" (deep-scan finding 4).
        _state.value = _state.value.copy(mode = mode)
        when (val clip = clipboard.read()) {
            is ClipboardRead.Text -> {
                // v0.5.2: this focus-gated read (the assistant's "Encrypt/
                // Decrypt clipboard" rows and the Home buttons route here)
                // is one of the history capture points — the message copied
                // in the messenger lands in the list.
                clipboardRecorder.recordReadText(clip.text)
                when (
                    val parsed = if (mode == CompanionMode.ENCRYPT) {
                        ProcessTextContract.parsePlaintext(clip.text)
                    } else {
                        ProcessTextContract.parseEnvelope(clip.text)
                    }
                ) {
                is ProcessTextContract.SelectedText.Valid ->
                    if (mode == CompanionMode.ENCRYPT) {
                        startEncryptFlow(parsed.text, TextSource.CLIPBOARD, readOnly = true)
                    } else {
                        startDecryptFlow(parsed.text, TextSource.CLIPBOARD, readOnly = true)
                    }
                ProcessTextContract.SelectedText.Empty -> clipboardNotice(
                    UiNotice(R.string.notice_clipboard_blank),
                )
                is ProcessTextContract.SelectedText.Oversized -> clipboardNotice(
                    UiNotice(
                        R.string.notice_input_too_long,
                        "${parsed.limitBytes / 1024}",
                    ),
                    retryable = false,
                )
                }
            }
            ClipboardRead.Empty -> clipboardNotice(
                UiNotice(R.string.notice_clipboard_empty),
            )
            is ClipboardRead.NonText -> clipboardNotice(
                UiNotice(
                    R.string.notice_clipboard_nontext,
                    clip.mimeType ?: "unknown type",
                ),
            )
            is ClipboardRead.Blocked -> clipboardNotice(
                UiNotice(
                    R.string.notice_clipboard_blocked,
                    clip.reason,
                ),
            )
        }
    }

    /**
     * "Try again" from a clipboard notice: resets the flow and re-reads.
     * The retry affordance is only rendered for retryable notices, and only
     * wired for clipboard launches.
     */
    fun retryClipboard() {
        val current = _state.value
        if (current.phase != CompanionUiState.Phase.Notice || !current.retryable) return
        val mode = current.mode
        begun.value = false
        _state.value = CompanionUiState(mode = mode)
        beginFromClipboard(mode)
    }

    // ------------------------------------------------------------ internals

    /** Unguarded encrypt flow (the public entry owns the begun flag). */
    private fun startEncryptFlow(text: String, source: TextSource, readOnly: Boolean) {
        launchSafely {
            val contacts = contactsRepository.observeContacts().first()
            val unlocked = contacts.map { it.id }
                .filter { messageCipher.isUnlocked(it) }
                .toSet()
            // Sticky contact, clamped to an existing contact (FR-005).
            val sticky = settings.lastContactId.first()
            val selected = contacts.firstOrNull { it.id.value == sticky }?.id
            _state.value = CompanionUiState(
                mode = CompanionMode.ENCRYPT,
                source = source,
                readOnly = readOnly,
                phase = if (contacts.isEmpty()) {
                    CompanionUiState.Phase.Notice
                } else {
                    CompanionUiState.Phase.Prepare
                },
                incoming = text,
                contacts = contacts,
                unlocked = unlocked,
                selectedContactId = selected,
                notice = if (contacts.isEmpty()) {
                    UiNotice(R.string.notice_no_contacts)
                } else null,
            )
            // v0.5.2: the prepare card may show a passphrase field for the
            // sticky (locked) contact — compute whether the suggestion is
            // honest for it.
            refreshSuggestPassphrase()
        }
    }

    /** Unguarded decrypt flow (the public entry owns the begun flag). */
    private fun startDecryptFlow(text: String, source: TextSource, readOnly: Boolean) {
        _state.value = CompanionUiState(
            mode = CompanionMode.DECRYPT,
            source = source,
            phase = CompanionUiState.Phase.Prepare,
            incoming = text,
            readOnly = readOnly,
            busy = true,
        )
        launchSafely { decryptInternal(text) }
    }

    /** Clipboard-outcome notice without re-checking the begun flag. */
    private fun clipboardNotice(notice: UiNotice, retryable: Boolean = true) {
        _state.value = CompanionUiState(
            mode = _state.value.mode,
            source = TextSource.CLIPBOARD,
            phase = CompanionUiState.Phase.Notice,
            notice = notice,
            retryable = retryable,
        )
    }

    private suspend fun decryptInternal(envelope: String) {
        _state.value = _state.value.copy(busy = true, notice = null)
        when (val out = messageCipher.decryptEnvelope(envelope)) {
            is DecryptOutcome.Decrypted -> _state.value = _state.value.copy(
                phase = CompanionUiState.Phase.Result,
                resultText = out.plaintext,
                resultContactName = out.contactName,
                busy = false,
                copied = false,
                pendingEraSessionId = null,
            )
            is DecryptOutcome.NeedsUnlock -> _state.value = _state.value.copy(
                phase = CompanionUiState.Phase.NeedsUnlock,
                pendingContactId = out.contactId,
                pendingContactName = out.contactName,
                pendingEraSessionId = null,
                busy = false,
            ).also {
                refreshBiometricOffer()
                refreshSuggestPassphrase()
            }
            DecryptOutcome.NotAnEnvelope -> _state.value = _state.value.copy(
                phase = CompanionUiState.Phase.Notice,
                busy = false,
                notice = UiNotice(R.string.notice_not_envelope),
            )
            DecryptOutcome.UnknownKey -> _state.value = _state.value.copy(
                phase = CompanionUiState.Phase.Notice,
                busy = false,
                notice = UiNotice(R.string.notice_unknown_key),
            )
            is DecryptOutcome.NeedsEraUnlock -> _state.value = _state.value.copy(
                phase = CompanionUiState.Phase.NeedsUnlock,
                pendingContactId = out.contactId,
                pendingContactName = out.contactName,
                pendingEraSessionId = out.eraSessionId,
                busy = false,
                // A fingerprint wrap is bound to the contact's LATEST era —
                // it can never serve a superseded-era envelope.
                biometricOffered = false,
            ).also { refreshSuggestPassphrase() }
            is DecryptOutcome.Failed -> _state.value = _state.value.copy(
                phase = CompanionUiState.Phase.Notice,
                busy = false,
                notice = UiNotice(R.string.notice_decrypt_failed),
            )
        }
    }

    private fun unlockThen(
        contactId: ContactId,
        passphrase: CharArray,
        andThen: suspend () -> Unit,
    ) {
        afterUnlock = andThen
        launchSafely {
            try {
                _state.value = _state.value.copy(busy = true, notice = null)
                val outcome = messageCipher.unlockContact(contactId, passphrase)
                when (outcome) {
                    UnlockOutcome.Unlocked, UnlockOutcome.AlreadyUnlocked -> {
                        _state.value = _state.value.copy(
                            unlocked = _state.value.unlocked + contactId,
                        )
                        andThen()
                        // v0.3.0: right after a passphrase unlock, offer to
                        // remember the key behind the fingerprint.
                        lastUnlockedContactId = contactId
                        offerRemember(contactId)
                    }
                    UnlockOutcome.WrongPassphrase -> _state.value = _state.value.copy(
                        busy = false,
                        notice = UiNotice(R.string.notice_wrong_passphrase),
                    )
                    UnlockOutcome.NoSession -> _state.value = _state.value.copy(
                        busy = false,
                        notice = UiNotice(R.string.notice_no_era),
                    )
                    is UnlockOutcome.Rejected -> _state.value = _state.value.copy(
                        busy = false,
                        notice = UiNotice(R.string.notice_passphrase_rejected, outcome.reason),
                    )
                    is UnlockOutcome.Failed -> _state.value = _state.value.copy(
                        busy = false,
                        notice = UiNotice(R.string.notice_unlock_failed, outcome.reason),
                    )
                    is UnlockOutcome.MemoryHungry -> _state.value = _state.value.copy(
                        busy = false,
                        // v0.5.1: the era's Argon2id profile does not fit this
                        // device's heap — honest notice, no OOM crash.
                        notice = UiNotice(
                            R.string.notice_kdf_memory,
                            "${outcome.requiredBytes / (1024L * 1024L)}",
                        ),
                    )
                }
            } finally {
                // The passphrase is wiped on EVERY path — including a thrown
                // exception (deep-scan finding 12: an unexpected error used
                // to crash the process and leak the buffer).
                passphrase.wipe()
            }
        }
    }

    /**
     * v0.4.0 (old-key-era decryption): unlock a SUPERSEDED era and continue.
     * Mirrors [unlockThen] with two deliberate omissions — the contact is
     * NOT added to the unlocked set (an old-era key must never become the
     * sending key) and no fingerprint remember offer is made (biometric
     * wraps are bound to the latest era only).
     */
    private fun unlockEraThen(
        contactId: ContactId,
        eraSessionId: SessionId,
        passphrase: CharArray,
        andThen: suspend () -> Unit,
    ) {
        launchSafely {
            try {
                _state.value = _state.value.copy(busy = true, notice = null)
                when (val outcome = messageCipher.unlockEra(contactId, eraSessionId, passphrase)) {
                    UnlockOutcome.Unlocked, UnlockOutcome.AlreadyUnlocked -> {
                        _state.value = _state.value.copy(
                            pendingEraSessionId = null,
                        )
                        andThen()
                    }
                    UnlockOutcome.WrongPassphrase -> _state.value = _state.value.copy(
                        busy = false,
                        notice = UiNotice(R.string.notice_wrong_passphrase),
                    )
                    UnlockOutcome.NoSession -> _state.value = _state.value.copy(
                        busy = false,
                        notice = UiNotice(R.string.notice_no_era),
                    )
                    is UnlockOutcome.Rejected -> _state.value = _state.value.copy(
                        busy = false,
                        notice = UiNotice(R.string.notice_passphrase_rejected, outcome.reason),
                    )
                    is UnlockOutcome.Failed -> _state.value = _state.value.copy(
                        busy = false,
                        notice = UiNotice(R.string.notice_unlock_failed, outcome.reason),
                    )
                    is UnlockOutcome.MemoryHungry -> _state.value = _state.value.copy(
                        busy = false,
                        notice = UiNotice(
                            R.string.notice_kdf_memory,
                            "${outcome.requiredBytes / (1024L * 1024L)}",
                        ),
                    )
                }
            } finally {
                // Like unlockThen: wiped on EVERY path, exceptions included.
                passphrase.wipe()
            }
        }
    }

    /**
     * viewModelScope.launch + never-crash wrapper (deep-scan finding 12):
     * every repository/crypto call in the companion flows funnels through
     * here, so an unexpected exception surfaces as a notice with busy
     * cleared instead of killing the process with the sheet frozen.
     */
    private fun launchSafely(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    busy = false,
                    notice = UiNotice(
                        R.string.notice_unexpected_error,
                        e.message ?: e.javaClass.simpleName,
                    ),
                )
            }
        }
    }
}
