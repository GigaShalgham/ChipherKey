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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.turnypt.app.R
import com.turnypt.app.ToolsViewModel
import com.turnypt.app.companion.ImageFiles
import com.turnypt.app.security.BiometricKeyStore
import com.turnypt.app.security.BiometricPromptHost
import com.turnypt.app.security.rememberBiometricPromptHelper
import com.turnypt.core.crypto.Ckf1
import com.turnypt.core.messages.Ck1

/**
 * In-app Decrypt tab (v0.3.0; v0.6.0 adds the Text | Image segments):
 * paste a CK1:… message OR pick a .cki file — auto-detection, contact
 * resolution, passphrase and fingerprint unlock all behave identically
 * in both directions.
 */
@Composable
fun DecryptScreen(
    viewModel: ToolsViewModel,
    biometrics: BiometricKeyStore,
    initialImage: Boolean = false,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val promptHelper = rememberBiometricPromptHelper(biometrics)
    // v0.4.2 (deep-scan): rememberSaveable — a rotation used to wipe the
    // pasted envelope. (Passphrase fields are deliberately NOT saved.)
    var input by rememberSaveable { mutableStateOf("") }
    // v0.6.0: Text | Image segment; the Home tile deep-links into Image.
    var imageSegment by rememberSaveable { mutableStateOf(initialImage) }

    LaunchedEffect(Unit) { viewModel.refresh() }
    BiometricPromptHost(
        helper = promptHelper,
        request = state.biometricPrompt,
        loadRememberKey = viewModel::consumeRememberKey,
        onUnlock = viewModel::onBiometricUnlockResult,
        onRememberDone = viewModel::onRememberDone,
    )

    val envelopeDetected = remember(input) { Ck1.isEnvelope(input.trim()) }

    val context = androidx.compose.ui.platform.LocalContext.current

    // v0.6.0: pick the .cki file to decrypt (SAF; also accepts the rare
    // provider that labels our mime correctly).
    val pickCki = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.decryptImageFrom(
                uri,
                ImageFiles.queryDisplayName(context, uri),
            )
        }
    }
    // v0.6.0: where the decrypted image is saved (SAF CreateDocument).
    val saveImage = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/*"),
    ) { uri ->
        if (uri != null) viewModel.saveDecryptedImageTo(uri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader(
            title = stringResource(R.string.decrypt_title),
            subtitle = stringResource(R.string.decrypt_subtitle),
        )

        ToolSegmentRow(
            imageSelected = imageSegment,
            onTextSelected = { imageSegment = false },
            onImageSelected = { imageSegment = true },
        )

        BusyLine(visible = state.busy)

        if (!imageSegment) {
            // ------------------------------------------------------ TEXT
            SectionCard(
                title = stringResource(R.string.decrypt_input_label),
                icon = TurnyptIcons.LockOpen,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text(stringResource(R.string.decrypt_input_hint)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (envelopeDetected) {
                    Text(
                        stringResource(R.string.decrypt_ck1_detected),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = {
                        viewModel.pasteIntoInput { input = it }
                    }) {
                        Text(stringResource(R.string.action_paste))
                    }
                    // v0.5.2: the clipboard history — tap an entry to insert.
                    OutlinedButton(onClick = viewModel::openClipboardHistory) {
                        Text(stringResource(R.string.action_view_clipboard))
                    }
                }
            }
        } else {
            // ------------------------------------------------------ IMAGE
            SectionCard(
                title = stringResource(R.string.segment_image),
                icon = TurnyptIcons.FileDoc,
            ) {
                Text(
                    stringResource(R.string.image_pick_cki_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        pickCki.launch(
                            arrayOf(Ckf1.MIME_TYPE, "image/*", "application/octet-stream"),
                        )
                    },
                    enabled = !state.imageBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.image_pick_cki_action)) }
                ImageProgressLine(state)
            }
        }

        if (state.unlockNeeded) {
            SectionCard(
                title = stringResource(
                    R.string.message_from,
                    state.unlockContactName ?: "",
                ),
                icon = TurnyptIcons.People,
            ) {
                // v0.4.0: old-key-era envelopes ask for the passphrase of the
                // era they were sent under — which may predate the current
                // one. v0.6.0: .cki files of superseded eras behave the same.
                Text(
                    stringResource(
                        if (state.pendingEraSessionId != null) {
                            R.string.locked_era_hint
                        } else {
                            R.string.locked_hint
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PassphraseInput(
                    label = stringResource(R.string.passphrase_label),
                    buttonLabel = stringResource(R.string.unlock_decrypt_action),
                    busy = state.busy,
                    onSubmit = viewModel::unlockAndContinue,
                    // v0.5.2: suggestion only when THIS side is choosing the
                    // passphrase (locally created era, never unlocked here).
                    allowSuggestion = state.suggestPassphrase,
                    onCopySensitive = viewModel::copyPassphrase,
                )
                if (state.biometricOffered) {
                    OutlinedButton(
                        onClick = viewModel::requestBiometricUnlock,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.use_fingerprint)) }
                }
            }
        } else if (!imageSegment) {
            Button(
                onClick = { viewModel.decrypt(input) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.decrypt_action)) }
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
            state.resultText?.takeIf { !state.resultIsEnvelope }?.let { plaintext ->
                SectionCard(
                    title = stringResource(
                        R.string.result_decrypted_from,
                        state.resultContactName ?: "",
                    ),
                ) {
                    ResultBlock(
                        text = plaintext,
                        monospace = false,
                        copied = state.copied,
                        onCopy = viewModel::copyResult,
                        onClear = viewModel::clearResult,
                    )
                }
            }
        } else {
            DecryptedImageCard(
                state = state,
                onSave = {
                    state.imageDecryptResult?.let { result ->
                        saveImage.launch(result.suggestedName)
                    }
                },
                onShare = {
                    state.imageDecryptResult?.let { result ->
                        ImageShare.cacheFile(
                            context,
                            result.cacheFileUriString,
                            result.mime ?: "application/octet-stream",
                            context.getString(R.string.image_share_decrypted_action),
                        )
                    }
                },
                onClear = viewModel::clearImageResult,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}
