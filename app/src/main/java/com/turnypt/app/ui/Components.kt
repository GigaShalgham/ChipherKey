package com.turnypt.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.turnypt.app.R
import com.turnypt.app.companion.UiNotice
import com.turnypt.app.ui.theme.LocalSemanticColors
import com.turnypt.security.policies.PassphraseAdvisor

/**
 * Shared building blocks — the v0.5 "Calm Vault" component set
 * (docs/DESIGN.md §3.4). One visual language for section cards, status
 * chips, quick-action tiles, result blocks, passphrase inputs, headers,
 * busy rows, notice banners, labeled switch rows and avatars.
 *
 * v0.5 rules: grouped surfaces are TONAL (surfaceContainerLow), never
 * bordered; state is always icon + color, never color alone; interactive
 * targets are at least 48dp (quick tiles 88dp).
 */

/**
 * Titled tonal card group used by every screen.
 *
 * v0.5: `surfaceContainerLow` fill with the medium (16dp) shape replaces
 * the v0.4.2 outlined border — the Material 3 role-system way to express
 * grouping; the optional [icon] leads the title in the primary color.
 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                icon?.let {
                    Icon(
                        it,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

/**
 * Status pill ("Unlocked: 2" / "Contacts: 0").
 *
 * v0.5: a full pill with an optional leading icon. Positive states use
 * the SUCCESS semantic extension (the vault-app "unlocked = green"
 * channel); everything else stays slate `secondaryContainer`. State is
 * readable from the icon alone, never from color alone.
 */
@Composable
fun StatusChip(
    text: String,
    positive: Boolean,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val semantic = LocalSemanticColors.current
    val bg = if (positive) {
        semantic.successContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val fg = if (positive) {
        semantic.onSuccessContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        color = bg,
        shape = RoundedCornerShape(percent = 50),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            icon?.let {
                Icon(it, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = fg,
            )
        }
    }
}

/**
 * Quick-action tile (Home grid): 40dp tonal icon circle over a two-line
 * label, at least 88dp tall.
 *
 * v0.5: the v0.3/v0.4 tile was an OutlinedButton row with an 18dp icon —
 * a 40dp control for the app's four primary actions. Touch target and
 * visual hierarchy now match the action's importance (docs/DESIGN.md
 * §2.6).
 */
@Composable
fun QuickActionTile(
    label: String,
    icon: ImageVector,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = CircleShape,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .padding(9.dp)
                        .size(22.dp),
                )
            }
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * Shared empty-state block — icon + title + body on a quiet tonal
 * surface, centered, with an optional call to action.
 *
 * v0.5: the CTA. An empty state is where new users land first; NN/g's
 * three jobs for it are status, education and a DIRECT pathway
 * (docs/DESIGN.md §2.4) — "Add contact" belongs on the empty Contacts
 * screen, not a dead end.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (actionLabel != null && onAction != null) {
                Spacer(modifier = Modifier.size(4.dp))
                Button(onClick = onAction) {
                    Text(actionLabel, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/**
 * Read-only result block (envelope or plaintext) with copy/clear row.
 *
 * v0.5: the text sits on a `surfaceContainerHigh` inset inside its
 * `surfaceContainerLow` section card — tonal layering instead of nothing,
 * monospace envelopes render in bodySmall.
 */
@Composable
fun ResultBlock(
    text: String,
    monospace: Boolean,
    copied: Boolean,
    onCopy: () -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            SelectionContainer {
                Text(
                    text = text,
                    style = if (monospace) {
                        MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    } else {
                        MaterialTheme.typography.bodyLarge
                    },
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        if (copied) {
            Text(
                stringResourceCompat(R.string.notice_copied),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onCopy, modifier = Modifier.weight(1f)) {
                Text(stringResourceCompat(R.string.action_copy))
            }
            OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) {
                Text(stringResourceCompat(R.string.action_clear))
            }
        }
    }
}

/** Passphrase input + submit row shared by every unlock card.
 *
 * v0.5.2: [allowSuggestion] adds the "Suggest a strong passphrase"
 * affordance right at the password-entry moment (user request) — but only
 * where it is honest: the caller passes it only when THIS side is choosing
 * the passphrase (a locally created era that was never unlocked). An
 * imported era means the peer already picked the passphrase.
 */
@Composable
fun PassphraseInput(
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
                onSubmit(passphrase.toCharArray())
                passphrase = ""
            },
            enabled = passphrase.isNotEmpty() && !busy,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) { Text(buttonLabel) }
    }
}

/**
 * v0.5.2: the strong-passphrase suggestion, AT the password-entry point
 * (user request: "you should offer it when entering the password").
 * Collapsed by default; "Use" fills the field above, "New one" rerolls,
 * "Copy" uses the SENSITIVE clipboard path and never lands in the
 * clipboard history.
 */
@Composable
fun SuggestPassphraseSection(
    onUse: (String) -> Unit,
    onCopySensitive: (String) -> Boolean,
) {
    var visible by remember { mutableStateOf(false) }
    if (!visible) {
        TextButton(onClick = { visible = true }) {
            Text(stringResourceCompat(R.string.passphrase_suggest_action))
        }
        return
    }
    var suggestion by remember { mutableStateOf(PassphraseAdvisor.recommend()) }
    var copied by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SelectionContainer {
            Text(
                suggestion,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            stringResourceCompat(
                R.string.contact_suggestion_bits,
                PassphraseAdvisor.GUARANTEED_BITS,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onUse(suggestion) }) {
                Text(stringResourceCompat(R.string.passphrase_suggestion_use))
            }
            TextButton(onClick = {
                suggestion = PassphraseAdvisor.recommend()
                copied = false
            }) {
                Text(stringResourceCompat(R.string.passphrase_suggestion_new))
            }
            TextButton(onClick = { copied = onCopySensitive(suggestion) }) {
                Text(stringResourceCompat(R.string.action_copy))
            }
            TextButton(onClick = { visible = false }) {
                Text(stringResourceCompat(R.string.contact_suggest_hide))
            }
        }
        if (copied) {
            Text(
                stringResourceCompat(R.string.passphrase_suggestion_copied),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            stringResourceCompat(R.string.passphrase_suggestion_first_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * v0.4.0: live strength estimate under a passphrase field. Display only —
 * it never blocks submission (that stays with KeyPolicy on the crypto
 * path); it teaches what a strong shared passphrase looks like while
 * typing.
 *
 * v0.5: STRONG now renders in the SUCCESS semantic tone (green), FAIR
 * keeps the warm ochre tertiary, WEAK stays error red.
 */
@Composable
fun PassphraseStrengthHint(passphrase: String) {
    if (passphrase.isEmpty()) return
    val semantic = LocalSemanticColors.current
    val strength = remember(passphrase) { PassphraseAdvisor.strength(passphrase) }
    val bits = remember(passphrase) { PassphraseAdvisor.estimateBits(passphrase) }
    val (labelRes, color) = when (strength) {
        PassphraseAdvisor.Strength.WEAK ->
            R.string.passphrase_strength_weak to MaterialTheme.colorScheme.error
        PassphraseAdvisor.Strength.FAIR ->
            R.string.passphrase_strength_fair to MaterialTheme.colorScheme.tertiary
        PassphraseAdvisor.Strength.STRONG ->
            R.string.passphrase_strength_strong to semantic.success
    }
    Text(
        text = stringResourceCompat(
            R.string.passphrase_strength_summary,
            stringResourceCompat(labelRes),
            bits,
        ),
        style = MaterialTheme.typography.bodySmall,
        color = color,
    )
}

/** Screen title + subtitle header. */
@Composable
fun ScreenHeader(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Centered busy row ("Decrypting…"). */
@Composable
fun BusyRow(label: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The four notice tones the UI distinguishes (docs/DESIGN.md §3.4). */
enum class NoticeTone { INFO, SUCCESS, WARNING, ERROR }

/**
 * Inline notice banner for typed notices.
 *
 * v0.4.2 made it a real banner (errorContainer surface); v0.5 adds tones
 * so confirmations (copied, created, unlocked) stop reading as errors.
 * The default stays ERROR — that is what a raw notice has always meant
 * at the existing call sites.
 */
@Composable
fun NoticeBanner(
    notice: UiNotice?,
    tone: NoticeTone = NoticeTone.ERROR,
) {
    if (notice == null) return
    val text = notice.arg?.let { arg ->
        stringResourceCompat(notice.res, arg)
    } ?: stringResourceCompat(notice.res)
    val semantic = LocalSemanticColors.current
    val (bg, fg, icon) = when (tone) {
        NoticeTone.INFO -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            TurnyptIcons.Info,
        )
        NoticeTone.SUCCESS -> Triple(
            semantic.successContainer,
            semantic.onSuccessContainer,
            TurnyptIcons.CheckCircle,
        )
        NoticeTone.WARNING -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            TurnyptIcons.Warning,
        )
        NoticeTone.ERROR -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            TurnyptIcons.Warning,
        )
    }
    Surface(
        color = bg,
        contentColor = fg,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * v0.5: a labeled settings switch row — title, optional summary, and the
 * switch as the trailing control. The v0.3-v0.4 Settings showed the
 * literal text "On"/"Off" next to the switch, which read as a status
 * display instead of the feature's name; a control must be labeled by
 * what it controls.
 */
@Composable
fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

/**
 * v0.5: contact avatar — the contact's initial on a tonal circle, green
 * when unlocked (the SUCCESS channel), quiet grey when locked.
 */
@Composable
fun ContactAvatar(
    name: String,
    unlocked: Boolean,
    modifier: Modifier = Modifier,
) {
    val semantic = LocalSemanticColors.current
    val bg = if (unlocked) semantic.successContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (unlocked) semantic.onSuccessContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        color = bg,
        shape = CircleShape,
        modifier = modifier.size(40.dp),
    ) {
        Text(
            text = name.trim().take(1).uppercase(),
            style = MaterialTheme.typography.titleMedium,
            color = fg,
            modifier = Modifier.wrapContentSize(Alignment.Center),
        )
    }
}

/** Thin indeterminate progress line shown while busy. */
@Composable
fun BusyLine(visible: Boolean) {
    if (visible) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

/**
 * v0.5.2: the clipboard HISTORY dialog (user request: "the list of things
 * you copied and had copied before") — replaces the v0.5.1 single-clip
 * preview. Tap an entry to paste it into the field; the per-row ✕ deletes
 * it; "Clear history" wipes the list. The current clip is recorded when
 * the dialog opens, so it always sits on top.
 */
@Composable
fun ClipboardHistoryDialog(
    entries: List<com.turnypt.data.repository.ClipboardHistoryEntry>,
    onUse: (com.turnypt.data.repository.ClipboardHistoryEntry) -> Unit,
    onDelete: (Long) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResourceCompat(R.string.clipboard_history_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (entries.isEmpty()) {
                    Text(
                        stringResourceCompat(R.string.clipboard_history_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 340.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(
                            count = entries.size,
                            key = { entries[it].id },
                        ) { index ->
                            ClipboardHistoryRow(
                                entry = entries[index],
                                onUse = { onUse(entries[index]) },
                                onDelete = { onDelete(entries[index].id) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (entries.isNotEmpty()) {
                androidx.compose.material3.TextButton(onClick = onClearAll) {
                    Text(
                        stringResourceCompat(R.string.action_clear_history),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResourceCompat(R.string.close))
            }
        },
    )
}

/** One history row: bounded preview, age + CK1 + truncated meta, ✕ delete. */
@Composable
private fun ClipboardHistoryRow(
    entry: com.turnypt.data.repository.ClipboardHistoryEntry,
    onUse: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        onClick = onUse,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 2.dp),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = entry.text,
                    style = if (entry.isEnvelope) {
                        MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = clipboardAgeLabel(entry.capturedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (entry.isEnvelope) {
                        Text(
                            stringResourceCompat(R.string.clipboard_history_ck1_tag),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (entry.truncated) {
                        Text(
                            stringResourceCompat(R.string.clipboard_history_truncated),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
            androidx.compose.material3.IconButton(onClick = onDelete) {
                Icon(
                    TurnyptIcons.Close,
                    contentDescription = stringResourceCompat(R.string.clipboard_history_delete_entry),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** "just now" / "3 min ago" / "2 h ago" / "5 d ago" for a history row. */
@Composable
private fun clipboardAgeLabel(capturedAt: Long): String {
    val now = System.currentTimeMillis()
    val minutes = (now - capturedAt) / 60_000L
    return when {
        minutes < 1 -> stringResourceCompat(R.string.clipboard_history_just_now)
        minutes < 60 -> stringResourceCompat(R.string.clipboard_history_minutes_ago, minutes)
        minutes < 60 * 24 -> stringResourceCompat(R.string.clipboard_history_hours_ago, minutes / 60)
        else -> stringResourceCompat(R.string.clipboard_history_days_ago, minutes / (60 * 24))
    }
}

/** stringResource indirection so the private helpers above stay short. */
@Composable
private fun stringResourceCompat(res: Int, vararg args: Any): String =
    androidx.compose.ui.res.stringResource(res, *args)
