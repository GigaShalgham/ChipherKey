package com.turnypt.app.companion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.turnypt.app.R
import com.turnypt.app.ui.PassphraseStrengthHint
import com.turnypt.app.ui.SuggestPassphraseSection
import com.turnypt.app.security.rememberBiometricPromptHelper
import com.turnypt.app.security.BiometricPromptHost
import com.turnypt.core.common.ContactId

/**
 * Compact utility sheet shared by all three companion entry activities.
 * Deliberately plain: one column, Material3, no navigation — every flow is
 * at most two steps (prepare/unlock → result).
 *
 * v0.3.0: hosts the system fingerprint prompt — [onBiometricUnlock] and
 * [onRememberKey] route the prompt outcomes back into the ViewModel.
 */
@Composable
fun CompanionSheet(
    state: CompanionUiState,
    onContactSelected: (ContactId) -> Unit,
    onEncrypt: () -> Unit,
    onUnlockEncrypt: (CharArray) -> Unit,
    onUnlockDecrypt: (CharArray) -> Unit,
    onCopy: () -> Unit,
    onInsert: () -> Unit,
    onClose: () -> Unit,
    onRetryClipboard: (() -> Unit)? = null,
    biometrics: com.turnypt.app.security.BiometricKeyStore? = null,
    onRequestBiometricUnlock: () -> Unit = {},
    onBiometricUnlock: (ByteArray?, String?) -> Unit = { _, _ -> },
    loadRememberKey: (suspend () -> ByteArray?)? = null,
    onRememberDone: (Boolean, String?) -> Unit = { _, _ -> },
    /** v0.5.2: sensitive copy for the passphrase suggestion (never recorded). */
    onCopyPassphrase: (String) -> Boolean = { false },
) {
    val promptHelper = biometrics?.let { rememberBiometricPromptHelper(it) }

    // The Remember prompt's key export is awaited INSIDE the host's effect
    // (deep-scan F1) — never pre-loaded into composition state.
    BiometricPromptHost(
        helper = promptHelper,
        request = state.biometricPrompt,
        loadRememberKey = loadRememberKey,
        onUnlock = onBiometricUnlock,
        onRememberDone = onRememberDone,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SheetHeader(state)

        if (state.busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        when (state.phase) {
            // Clipboard launches sit here for the brief moment between
            // activity creation and the first onWindowFocusChanged(true) —
            // the deferred read has not run yet (spec 004).
            CompanionUiState.Phase.Idle -> if (onRetryClipboard != null) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.reading_clipboard), fontSize = 14.sp)
                }
            } else Unit

            CompanionUiState.Phase.Prepare ->
                if (state.mode == CompanionMode.ENCRYPT) {
                    EncryptPrepareCard(state, onContactSelected, onEncrypt, onUnlockEncrypt, onCopyPassphrase)
                } else {
                    // Decrypt transient state: work already in flight.
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.decrypting), fontSize = 14.sp)
                    }
                }

            CompanionUiState.Phase.NeedsUnlock ->
                UnlockCard(
                    state = state,
                    onUnlockDecrypt = onUnlockDecrypt,
                    onUseFingerprint = onRequestBiometricUnlock,
                    onCopyPassphrase = onCopyPassphrase,
                )

            CompanionUiState.Phase.Result ->
                ResultCard(state, onCopy, onInsert, onClose)

            CompanionUiState.Phase.Notice ->
                NoticeCard(state, onClose, onRetryClipboard)
        }

        // Deep-scan finding 10: when the phase itself is Notice, the card
        // above already renders the text — this inline error line is for
        // notices that accompany a live phase (Prepare / NeedsUnlock /
        // Result), e.g. "Wrong passphrase." under the unlock card.
        if (state.phase != CompanionUiState.Phase.Notice) {
            state.notice?.let { notice ->
                NoticeText(notice)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

/** Renders a typed notice in the current locale (v0.3.0). */
@Composable
fun NoticeText(notice: UiNotice) {
    val text = notice.arg?.let { arg ->
        stringResource(notice.res, arg)
    } ?: stringResource(notice.res)
    Text(
        text = text,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun SheetHeader(state: CompanionUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = if (state.mode == CompanionMode.ENCRYPT) {
                stringResource(R.string.sheet_encrypt)
            } else {
                stringResource(R.string.sheet_decrypt)
            },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = when (state.source) {
                TextSource.SELECTION -> stringResource(R.string.sheet_from_selection)
                TextSource.SHARE -> stringResource(R.string.sheet_from_share)
                TextSource.CLIPBOARD -> stringResource(R.string.sheet_from_clipboard)
            } + "  ·  Turnypt",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EncryptPrepareCard(
    state: CompanionUiState,
    onContactSelected: (ContactId) -> Unit,
    onEncrypt: () -> Unit,
    onUnlockEncrypt: (CharArray) -> Unit,
    onCopyPassphrase: (String) -> Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.draft_label),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = state.incoming ?: "",
            fontSize = 15.sp,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .heightIn(max = 120.dp),
        )

        ContactDropdown(
            contacts = state.contacts,
            selectedContactId = state.selectedContactId,
            unlocked = state.unlocked,
            onContactSelected = onContactSelected,
        )

        if (state.selectedContactId != null && !state.selectedContactUnlocked) {
            PassphraseField(
                label = stringResource(
                    R.string.passphrase_for,
                    state.selectedContact?.displayName ?: "",
                ),
                buttonLabel = stringResource(R.string.unlock_encrypt_action),
                busy = state.busy,
                onSubmit = onUnlockEncrypt,
                // v0.5.2: suggestion only when THIS side is choosing the
                // shared passphrase (locally created era, never unlocked).
                allowSuggestion = state.suggestPassphrase,
                onCopySensitive = onCopyPassphrase,
            )
        } else {
            Button(
                onClick = onEncrypt,
                enabled = state.selectedContactId != null && !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.encrypt_action)) }
        }

        if (state.selectedContactUnlocked) {
            Text(
                stringResource(R.string.session_unlocked_hint),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactDropdown(
    contacts: List<com.turnypt.data.repository.Contact>,
    selectedContactId: ContactId?,
    unlocked: Set<ContactId>,
    onContactSelected: (ContactId) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = contacts.firstOrNull { it.id == selectedContactId }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        TextField(
            value = selected?.displayName ?: stringResource(R.string.contact_choose),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.encrypt_contact_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            contacts.forEach { contact ->
                DropdownMenuItem(
                    text = {
                        // v0.4.2 (deep-scan): the lock glyph comes from the
                        // shared string resources now — it was a hardcoded
                        // emoji literal that only matched the English build.
                        Text(
                            (if (contact.id in unlocked) {
                                stringResource(R.string.contact_unlocked)
                            } else {
                                stringResource(R.string.contact_locked)
                            }) + "  " + contact.displayName,
                        )
                    },
                    onClick = {
                        onContactSelected(contact.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun UnlockCard(
    state: CompanionUiState,
    onUnlockDecrypt: (CharArray) -> Unit,
    onUseFingerprint: () -> Unit,
    onCopyPassphrase: (String) -> Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.message_from, state.pendingContactName ?: ""),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            // v0.4.0: old-key-era envelopes ask for the passphrase of the era
            // they were sent under — which may predate the current one.
            stringResource(
                if (state.pendingEraSessionId != null) {
                    R.string.locked_era_hint
                } else {
                    R.string.locked_hint
                },
            ),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PassphraseField(
            label = stringResource(R.string.passphrase_label),
            buttonLabel = stringResource(R.string.unlock_decrypt_action),
            busy = state.busy,
            onSubmit = onUnlockDecrypt,
            // v0.5.2: suggestion only when THIS side is choosing the shared
            // passphrase (locally created era, never unlocked here).
            allowSuggestion = state.suggestPassphrase,
            onCopySensitive = onCopyPassphrase,
        )
        // v0.3.0: remembered fingerprint wrap for this contact's era.
        if (state.biometricOffered) {
            OutlinedButton(
                onClick = onUseFingerprint,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.use_fingerprint)) }
        }
    }
}

@Composable
private fun PassphraseField(
    label: String,
    buttonLabel: String,
    busy: Boolean,
    onSubmit: (CharArray) -> Unit,
    allowSuggestion: Boolean = false,
    onCopySensitive: ((String) -> Boolean)? = null,
) {
    var passphrase by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            label = { Text(label) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Password,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        // v0.4.0: live strength hint while the user types.
        PassphraseStrengthHint(passphrase)
        if (allowSuggestion && onCopySensitive != null) {
            SuggestPassphraseSection(
                onUse = { passphrase = it },
                onCopySensitive = onCopySensitive,
            )
        }
        Button(
            onClick = {
                // Convert to CharArray for the crypto path (which wipes it)
                // and clear the field immediately afterwards. The Compose
                // String itself cannot be zeroed — known, documented
                // limitation of the UI toolkit (same as ContactsScreen).
                onSubmit(passphrase.toCharArray())
                passphrase = ""
            },
            enabled = passphrase.isNotEmpty() && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(buttonLabel) }
    }
}

@Composable
private fun ResultCard(
    state: CompanionUiState,
    onCopy: () -> Unit,
    onInsert: () -> Unit,
    onClose: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = when (state.mode) {
                CompanionMode.ENCRYPT ->
                    stringResource(
                        R.string.result_encrypted_for,
                        state.resultContactName ?: "",
                    )
                CompanionMode.DECRYPT ->
                    stringResource(
                        R.string.result_decrypted_from,
                        state.resultContactName ?: "",
                    )
            },
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )

        SelectionContainer {
            Text(
                text = state.resultText ?: "",
                fontSize = if (state.mode == CompanionMode.ENCRYPT) 12.sp else 15.sp,
                fontFamily = if (state.mode == CompanionMode.ENCRYPT) FontFamily.Monospace else FontFamily.Default,
                maxLines = if (state.mode == CompanionMode.ENCRYPT) 6 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            )
        }

        if (state.mode == CompanionMode.ENCRYPT) {
            Text(
                stringResource(R.string.envelope_truncated_hint),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.copied) {
            Text(
                stringResource(R.string.notice_copied),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // v0.3.0: insert works for decrypt-from-selection too now.
        if (state.insertable) {
            Button(
                onClick = onInsert,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.replace_in_field)) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onCopy,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.action_copy)) }
            OutlinedButton(
                onClick = onClose,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.action_done)) }
        }
    }
}

@Composable
private fun NoticeCard(
    state: CompanionUiState,
    onClose: () -> Unit,
    onRetryClipboard: (() -> Unit)?,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.padding(vertical = 24.dp),
    ) {
        state.notice?.let { NoticeText(it) }
        if (state.retryable && onRetryClipboard != null) {
            Button(onClick = onRetryClipboard, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.try_again))
            }
        }
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.close))
        }
    }
}
