package org.cipherkey.app.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import org.cipherkey.app.R
import org.cipherkey.app.ToolsUiState
import org.cipherkey.app.companion.ImageFiles
import org.cipherkey.data.preferences.CipherPreference

/**
 * v0.6.0: the shared visual language of the image flows (docs/DESIGN.md —
 * same tonal cards, chips and buttons as every other screen). Used by the
 * Encrypt / Decrypt tabs AND the companion image activity, so the feature
 * looks native everywhere it is reachable from.
 */
object ImageShare {

    /** Share any URI the app can serve (SAF outputs via clipData grant). */
    fun uri(context: Context, uri: Uri, mime: String, title: String? = null) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("CipherKey file", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val launch = title?.let { Intent.createChooser(send, it) } ?: send
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(launch) }
    }

    /** Share the decrypted cache file through the app's FileProvider. */
    fun cacheFile(context: Context, cacheFileUriString: String, mime: String, title: String?) {
        val file = File(java.net.URI(cacheFileUriString))
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull() ?: return
        uri(context, uri, mime, title)
    }
}

/** The Text | Image segment switch at the top of the Encrypt/Decrypt tabs. */
@Composable
fun ToolSegmentRow(
    imageSelected: Boolean,
    onTextSelected: () -> Unit,
    onImageSelected: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = !imageSelected,
            onClick = onTextSelected,
            label = { Text(stringResource(R.string.segment_text)) },
            leadingIcon = {
                Icon(CipherKeyIcons.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
            },
        )
        FilterChip(
            selected = imageSelected,
            onClick = onImageSelected,
            label = { Text(stringResource(R.string.segment_image)) },
            leadingIcon = {
                Icon(CipherKeyIcons.Image, contentDescription = null, modifier = Modifier.size(16.dp))
            },
        )
    }
}

/**
 * The cipher chip shown at the point of use: "Automatic → AES-256-GCM".
 * Tapping opens the same preference dialog Settings shows — one setting,
 * every surface.
 */
@Composable
fun CipherChip(
    preference: CipherPreference?,
    resolvedName: String?,
    onClick: () -> Unit,
) {
    val resolved = resolvedName ?: return
    val label = when (preference) {
        CipherPreference.AUTO -> stringResource(R.string.cipher_auto_resolved, resolved)
        else -> stringResource(R.string.cipher_chip_label, resolved)
    }
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(percent = 50),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(start = 12.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Icon(
                CipherKeyIcons.Key,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(14.dp),
            )
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/** Picked image line: name + size + replace affordance. */
@Composable
fun PickedImageCard(
    displayName: String,
    sizeBytes: Long?,
    busy: Boolean,
    onReplace: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.heightIn(min = 56.dp),
        ) {
            Icon(
                CipherKeyIcons.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
                Text(
                    sizeBytes?.let { ImageFiles.formatSize(it) } ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!busy) {
                TextButton(onClick = onReplace) {
                    Text(stringResource(R.string.image_replace_action))
                }
            }
        }
    }
}

/** "Encrypting… 1.2 MB / 4.0 MB" progress line for the image flows. */
@Composable
fun ImageProgressLine(state: ToolsUiState) {
    if (!state.imageBusy) return
    val done = ImageFiles.formatSize(state.imageProgressBytes)
    val text = state.imageProgressTotal?.let { total ->
        "$done / ${ImageFiles.formatSize(total)}"
    } ?: stringResource(R.string.image_encrypt_progress, done)
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** Finished image encryption: saved file + share + clear. */
@Composable
fun EncryptedFileCard(
    state: ToolsUiState,
    onShare: () -> Unit,
    onClear: () -> Unit,
) {
    val result = state.imageEncryptResult ?: return
    SectionCard(
        title = stringResource(
            R.string.image_result_title,
            state.resultContactName ?: state.selectedContact?.displayName ?: "",
        ),
        icon = CipherKeyIcons.FileDoc,
    ) {
        Text(
            stringResource(R.string.image_result_saved_as, result.displayName),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.image_result_details, result.sizeText, result.algorithmName),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onShare, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.image_share_action))
            }
            OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.action_clear))
            }
        }
    }
}

/** Finished image decryption: preview (or file line) + save/share + clear. */
@Composable
fun DecryptedImageCard(
    state: ToolsUiState,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onClear: () -> Unit,
) {
    val result = state.imageDecryptResult ?: return
    SectionCard(
        title = stringResource(R.string.image_result_decrypt_title, result.contactName),
        icon = CipherKeyIcons.Image,
    ) {
        if (result.preview != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.small,
            ) {
                Image(
                    bitmap = result.preview,
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            Text(
                stringResource(R.string.image_no_preview, result.sizeText),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            stringResource(R.string.image_result_details, result.sizeText, result.algorithmName),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.imageSavedAfterDecrypt) {
            Text(
                stringResource(R.string.image_saved_ok),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onSave, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.image_save_action))
            }
            OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.image_share_decrypted_action))
            }
        }
        TextButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_clear))
        }
    }
}

/**
 * The cipher preference dialog — three honest options, each with a short
 * practical description, plus an expandable "which one should I choose?"
 * explainer. Shared by Settings and the cipher chip at the point of use.
 */
@Composable
fun CipherPreferenceDialog(
    current: CipherPreference,
    autoResolvedName: String?,
    onSelect: (CipherPreference) -> Unit,
    onDismiss: () -> Unit,
) {
    var explain by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cipher_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CipherOptionRow(
                    selected = current == CipherPreference.AUTO,
                    title = stringResource(R.string.cipher_pref_auto),
                    description = stringResource(R.string.cipher_pref_auto_desc),
                    extra = autoResolvedName?.let {
                        stringResource(R.string.cipher_auto_resolved, it)
                    },
                    onClick = { onSelect(CipherPreference.AUTO) },
                )
                CipherOptionRow(
                    selected = current == CipherPreference.AES_256_GCM,
                    title = stringResource(R.string.cipher_pref_aes),
                    description = stringResource(R.string.cipher_pref_aes_desc),
                    extra = null,
                    onClick = { onSelect(CipherPreference.AES_256_GCM) },
                )
                CipherOptionRow(
                    selected = current == CipherPreference.CHACHA20_POLY1305,
                    title = stringResource(R.string.cipher_pref_chacha),
                    description = stringResource(R.string.cipher_pref_chacha_desc),
                    extra = null,
                    onClick = { onSelect(CipherPreference.CHACHA20_POLY1305) },
                )
                TextButton(onClick = { explain = !explain }) {
                    Text(stringResource(R.string.cipher_explain_action))
                }
                if (explain) {
                    Text(
                        stringResource(R.string.cipher_explain_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

@Composable
private fun CipherOptionRow(
    selected: Boolean,
    title: String,
    description: String,
    extra: String?,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (extra != null) {
                Text(
                    extra,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
