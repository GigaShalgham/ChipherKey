package com.turnypt.app

import android.content.Context
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.URI
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.turnypt.app.R
import com.turnypt.app.companion.CompanionMode
import com.turnypt.app.companion.ContentResolverStreams
import com.turnypt.app.companion.ImageFiles
import com.turnypt.app.security.BiometricKeyStore
import com.turnypt.app.security.BiometricPromptRequest
import com.turnypt.core.common.ContactId
import com.turnypt.core.common.SessionId
import com.turnypt.core.crypto.CipherAlgorithm
import com.turnypt.core.crypto.CryptoError
import com.turnypt.core.messages.Ck1Limits
import com.turnypt.core.sessions.wipe
import com.turnypt.data.preferences.CipherPreference
import com.turnypt.data.preferences.SettingsRepository
import com.turnypt.data.repository.CipherAlgorithmSource
import com.turnypt.data.repository.ClipboardHistoryEntry
import com.turnypt.data.repository.ClipboardHistoryRepository
import com.turnypt.data.repository.Contact
import com.turnypt.data.repository.ContactRepository
import com.turnypt.data.repository.DecryptOutcome
import com.turnypt.data.repository.EncryptOutcome
import com.turnypt.data.repository.MediaCipher
import com.turnypt.data.repository.MediaDecryptOutcome
import com.turnypt.data.repository.MediaEncryptOutcome
import com.turnypt.data.repository.MessageCipher
import com.turnypt.data.repository.SessionKeyRepository
import com.turnypt.data.repository.UnlockOutcome
import com.turnypt.app.clipboard.ClipboardHistoryRecorder
import com.turnypt.security.clipboard.ClipboardAccess
import com.turnypt.security.clipboard.ClipboardRead

/**
 * v0.6.0: the image the user picked for encryption (SAF or sharesheet).
 */
data class PickedImageSource(
    val uriString: String,
    val displayName: String,
    val sizeBytes: Long?,
)

/** v0.6.0: a finished image encryption — the .cki file was streamed out. */
data class ImageEncryptDone(
    val outputUriString: String,
    val displayName: String,
    val algorithmName: String,
    val sizeText: String,
)

/** v0.6.0: a finished image decryption — preview plus save/share target. */
data class ImageDecryptDone(
    val cacheFileUriString: String,
    val suggestedName: String,
    val mime: String?,
    val isImage: Boolean,
    val preview: ImageBitmap?,
    val contactName: String,
    val algorithmName: String,
    val sizeText: String,
)

/**
 * State of the in-app Tools tabs (v0.3.0): manual encrypt and decrypt of
 * typed or pasted text, outside the PROCESS_TEXT companion flows — plus,
 * since v0.6.0, the Text | Image segments of both tabs.
 */
data class ToolsUiState(
    val contacts: List<Contact> = emptyList(),
    val unlocked: Set<ContactId> = emptySet(),
    val selectedContactId: ContactId? = null,
    /** Final output: envelope (encrypt) or plaintext (decrypt). */
    val resultText: String? = null,
    val resultContactName: String? = null,
    /** True when resultText is a CK1 envelope (encrypt direction). */
    val resultIsEnvelope: Boolean = false,
    val copied: Boolean = false,
    val busy: Boolean = false,
    val notice: com.turnypt.app.companion.UiNotice? = null,
    /** True while a locked contact waits for a passphrase. */
    val unlockNeeded: Boolean = false,
    val pendingContactId: ContactId? = null,
    val unlockContactName: String? = null,
    /**
     * v0.4.0: non-null while the pending unlock is for a SUPERSEDED key era
     * (old-era envelope). The passphrase prompt then explains that the OLD
     * era's passphrase is wanted — and the biometric offer stays off (a
     * fingerprint wrap is bound to the latest era only).
     */
    val pendingEraSessionId: SessionId? = null,
    /** v0.3.0: a remembered fingerprint wrap exists for the pending contact. */
    val biometricOffered: Boolean = false,
    /** v0.3.0: non-null while the system biometric prompt should be shown. */
    val biometricPrompt: BiometricPromptRequest? = null,
    /**
     * v0.5.2: non-null while the pending unlock is the FIRST passphrase
     * choice for a locally created era (no friend invite entered) — the only
     * situation where suggesting a strong passphrase is honest (an imported
     * era means the peer already picked the passphrase the user must type).
     */
    val suggestPassphrase: Boolean = false,
    /** v0.5.2: entries shown while the clipboard-history dialog is open. */
    val clipboardHistory: List<ClipboardHistoryEntry> = emptyList(),
    /** v0.5.2: true while the clipboard-history dialog is on screen. */
    val clipboardHistoryOpen: Boolean = false,

    // ------------------------------------------------------ v0.6.0 images
    /** The image chosen for encryption (null until picked). */
    val pickedImage: PickedImageSource? = null,
    /** True while a file is being encrypted or decrypted. */
    val imageBusy: Boolean = false,
    /** Plaintext bytes processed so far (progress line). */
    val imageProgressBytes: Long = 0,
    /** Total plaintext bytes, when the source advertised a size. */
    val imageProgressTotal: Long? = null,
    val imageEncryptResult: ImageEncryptDone? = null,
    val imageDecryptResult: ImageDecryptDone? = null,
    /** True after the decrypted file was saved via CreateDocument. */
    val imageSavedAfterDecrypt: Boolean = false,
    /** The user's cipher preference (null until loaded). */
    val cipherPreference: CipherPreference? = null,
    /** What that preference resolves to on THIS device (chip label). */
    val resolvedCipherName: String? = null,
) {
    val selectedContact: Contact?
        get() = contacts.firstOrNull { it.id == selectedContactId }
}

/**
 * ViewModel behind the in-app Encrypt and Decrypt tabs (v0.3.0).
 *
 * The tabs exist for apps where neither the selection toolbar nor the
 * sharesheet is available: the user types or pastes text directly. All
 * crypto still goes through the [MessageCipher] facade; nothing here
 * re-implements the protocol.
 */
@HiltViewModel
class ToolsViewModel @Inject constructor(
    private val messageCipher: MessageCipher,
    private val mediaCipher: MediaCipher,
    private val contactsRepository: ContactRepository,
    private val sessionKeys: SessionKeyRepository,
    private val settings: SettingsRepository,
    private val clipboard: ClipboardAccess,
    private val clipboardHistoryRepository: ClipboardHistoryRepository,
    private val clipboardRecorder: ClipboardHistoryRecorder,
    private val biometricStore: BiometricKeyStore,
    private val algorithmSource: CipherAlgorithmSource,
    private val streams: ContentResolverStreams,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(ToolsUiState())
    val state = _state.asStateFlow()

    /** The mode the next encrypt/decrypt continues after an unlock. */
    private var pendingMode: CompanionMode = CompanionMode.ENCRYPT

    /** The text the next encrypt/decrypt continues with after an unlock. */
    private var pendingText: String? = null

    /**
     * v0.6.0: the image operation that continues after a successful unlock
     * (encrypt-to-target or decrypt-from-source). Takes precedence over the
     * text continuation when set; kept across a failed unlock attempt so
     * the user can retry with the right passphrase.
     */
    private var pendingImageOp: (suspend () -> Unit)? = null

    /** The decrypted-image cache file owned by this ViewModel. */
    private var decryptCacheFile: File? = null

    /** v0.3.0: contact unlocked most recently (biometric remember target). */
    private var lastUnlockedContactId: ContactId? = null

    /**
     * v0.4.2 (deep-scan N2 guard): the "contactId:eraId" key the outstanding
     * fingerprint prompt unwraps. If the era rotates between showing the
     * prompt and the user touching the sensor, the wrapped bytes belong to
     * the OLD era and must never be activated for the new one.
     */
    private var pendingPromptKey: String? = null

    init {
        refresh()
        refreshCipherInfo()
        // v0.4.2 (deep-scan N1): lockAll(), the 60s expiry sweeper or an era
        // rotation in another surface used to leave this tab's `unlocked`
        // snapshot stale — the Encrypt button stayed up with no unlock card.
        // Re-read the live state on every session/era change instead.
        viewModelScope.launch {
            messageCipher.sessionChanges.collect { resyncUnlockState() }
        }
    }

    /**
     * v0.4.2 (deep-scan N1): drops contacts that are no longer unlocked
     * (locked elsewhere, expired, or their era rotated) so the UI re-locks
     * immediately instead of on the next action.
     */
    private fun resyncUnlockState() {
        launchSafely {
            val live = _state.value.contacts.map { it.id }
                .filter { messageCipher.isUnlocked(it) }
                .toSet()
            _state.value = _state.value.copy(unlocked = live)
            // An unlock card that is on screen may have become wrong (the
            // wrap it offers could be bound to a rotated era).
            if (_state.value.unlockNeeded) refreshBiometricOffer()
        }
    }

    /** Loads contacts and their unlock status. */
    fun refresh() {
        launchSafely {
            val contacts = contactsRepository.observeContacts().first()
            val unlocked = contacts.map { it.id }
                .filter { messageCipher.isUnlocked(it) }
                .toSet()
            val sticky = settings.lastContactId.first()
            val selected = _state.value.selectedContactId
                ?: contacts.firstOrNull { it.id.value == sticky }?.id
                ?: contacts.firstOrNull()?.id
            _state.value = _state.value.copy(
                contacts = contacts,
                unlocked = unlocked,
                selectedContactId = selected,
            )
        }
    }

    fun selectContact(id: ContactId) {
        _state.value = _state.value.copy(selectedContactId = id)
    }

    fun dismissNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    /** Encrypt tab: encrypt [draft] for the selected contact. */
    fun encrypt(draft: String) {
        val s = _state.value
        val id = s.selectedContactId ?: run {
            _state.value = s.copy(notice = UiNoticeOf(R.string.notice_no_contact_selected))
            return
        }
        if (draft.isBlank()) {
            _state.value = s.copy(notice = UiNoticeOf(R.string.notice_empty_input))
            return
        }
        pendingMode = CompanionMode.ENCRYPT
        pendingText = draft
        pendingImageOp = null
        launchSafely {
            _state.value = _state.value.copy(busy = true, notice = null)
            encryptInternal(id, draft)
        }
    }

    /** Decrypt tab: decrypt [envelope] (auto-resolves the contact). */
    fun decrypt(envelope: String) {
        if (envelope.isBlank()) {
            _state.value = _state.value.copy(notice = UiNoticeOf(R.string.notice_empty_input))
            return
        }
        pendingMode = CompanionMode.DECRYPT
        pendingText = envelope
        pendingImageOp = null
        launchSafely {
            _state.value = _state.value.copy(busy = true, notice = null)
            decryptInternal(envelope)
        }
    }

    // ------------------------------------------------------------ images (v0.6.0)

    /**
     * The user picked an image (OpenDocument / photo picker / sharesheet):
     * record it as the encryption source. Decryption results from a
     * previous flow are cleared so the UI tells one story at a time.
     */
    fun pickImage(uri: Uri, displayName: String?, sizeBytes: Long?) {
        _state.value = _state.value.copy(
            pickedImage = PickedImageSource(
                uriString = uri.toString(),
                displayName = displayName?.takeIf { it.isNotBlank() } ?: "image",
                sizeBytes = sizeBytes,
            ),
            imageEncryptResult = null,
            imageDecryptResult = null,
            imageSavedAfterDecrypt = false,
            notice = null,
        )
    }

    /** Clears the picked image (and any encryption result). */
    fun clearPickedImage() {
        _state.value = _state.value.copy(
            pickedImage = null,
            imageEncryptResult = null,
            imageProgressBytes = 0,
            imageProgressTotal = null,
        )
    }

    /**
     * Streams the picked image into [outputUri] (the SAF CreateDocument
     * target) sealed for the selected contact. A locked contact sets the
     * SAME unlock card the text flow uses — the pending image operation
     * resumes right after the passphrase (or fingerprint) unlocks it.
     */
    fun encryptImageTo(outputUri: Uri) {
        val s = _state.value
        val id = s.selectedContactId ?: run {
            _state.value = s.copy(notice = UiNoticeOf(R.string.notice_no_contact_selected))
            return
        }
        val source = s.pickedImage ?: run {
            _state.value = s.copy(notice = UiNoticeOf(R.string.notice_image_pick_first))
            return
        }
        val inputUri = Uri.parse(source.uriString)
        val op: suspend () -> Unit = { encryptImageInternal(id, inputUri, outputUri) }
        pendingImageOp = op
        launchSafely { op() }
    }

    /**
     * The user picked a .cki file: stream-decrypt it into a private cache
     * file (never the gallery), then preview + save/share from there.
     */
    fun decryptImageFrom(uri: Uri, displayName: String?) {
        val op: suspend () -> Unit = { decryptImageInternal(uri, displayName) }
        pendingImageOp = op
        launchSafely { op() }
    }

    /** Copies the decrypted cache file to the SAF CreateDocument target. */
    fun saveDecryptedImageTo(outputUri: Uri) {
        val result = _state.value.imageDecryptResult ?: return
        launchSafely {
            _state.value = _state.value.copy(busy = true, notice = null)
            val cache = File(URI(result.cacheFileUriString))
            val output = streams.write(outputUri)
            if (output == null) {
                _state.value = _state.value.copy(
                    busy = false,
                    notice = UiNoticeOf(R.string.notice_image_write_failed),
                )
                return@launchSafely
            }
            val copied = runCatching {
                cache.inputStream().use { input -> output.use { input.copyTo(it) } }
                true
            }.getOrDefault(false)
            _state.value = if (copied) {
                _state.value.copy(busy = false, imageSavedAfterDecrypt = true)
            } else {
                _state.value.copy(
                    busy = false,
                    notice = UiNoticeOf(R.string.notice_image_write_failed),
                )
            }
        }
    }

    /** Clears the image decrypt result and wipes its cache preview file. */
    fun clearImageResult() {
        decryptCacheFile?.delete()
        decryptCacheFile = null
        _state.value = _state.value.copy(
            imageDecryptResult = null,
            imageSavedAfterDecrypt = false,
            imageBusy = false,
            imageProgressBytes = 0,
            imageProgressTotal = null,
        )
    }

    /** Loads the cipher preference + what it resolves to (the chip label). */
    fun refreshCipherInfo() {
        launchSafely {
            val preference = settings.settings.first().preferredCipher
            // May run the one-time Automatic benchmark: keep it off the
            // main thread.
            val resolved = withContext(Dispatchers.Default) { algorithmSource.algorithm() }
            _state.value = _state.value.copy(
                cipherPreference = preference,
                resolvedCipherName = displayNameOf(resolved),
            )
        }
    }

    /** v0.6.0: change the cipher preference (the chip and Settings share it). */
    fun setPreferredCipher(preference: CipherPreference) {
        launchSafely {
            settings.setPreferredCipher(preference)
            val resolved = withContext(Dispatchers.Default) { algorithmSource.algorithm() }
            _state.value = _state.value.copy(
                cipherPreference = preference,
                resolvedCipherName = displayNameOf(resolved),
            )
        }
    }

    private fun displayNameOf(algorithm: CipherAlgorithm): String = when (algorithm) {
        CipherAlgorithm.AES_256_GCM -> "AES-256-GCM"
        CipherAlgorithm.CHACHA20_POLY1305 -> "ChaCha20-Poly1305"
    }

    private suspend fun encryptImageInternal(contactId: ContactId, inputUri: Uri, outputUri: Uri) {
        _state.value = _state.value.copy(
            busy = true,
            imageBusy = true,
            notice = null,
            imageProgressBytes = 0,
            imageProgressTotal = _state.value.pickedImage?.sizeBytes,
        )
        val input = streams.read(inputUri)
        if (input == null) {
            _state.value = _state.value.copy(
                busy = false,
                imageBusy = false,
                notice = UiNoticeOf(R.string.notice_image_open_failed),
            )
            return
        }
        val output = streams.write(outputUri)
        if (output == null) {
            input.close()
            _state.value = _state.value.copy(
                busy = false,
                imageBusy = false,
                notice = UiNoticeOf(R.string.notice_image_write_failed),
            )
            return
        }
        val outcome = try {
            mediaCipher.encryptFile(contactId, input, output) { done ->
                _state.value = _state.value.copy(imageProgressBytes = done)
            }
        } finally {
            runCatching { input.close() }
            runCatching { output.close() }
        }
        when (outcome) {
            is MediaEncryptOutcome.Encrypted -> {
                settings.setLastContactId(contactId.value)
                val savedName = ImageFiles.queryDisplayName(appContext, outputUri)
                    ?: ImageFiles.suggestedEncryptedName(_state.value.pickedImage?.displayName)
                _state.value = _state.value.copy(
                    busy = false,
                    imageBusy = false,
                    imageEncryptResult = ImageEncryptDone(
                        outputUriString = outputUri.toString(),
                        displayName = savedName,
                        algorithmName = displayNameOf(outcome.algorithm),
                        sizeText = ImageFiles.formatSize(outcome.plaintextBytes),
                    ),
                    notice = null,
                )
            }
            MediaEncryptOutcome.NeedsUnlock -> _state.value = _state.value.copy(
                busy = false,
                imageBusy = false,
                unlockNeeded = true,
                suggestPassphrase = sessionKeys.awaitingFirstPassphrase(contactId),
                pendingContactId = contactId,
                unlockContactName = _state.value.selectedContact?.displayName,
            ).also { refreshBiometricOffer() }
            MediaEncryptOutcome.NoSession -> _state.value = _state.value.copy(
                busy = false,
                imageBusy = false,
                notice = UiNoticeOf(R.string.notice_no_era),
            )
            is MediaEncryptOutcome.Failed -> _state.value = _state.value.copy(
                busy = false,
                imageBusy = false,
                notice = UiNoticeOf(R.string.notice_media_encrypt_failed, outcome.error.toString()),
            )
        }
    }

    private suspend fun decryptImageInternal(uri: Uri, displayName: String?) {
        _state.value = _state.value.copy(
            busy = true,
            imageBusy = true,
            notice = null,
            imageProgressBytes = 0,
            imageProgressTotal = null,
        )
        val input = streams.read(uri)
        if (input == null) {
            _state.value = _state.value.copy(
                busy = false,
                imageBusy = false,
                notice = UiNoticeOf(R.string.notice_image_open_failed),
            )
            return
        }
        // Fresh private cache file; the previous one (if any) is wiped.
        decryptCacheFile?.delete()
        val cache = File(
            appContext.cacheDir,
            "ck_decrypt_${System.currentTimeMillis()}.bin",
        )
        val outcome = try {
            cache.outputStream().use { target ->
                mediaCipher.decryptFile(input, target) { done ->
                    _state.value = _state.value.copy(imageProgressBytes = done)
                }
            }
        } catch (e: Exception) {
            cache.delete()
            null
        } finally {
            runCatching { input.close() }
        }
        when (outcome) {
            is MediaDecryptOutcome.Decrypted -> {
                decryptCacheFile = cache
                val mime = ImageFiles.sniffMime(cache)
                val isImage = ImageFiles.isImageMime(mime)
                val preview = if (isImage) {
                    withContext(Dispatchers.Default) {
                        ImageFiles.decodePreview(cache)?.asImageBitmap()
                    }
                } else {
                    null
                }
                _state.value = _state.value.copy(
                    busy = false,
                    imageBusy = false,
                    imageDecryptResult = ImageDecryptDone(
                        cacheFileUriString = cache.toURI().toString(),
                        suggestedName = ImageFiles.suggestedOriginalName(displayName),
                        mime = mime,
                        isImage = isImage,
                        preview = preview,
                        contactName = outcome.contactName,
                        algorithmName = displayNameOf(outcome.algorithm),
                        sizeText = ImageFiles.formatSize(outcome.plaintextBytes),
                    ),
                    notice = null,
                )
            }
            is MediaDecryptOutcome.NeedsUnlock -> _state.value = _state.value.copy(
                busy = false,
                imageBusy = false,
                unlockNeeded = true,
                suggestPassphrase = sessionKeys.awaitingFirstPassphrase(outcome.contactId),
                pendingContactId = outcome.contactId,
                unlockContactName = outcome.contactName,
                pendingEraSessionId = null,
            ).also { refreshBiometricOffer() }
            is MediaDecryptOutcome.NeedsEraUnlock -> _state.value = _state.value.copy(
                busy = false,
                imageBusy = false,
                unlockNeeded = true,
                suggestPassphrase = sessionKeys.awaitingFirstPassphrase(
                    outcome.contactId,
                    outcome.eraSessionId,
                ),
                pendingContactId = outcome.contactId,
                unlockContactName = outcome.contactName,
                pendingEraSessionId = outcome.eraSessionId,
                // A fingerprint wrap is bound to the contact's LATEST era —
                // it can never serve a superseded-era file.
                biometricOffered = false,
            )
            MediaDecryptOutcome.NotACipherFile -> _state.value = _state.value.copy(
                busy = false,
                imageBusy = false,
                notice = UiNoticeOf(R.string.notice_not_cipher_file),
            )
            MediaDecryptOutcome.UnknownKey -> _state.value = _state.value.copy(
                busy = false,
                imageBusy = false,
                notice = UiNoticeOf(R.string.notice_unknown_key),
            )
            is MediaDecryptOutcome.Failed -> _state.value = _state.value.copy(
                busy = false,
                imageBusy = false,
                notice = if (outcome.error == CryptoError.FileTruncated) {
                    UiNoticeOf(R.string.notice_file_truncated)
                } else {
                    UiNoticeOf(R.string.notice_media_decrypt_failed)
                },
            )
            null -> _state.value = _state.value.copy(
                busy = false,
                imageBusy = false,
                notice = UiNoticeOf(R.string.notice_media_decrypt_failed),
            )
        }
    }

    /** Unlock (passphrase) and continue the pending operation.
     *
     * v0.4.0: when the pending unlock is for a superseded era
     * ([ToolsUiState.pendingEraSessionId]), it goes through
     * [MessageCipher.unlockEra] instead — the contact is NOT marked unlocked
     * for sending, and no fingerprint remember offer is made.
     */
    fun unlockAndContinue(passphrase: CharArray) {
        val id = _state.value.pendingContactId ?: _state.value.selectedContactId ?: run {
            passphrase.wipe(); return
        }
        // v0.6.0: an image operation pending an unlock continues INSTEAD of
        // the text continuation (which is null in the image flows anyway).
        val imageOp = pendingImageOp
        val text = if (imageOp == null) {
            pendingText ?: run {
                passphrase.wipe(); return
            }
        } else {
            null
        }
        val eraSessionId = _state.value.pendingEraSessionId
        launchSafely {
            try {
                _state.value = _state.value.copy(busy = true, notice = null)
                val outcome = if (eraSessionId != null) {
                    messageCipher.unlockEra(id, eraSessionId, passphrase)
                } else {
                    messageCipher.unlockContact(id, passphrase)
                }
                when (outcome) {
                    UnlockOutcome.Unlocked, UnlockOutcome.AlreadyUnlocked -> {
                        _state.value = _state.value.copy(
                            unlocked = if (eraSessionId != null) {
                                _state.value.unlocked
                            } else {
                                _state.value.unlocked + id
                            },
                            unlockNeeded = false,
                            pendingEraSessionId = null,
                        )
                        if (eraSessionId == null) {
                            lastUnlockedContactId = id
                            offerRemember(id)
                        }
                        if (imageOp != null) {
                            pendingImageOp = null
                            imageOp()
                        } else if (text != null) {
                            when (pendingMode) {
                                CompanionMode.ENCRYPT -> encryptInternal(id, text)
                                CompanionMode.DECRYPT -> decryptInternal(text)
                            }
                        }
                    }
                    UnlockOutcome.WrongPassphrase -> _state.value = _state.value.copy(
                        busy = false,
                        notice = UiNoticeOf(R.string.notice_wrong_passphrase),
                    )
                    UnlockOutcome.NoSession -> _state.value = _state.value.copy(
                        busy = false,
                        notice = UiNoticeOf(R.string.notice_no_era),
                    )
                    is UnlockOutcome.Rejected -> _state.value = _state.value.copy(
                        busy = false,
                        notice = UiNoticeOf(R.string.notice_passphrase_rejected, outcome.reason),
                    )
                    is UnlockOutcome.Failed -> _state.value = _state.value.copy(
                        busy = false,
                        notice = UiNoticeOf(R.string.notice_unlock_failed, outcome.reason),
                    )
                    is UnlockOutcome.MemoryHungry -> _state.value = _state.value.copy(
                        busy = false,
                        // v0.5.1: the Argon2id profile does not fit this
                        // device's heap — honest notice, no OOM crash.
                        notice = UiNoticeOf(
                            R.string.notice_kdf_memory,
                            "${outcome.requiredBytes / (1024L * 1024L)}",
                        ),
                    )
                }
            } finally {
                passphrase.wipe()
            }
        }
    }

    // ------------------------------------------------------------ biometric

    /** v0.3.0: recompute whether the fingerprint button should be offered. */
    fun refreshBiometricOffer() {
        launchSafely {
            val enabled = settings.settings.first().biometricUnlockEnabled
            if (!enabled || !biometricStore.isDeviceCapable()) {
                _state.value = _state.value.copy(biometricOffered = false)
                return@launchSafely
            }
            val contactKey = currentBiometricContactKey() ?: run {
                _state.value = _state.value.copy(biometricOffered = false)
                return@launchSafely
            }
            _state.value = _state.value.copy(
                biometricOffered = biometricStore.hasWrapped(contactKey),
            )
        }
    }

    /** v0.3.0: "Use fingerprint" — show the system prompt for a wrap. */
    fun requestBiometricUnlock() {
        val id = _state.value.pendingContactId ?: _state.value.selectedContactId ?: return
        launchSafely {
            val contactKey = biometricContactKeyFor(id) ?: return@launchSafely
            if (!biometricStore.hasWrapped(contactKey)) {
                // v0.4.2 (deep-scan N2): the offer was computed for an older
                // era — drop it instead of dead-tapping the button silently.
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
     * v0.3.0: outcome of the system prompt (null = canceled / failed).
     *
     * v0.5.1: [reason] carries the system's localized message for
     * non-cancel failures so the notice explains what happened.
     */
    fun onBiometricUnlockResult(keyBytes: ByteArray?, reason: String? = null) {
        _state.value = _state.value.copy(biometricPrompt = null)
        if (keyBytes == null) {
            _state.value = _state.value.copy(
                notice = reason?.let {
                    UiNoticeOf(R.string.notice_biometric_failed_reason, it)
                } ?: UiNoticeOf(R.string.notice_biometric_failed),
            )
            return
        }
        val id = _state.value.pendingContactId ?: _state.value.selectedContactId
        if (id == null) {
            keyBytes.fill(0)
            return
        }
        launchSafely {
            // v0.4.2 (deep-scan N2 guard): the era may have rotated between
            // showing the prompt and touching the sensor. A wrap of the OLD
            // era must never be activated as the NEW era's key — discard it
            // and fall back to the passphrase, honestly.
            val requestedKey = pendingPromptKey
            pendingPromptKey = null
            val currentKey = biometricContactKeyFor(id)
            if (requestedKey != null && currentKey != null && requestedKey != currentKey) {
                keyBytes.fill(0)
                biometricStore.clear(requestedKey)
                _state.value = _state.value.copy(
                    busy = false,
                    unlockNeeded = true,
                    biometricOffered = false,
                    notice = UiNoticeOf(R.string.notice_biometric_stale),
                )
                return@launchSafely
            }
            val contactKey = biometricContactKeyFor(id)
            when (messageCipher.unlockContactWithKey(id, keyBytes)) {
                UnlockOutcome.Unlocked, UnlockOutcome.AlreadyUnlocked -> {
                    _state.value = _state.value.copy(
                        unlocked = _state.value.unlocked + id,
                        unlockNeeded = false,
                        notice = null,
                    )
                    val text = pendingText
                    // v0.6.0: the fingerprint unlock serves image flows too
                    // — the pending image op resumes exactly like the text
                    // continuation does.
                    val imageOp = pendingImageOp
                    if (imageOp != null) {
                        pendingImageOp = null
                        imageOp()
                    } else {
                        when (pendingMode) {
                            CompanionMode.ENCRYPT ->
                                if (text != null) encryptInternal(id, text)
                            CompanionMode.DECRYPT ->
                                if (text != null) decryptInternal(text)
                        }
                    }
                }
                UnlockOutcome.NoSession -> _state.value = _state.value.copy(
                    notice = UiNoticeOf(R.string.notice_no_era),
                )
                else -> {
                    contactKey?.let { biometricStore.clear(it) }
                    _state.value = _state.value.copy(
                        notice = UiNoticeOf(R.string.notice_biometric_stale),
                        unlockNeeded = true,
                        busy = false,
                    )
                }
            }
        }
    }

    /**
     * v0.3.0: offer to remember the just-unlocked key behind the prompt.
     *
     * v0.5.1 (reported "fingerprint didn't work anywhere"): the Settings
     * toggle no longer hides the offer — the system prompt ("Remember this
     * key" / "Not now") is the consent, and success persists the toggle
     * ON. Turning the toggle OFF still wipes every wrap.
     */
    fun offerRemember(contactId: ContactId) {
        launchSafely {
            if (!biometricStore.isDeviceCapable()) return@launchSafely
            val contactKey = biometricContactKeyFor(contactId) ?: return@launchSafely
            if (!biometricStore.hasWrapped(contactKey)) {
                _state.value = _state.value.copy(
                    biometricPrompt = BiometricPromptRequest.Remember(contactKey),
                )
            }
        }
    }

    /**
     * v0.3.0: the key bytes the Remember prompt should wrap.
     */
    suspend fun consumeRememberKey(): ByteArray? {
        if (_state.value.biometricPrompt !is BiometricPromptRequest.Remember) return null
        val id = lastUnlockedContactId ?: return null
        return messageCipher.exportSessionKey(id)
    }

    /**
     * v0.3.0: prompt finished — [success] says whether the wrap persisted.
     *
     * v0.5.1: a persisted wrap turns the Settings toggle ON (the
     * authenticated "Remember this key" prompt IS the opt-in); a non-cancel
     * failure surfaces the system's reason instead of silence.
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
                notice = UiNoticeOf(R.string.notice_biometric_failed_reason, reason),
            )
        }
    }

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

    /** Paste the current clipboard text into the input field. */
    fun pasteIntoInput(onPasted: (String) -> Unit) {
        launchSafely {
            when (val clip = clipboard.read()) {
                is ClipboardRead.Text -> {
                    // v0.5.2: an explicit Paste is one of the capture points
                    // of the clipboard history (explicit user action from a
                    // focused activity).
                    clipboardRecorder.recordReadText(clip.text)
                    onPasted(clip.text)
                }
                else -> _state.value = _state.value.copy(
                    notice = UiNoticeOf(R.string.notice_clipboard_empty),
                )
            }
        }
    }

    /**
     * v0.5.2: opens the clipboard HISTORY (user request: "the list of
     * things you copied and had copied before"). Opening the dialog is an
     * explicit user action from a focused activity, so the current clip is
     * read + recorded first — it then always appears as the newest entry.
     */
    fun openClipboardHistory() {
        launchSafely {
            val clip = clipboard.read()
            if (clip is ClipboardRead.Text) {
                clipboardRecorder.recordReadText(clip.text)
            }
            _state.value = _state.value.copy(
                clipboardHistory = clipboardHistoryRepository.snapshot(),
                clipboardHistoryOpen = true,
                notice = null,
            )
        }
    }

    /** v0.5.2: closes the history dialog. */
    fun dismissClipboardHistory() {
        _state.value = _state.value.copy(clipboardHistoryOpen = false)
    }

    /** v0.5.2: deletes one history entry (the per-row ✕). */
    fun deleteClipboardEntry(id: Long) {
        launchSafely {
            clipboardHistoryRepository.delete(id)
            _state.value = _state.value.copy(
                clipboardHistory = clipboardHistoryRepository.snapshot(),
            )
        }
    }

    /** v0.5.2: wipes the whole history ("Clear history" in the dialog). */
    fun clearClipboardHistory() {
        launchSafely {
            clipboardHistoryRepository.clear()
            _state.value = _state.value.copy(
                clipboardHistory = emptyList(),
                notice = UiNoticeOf(R.string.clipboard_history_cleared),
            )
        }
    }

    /**
     * v0.5.2: copies a SUGGESTED PASSPHRASE — sensitive: the sensitive
     * clipboard path (hidden preview on Android 13+) and NEVER recorded in
     * the history.
     */
    fun copyPassphrase(text: String): Boolean {
        val copied = clipboard.setSensitiveText(text)
        if (copied) {
            clipboardRecorder.onSensitiveCopied(text)
        }
        return copied
    }

    /** Copy the result to the clipboard (explicit user action). */
    fun copyResult() {
        val result = _state.value.resultText ?: return
        val sensitive = !_state.value.resultIsEnvelope
        val copied = if (sensitive) {
            clipboard.setSensitiveText(result)
        } else {
            clipboard.setText(result)
        }
        _state.value = if (copied) {
            // v0.5.2: envelopes (public by design) join the history so they
            // can be re-pasted; decrypted plaintext never does.
            if (!sensitive) clipboardRecorder.onCopied(result)
            _state.value.copy(copied = true)
        } else {
            _state.value.copy(notice = UiNoticeOf(R.string.notice_clipboard_access_failed))
        }
    }

    /** Clear the result block. */
    fun clearResult() {
        _state.value = _state.value.copy(
            resultText = null,
            resultContactName = null,
            resultIsEnvelope = false,
            copied = false,
            notice = null,
            unlockNeeded = false,
            pendingEraSessionId = null,
        )
    }

    // ------------------------------------------------------------ internals

    private suspend fun encryptInternal(contactId: ContactId, draft: String) {
        _state.value = _state.value.copy(busy = true, notice = null)
        when (val out = messageCipher.encryptDraft(contactId, draft)) {
            is EncryptOutcome.Encrypted -> {
                settings.setLastContactId(contactId.value)
                _state.value = _state.value.copy(
                    busy = false,
                    resultText = out.envelope,
                    resultContactName = _state.value.selectedContact?.displayName,
                    resultIsEnvelope = true,
                    copied = false,
                )
            }
            is EncryptOutcome.NeedsUnlock -> _state.value = _state.value.copy(
                busy = false,
                unlockNeeded = true,
                // v0.5.2: offer the passphrase suggestion only when THIS side
                // is choosing the passphrase (locally created era, never
                // unlocked here) — see SessionKeyRepository.awaitingFirstPassphrase.
                suggestPassphrase = sessionKeys.awaitingFirstPassphrase(contactId),
                pendingContactId = contactId,
                unlockContactName = _state.value.selectedContact?.displayName,
            ).also { refreshBiometricOffer() }
            is EncryptOutcome.NoSession -> _state.value = _state.value.copy(
                busy = false,
                notice = UiNoticeOf(R.string.notice_no_era),
            )
            is EncryptOutcome.EmptyDraft -> _state.value = _state.value.copy(
                busy = false,
                notice = UiNoticeOf(R.string.notice_empty_input),
            )
            is EncryptOutcome.Failed -> _state.value = _state.value.copy(
                busy = false,
                notice = if (out.error == CryptoError.InputTooLarge) {
                    // v0.5.1: the char-count guard in MessageCipher rejected
                    // an oversized draft before any big allocation — say so
                    // in the same words the companion flows use.
                    UiNoticeOf(R.string.notice_input_too_long, "${Ck1Limits.MAX_PLAINTEXT_BYTES / 1024}")
                } else {
                    UiNoticeOf(R.string.notice_encrypt_failed, out.error.toString())
                },
            )
        }
    }

    private suspend fun decryptInternal(envelope: String) {
        _state.value = _state.value.copy(busy = true, notice = null)
        when (val out = messageCipher.decryptEnvelope(envelope)) {
            is DecryptOutcome.Decrypted -> _state.value = _state.value.copy(
                busy = false,
                resultText = out.plaintext,
                resultContactName = out.contactName,
                resultIsEnvelope = false,
                copied = false,
            )
            is DecryptOutcome.NeedsUnlock -> _state.value = _state.value.copy(
                busy = false,
                unlockNeeded = true,
                suggestPassphrase = sessionKeys.awaitingFirstPassphrase(out.contactId),
                pendingContactId = out.contactId,
                unlockContactName = out.contactName,
                pendingEraSessionId = null,
            ).also { refreshBiometricOffer() }
            DecryptOutcome.NotAnEnvelope -> _state.value = _state.value.copy(
                busy = false,
                notice = UiNoticeOf(R.string.notice_not_envelope),
            )
            DecryptOutcome.UnknownKey -> _state.value = _state.value.copy(
                busy = false,
                notice = UiNoticeOf(R.string.notice_unknown_key),
            )
            is DecryptOutcome.NeedsEraUnlock -> _state.value = _state.value.copy(
                busy = false,
                unlockNeeded = true,
                suggestPassphrase = sessionKeys.awaitingFirstPassphrase(
                    out.contactId,
                    out.eraSessionId,
                ),
                pendingContactId = out.contactId,
                unlockContactName = out.contactName,
                pendingEraSessionId = out.eraSessionId,
                // A fingerprint wrap is bound to the contact's LATEST era —
                // it can never serve a superseded-era envelope.
                biometricOffered = false,
            )
            is DecryptOutcome.Failed -> _state.value = _state.value.copy(
                busy = false,
                notice = UiNoticeOf(R.string.notice_decrypt_failed),
            )
        }
    }

    private fun launchSafely(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    busy = false,
                    notice = UiNoticeOf(
                        R.string.notice_unexpected_error,
                        e.message ?: e.javaClass.simpleName,
                    ),
                )
            }
        }
    }

    override fun onCleared() {
        pendingText = null
        pendingImageOp = null
        // The decrypted-image preview cache dies with the ViewModel.
        decryptCacheFile?.delete()
        decryptCacheFile = null
        super.onCleared()
    }
}

/** Local alias so the state class above stays short. */
private fun UiNoticeOf(res: Int, arg: String? = null) =
    com.turnypt.app.companion.UiNotice(res, arg)
