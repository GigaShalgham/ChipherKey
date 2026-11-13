package org.cipherkey.app.companion

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.cipherkey.app.R
import org.cipherkey.app.ToolsViewModel
import org.cipherkey.app.security.BiometricKeyStore
import org.cipherkey.app.security.BiometricPromptHost
import org.cipherkey.app.security.rememberBiometricPromptHelper
import org.cipherkey.app.ui.CipherKeyIcons
import org.cipherkey.app.ui.CipherChip
import org.cipherkey.app.ui.ContactPicker
import org.cipherkey.app.ui.DecryptedImageCard
import org.cipherkey.app.ui.EncryptedFileCard
import org.cipherkey.app.ui.ImageProgressLine
import org.cipherkey.app.ui.ImageShare
import org.cipherkey.app.ui.NoticeBanner
import org.cipherkey.app.ui.PickedImageCard
import org.cipherkey.app.ui.PassphraseInput
import org.cipherkey.app.ui.ScreenHeader
import org.cipherkey.app.ui.BusyLine
import org.cipherkey.app.ui.CipherPreferenceDialog
import org.cipherkey.app.ui.theme.CipherKeyTheme
import org.cipherkey.core.crypto.Ckf1
import org.cipherkey.data.preferences.CipherPreference

/**
 * v0.6.0: the image companion — the sharesheet/file-manager entry into the
 * SAME image flows the in-app tabs host (one ToolsViewModel, one set of
 * rules). Entry modes:
 *
 *  - ACTION_SEND with an image MIME type → encrypt the shared image for a
 *    contact;
 *  - ACTION_SEND or ACTION_VIEW with the x-cipherkey MIME type → decrypt
 *    a .cki file;
 *  - no payload (floating assistant) → in-sheet image picker.
 *
 * Companion discipline as everywhere: FLAG_SECURE (no screenshots/recents
 * of decrypted content), noHistory + excludeFromRecents in the manifest,
 * and the passphrase field is never persisted in view state.
 */
/** What the sheet is doing (from the launch intent). */
private enum class SheetMode { PICKER_ENCRYPT, ENCRYPT_SHARED, DECRYPT_SHARED }

@AndroidEntryPoint
class ImageCipherActivity : FragmentActivity() {

    private val viewModel: ToolsViewModel by viewModels()

    @Inject
    lateinit var biometrics: BiometricKeyStore

    /** What the sheet is doing (from the launch intent). */
    private var sheetMode = SheetMode.PICKER_ENCRYPT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Same as every companion surface: decrypted content must never
        // reach screenshots, screen recording or the recents preview.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        if (savedInstanceState == null) {
            dispatchIntent(intent)
        }
        setContent {
            CipherKeyTheme {
                ImageCipherSheet(
                    viewModel = viewModel,
                    biometrics = biometrics,
                    mode = sheetMode,
                    onClose = { finish() },
                )
            }
        }
    }

    private fun dispatchIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val shared = IntentCompat.getParcelableExtra(
                    intent,
                    Intent.EXTRA_STREAM,
                    Uri::class.java,
                )
                if (shared == null) {
                    sheetMode = SheetMode.PICKER_ENCRYPT
                    return
                }
                val displayName = ImageFiles.queryDisplayName(this, shared)
                val size = ImageFiles.querySize(this, shared)
                if (ImageFiles.isImageMime(intent.type) ||
                    displayName?.endsWith(".${Ckf1.FILE_EXTENSION}") != true
                ) {
                    // An image (or anything the user wants encrypted): the
                    // encrypt flow. CKF1 is file-type agnostic by design.
                    sheetMode = SheetMode.ENCRYPT_SHARED
                    viewModel.pickImage(shared, displayName, size)
                } else {
                    sheetMode = SheetMode.DECRYPT_SHARED
                    viewModel.decryptImageFrom(shared, displayName)
                }
            }
            Intent.ACTION_VIEW -> {
                val data = intent.data
                if (data != null) {
                    sheetMode = SheetMode.DECRYPT_SHARED
                    viewModel.decryptImageFrom(data, ImageFiles.queryDisplayName(this, data))
                }
            }
            // Launched from the floating assistant / Home: pick inside.
            else -> sheetMode = SheetMode.PICKER_ENCRYPT
        }
    }
}

@Composable
private fun ImageCipherSheet(
    viewModel: ToolsViewModel,
    biometrics: BiometricKeyStore,
    mode: SheetMode,
    onClose: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val promptHelper = rememberBiometricPromptHelper(biometrics)
    var cipherDialogOpen by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(Unit) { viewModel.refresh() }
    BiometricPromptHost(
        helper = promptHelper,
        request = state.biometricPrompt,
        loadRememberKey = viewModel::consumeRememberKey,
        onUnlock = viewModel::onBiometricUnlockResult,
        onRememberDone = viewModel::onRememberDone,
    )

    val pickImage = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.pickImage(
                uri,
                ImageFiles.queryDisplayName(context, uri),
                ImageFiles.querySize(context, uri),
            )
        }
    }
    val pickCki = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.decryptImageFrom(uri, ImageFiles.queryDisplayName(context, uri))
        }
    }
    val createCki = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(Ckf1.MIME_TYPE),
    ) { uri ->
        if (uri != null) viewModel.encryptImageTo(uri)
    }
    val saveImage = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/*"),
    ) { uri ->
        if (uri != null) viewModel.saveDecryptedImageTo(uri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(
                    if (mode == SheetMode.DECRYPT_SHARED) {
                        R.string.image_sheet_title_decrypt
                    } else {
                        R.string.image_sheet_title_encrypt
                    },
                ),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(CipherKeyIcons.Close, contentDescription = stringResource(R.string.close))
            }
        }

        BusyLine(visible = state.busy)

        if (state.contacts.isEmpty() && mode != SheetMode.DECRYPT_SHARED) {
            org.cipherkey.app.ui.EmptyState(
                icon = CipherKeyIcons.People,
                title = stringResource(R.string.contacts_empty_title),
                body = stringResource(R.string.notice_no_contacts),
                actionLabel = stringResource(R.string.assistant_open_app),
                onAction = onClose,
            )
        } else if (mode == SheetMode.DECRYPT_SHARED) {
            // ------------------------------------------------------ DECRYPT
            SectionlessBody {
                ImageProgressLine(state)
                if (state.imageDecryptResult == null && !state.imageBusy) {
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
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.image_pick_cki_action)) }
                }
                DecryptedImageCard(
                    state = state,
                    onSave = {
                        state.imageDecryptResult?.let { saveImage.launch(it.suggestedName) }
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
        } else {
            // ------------------------------------------------------ ENCRYPT
            if (state.pickedImage != null) {
                PickedImageCard(
                    displayName = state.pickedImage!!.displayName,
                    sizeBytes = state.pickedImage!!.sizeBytes,
                    busy = state.imageBusy,
                    onReplace = { pickImage.launch(arrayOf("image/*")) },
                )
            } else {
                Text(
                    stringResource(R.string.image_pick_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { pickImage.launch(arrayOf("image/*")) },
                    enabled = !state.imageBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.image_sheet_pick_cta)) }
            }
            ImageProgressLine(state)

            ContactPicker(
                contacts = state.contacts,
                selectedContactId = state.selectedContactId,
                unlocked = state.unlocked,
                onContactSelected = viewModel::selectContact,
            )

            if (state.selectedContact != null && state.selectedContactId !in state.unlocked) {
                Text(
                    stringResource(R.string.locked_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PassphraseInput(
                    label = stringResource(R.string.passphrase_label),
                    buttonLabel = stringResource(R.string.unlock_encrypt_action),
                    busy = state.busy,
                    onSubmit = viewModel::unlockAndContinue,
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
            } else if (state.pickedImage != null) {
                CipherChip(
                    preference = state.cipherPreference,
                    resolvedName = state.resolvedCipherName,
                    onClick = { cipherDialogOpen = true },
                )
                Button(
                    onClick = {
                        createCki.launch(
                            ImageFiles.suggestedEncryptedName(state.pickedImage?.displayName),
                        )
                    },
                    enabled = !state.busy && state.pickedImage != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.image_encrypt_action)) }
            }

            EncryptedFileCard(
                state = state,
                onShare = {
                    state.imageEncryptResult?.let { result ->
                        ImageShare.uri(
                            context,
                            Uri.parse(result.outputUriString),
                            Ckf1.MIME_TYPE,
                            context.getString(R.string.image_share_action),
                        )
                    }
                },
                onClear = viewModel::clearPickedImage,
            )
        }

        NoticeBanner(state.notice)

        if (cipherDialogOpen) {
            CipherPreferenceDialog(
                current = state.cipherPreference ?: CipherPreference.AUTO,
                autoResolvedName = state.resolvedCipherName,
                onSelect = { preference -> viewModel.setPreferredCipher(preference) },
                onDismiss = { cipherDialogOpen = false },
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

/** Plain column wrapper (the sheet has no SectionCard of its own). */
@Composable
private fun SectionlessBody(content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
}
