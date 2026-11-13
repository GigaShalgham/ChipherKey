package com.turnypt.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.turnypt.app.R
import com.turnypt.app.ToolsViewModel
import com.turnypt.app.companion.ImageFiles
import com.turnypt.app.security.BiometricKeyStore
import com.turnypt.app.security.BiometricPromptHost
import com.turnypt.app.security.rememberBiometricPromptHelper

/**
 * In-app Encrypt tab (v0.3.0; v0.6.0 adds the Text | Image segments):
 * type or paste a message OR pick an image, pick a contact, encrypt — for
 * apps where neither the selection toolbar nor the sharesheet reaches the
 * content. All crypto runs through the shared ToolsViewModel.
 */
@Composable
fun EncryptScreen(
    viewModel: ToolsViewModel,
    biometrics: BiometricKeyStore,
    initialImage: Boolean = false,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val promptHelper = rememberBiometricPromptHelper(biometrics)
    // v0.4.2 (deep-scan): rememberSaveable — a rotation used to wipe the
    // typed draft. (Passphrase fields are deliberately NOT saved: view-state
    // must never hold a passphrase longer than the interaction itself.)
    var input by rememberSaveable { mutableStateOf("") }
    // v0.6.0: Text | Image segment; the Home tile deep-links into Image.
    var imageSegment by rememberSaveable { mutableStateOf(initialImage) }
    var cipherDialogOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refresh() }
    // The Remember prompt's key export is awaited INSIDE the host's effect
    // (deep-scan F1) — never pre-loaded into composition state.
    BiometricPromptHost(
        helper = promptHelper,
        request = state.biometricPrompt,
        loadRememberKey = viewModel::consumeRememberKey,
        onUnlock = viewModel::onBiometricUnlockResult,
        onRememberDone = viewModel::onRememberDone,
    )

    val context = androidx.compose.ui.platform.LocalContext.current

    // v0.6.0: pick the image to encrypt (SAF — no storage permission).
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.pickImage(
                uri,
                ImageFiles.queryDisplayName(context, uri),
                ImageFiles.querySize(context, uri),
                // v1.3.0: the advertised type rides along so an extension-less
                // source still lands in the TNP family with its real image
                // extension inside (IMG_001 + image/png -> IMG_001.png.tnpi).
                ImageFiles.queryMimeType(context, uri),
            )
        }
    }
    // v1.3.0: where the encrypted image lands (SAF CreateDocument — user
    // picks the location; nothing is written without this explicit target).
    // The output carries the Turnypt-ecosystem extension — <name>.tnpi —
    // and is created under its own application/x-turnypt-image type, so
    // the suggested name arrives in the dialog pre-suffixed.
    val createCki = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ImageFiles.ENCRYPTED_IMAGE_MIME),
    ) { uri ->
        if (uri != null) viewModel.encryptImageTo(uri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader(
            title = stringResource(R.string.encrypt_title),
            subtitle = stringResource(R.string.encrypt_subtitle),
        )

        ToolSegmentRow(
            imageSelected = imageSegment,
            onTextSelected = { imageSegment = false },
            onImageSelected = { imageSegment = true },
        )

        BusyLine(visible = state.busy)

        if (state.contacts.isEmpty()) {
            EmptyState(
                icon = TurnyptIcons.People,
                title = stringResource(R.string.contacts_empty_title),
                body = stringResource(R.string.contacts_empty_body),
            )
        } else if (!imageSegment) {
            // ------------------------------------------------------ TEXT
            SectionCard(
                title = stringResource(R.string.tools_input_label),
                icon = TurnyptIcons.Lock,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text(stringResource(R.string.tools_input_hint)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = {
                        viewModel.pasteIntoInput { input = it }
                    }) {
                        Text(stringResource(R.string.action_paste))
                    }
                    // v0.5.2: the clipboard HISTORY ("things you copied and
                    // had copied before") — tap an entry to insert it.
                    OutlinedButton(onClick = viewModel::openClipboardHistory) {
                        Text(stringResource(R.string.action_view_clipboard))
                    }
                }
            }
        } else {
            // ------------------------------------------------------ IMAGE
            SectionCard(
                title = stringResource(R.string.segment_image),
                icon = TurnyptIcons.Image,
            ) {
                Text(
                    stringResource(R.string.image_pick_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.pickedImage != null) {
                    PickedImageCard(
                        displayName = state.pickedImage!!.displayName,
                        sizeBytes = state.pickedImage!!.sizeBytes,
                        busy = state.imageBusy,
                        onReplace = { pickImage.launch(arrayOf("image/*")) },
                    )
                } else {
                    Button(
                        onClick = { pickImage.launch(arrayOf("image/*")) },
                        enabled = !state.imageBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.image_pick_action)) }
                }
                ImageProgressLine(state)
            }
        }

        SectionCard(
            title = stringResource(R.string.encrypt_contact_label),
            icon = TurnyptIcons.People,
        ) {
            ContactPicker(
                contacts = state.contacts,
                selectedContactId = state.selectedContactId,
                unlocked = state.unlocked,
                onContactSelected = viewModel::selectContact,
            )
            if (state.selectedContact == null || state.selectedContactId in state.unlocked) {
                if (!imageSegment) {
                    Button(
                        onClick = { viewModel.encrypt(input) },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.encrypt_action)) }
                } else {
                    if (state.pickedImage != null) {
                        CipherChip(
                            preference = state.cipherPreference,
                            resolvedName = state.resolvedCipherName,
                            onClick = { cipherDialogOpen = true },
                        )
                    }
                    Button(
                        onClick = {
                            val name = ImageFiles.suggestedEncryptedName(
                                state.pickedImage?.displayName,
                                state.pickedImage?.mime,
                            )
                            createCki.launch(name)
                        },
                        enabled = !state.busy && state.pickedImage != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.image_encrypt_action)) }
                }
            }
        }

        // v1.2.0 (user report: "the password input in the encryption section
        // is visually broken"): the unlock card is its OWN section — the
        // same shape the Decrypt tab uses — and it is armed on the first
        // appearance of the screen (ToolsViewModel.refresh), so the
        // passphrase field, the live strength hint, the suggestion (when
        // honest) and the fingerprint button always render together as one
        // complete card instead of appearing piecemeal after extra taps.
        if (state.selectedContact != null && state.selectedContactId !in state.unlocked) {
            SectionCard(
                title = stringResource(
                    R.string.unlock_for_title,
                    state.unlockContactName ?: state.selectedContact!!.displayName,
                ),
                icon = TurnyptIcons.Lock,
            ) {
                UnlockCard(
                    state = state,
                    onSubmit = viewModel::unlockAndContinue,
                    onUseFingerprint = viewModel::requestBiometricUnlock,
                    onCopyPassphrase = viewModel::copyPassphrase,
                )
            }
        }

        NoticeBanner(state.notice)

        // v0.5.2: clipboard history dialog ("Clipboard" next to Paste).
        if (state.clipboardHistoryOpen) {
            ClipboardHistoryDialog(
                entries = state.clipboardHistory,
                onUse = { entry ->
                    viewModel.dismissClipboardHistory()
                    input = entry.text
                },
                onDelete = viewModel::deleteClipboardEntry,
                onClearAll = viewModel::clearClipboardHistory,
                onDismiss = viewModel::dismissClipboardHistory,
            )
        }

        if (!imageSegment) {
            state.resultText?.takeIf { state.resultIsEnvelope }?.let { envelope ->
                SectionCard(
                    title = stringResource(
                        R.string.result_encrypted_for,
                        state.resultContactName ?: "",
                    ),
                ) {
                    ResultBlock(
                        text = envelope,
                        monospace = true,
                        copied = state.copied,
                        onCopy = viewModel::copyResult,
                        onClear = viewModel::clearResult,
                    )
                    Text(
                        stringResource(R.string.envelope_truncated_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            EncryptedFileCard(
                state = state,
                onShare = {
                    state.imageEncryptResult?.let { result ->
                        // v1.3.0: the encrypted image is a Turnypt file
                        // (<name>.tnpi) and shares under its own
                        // x-turnypt-image type; legacy .cki outputs and
                        // anything else keep the honest x-cipherkey type.
                        ImageShare.uri(
                            context,
                            Uri.parse(result.outputUriString),
                            ImageFiles.encryptedShareMime(result.displayName),
                            context.getString(R.string.image_share_action),
                        )
                    }
                },
                onClear = viewModel::clearPickedImage,
            )
        }

        if (cipherDialogOpen) {
            CipherPreferenceDialog(
                current = state.cipherPreference ?: com.turnypt.data.preferences.CipherPreference.AUTO,
                autoResolvedName = state.resolvedCipherName,
                onSelect = { preference ->
                    viewModel.setPreferredCipher(preference)
                },
                onDismiss = { cipherDialogOpen = false },
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

/** Locked-contact card: passphrase (or fingerprint) before continuing. */
@Composable
private fun UnlockCard(
    state: com.turnypt.app.ToolsUiState,
    onSubmit: (CharArray) -> Unit,
    onUseFingerprint: () -> Unit,
    onCopyPassphrase: (String) -> Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.locked_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PassphraseInput(
            label = stringResource(R.string.passphrase_label),
            buttonLabel = stringResource(R.string.unlock_encrypt_action),
            busy = state.busy,
            onSubmit = onSubmit,
            // v0.5.2: the suggestion appears at the password-entry moment,
            // but only when this side is CHOOSING the passphrase (a locally
            // created era, never unlocked) — an imported era means the peer
            // already picked it.
            allowSuggestion = state.suggestPassphrase,
            onCopySensitive = onCopyPassphrase,
        )
        if (state.biometricOffered) {
            OutlinedButton(
                onClick = onUseFingerprint,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.use_fingerprint)) }
        }
    }
}
