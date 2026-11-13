package com.turnypt.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collectLatest
import com.turnypt.app.BuildConfig
import com.turnypt.app.ContactsViewModel
import com.turnypt.app.R
import com.turnypt.app.SettingsEvent
import com.turnypt.app.SettingsViewModel
import com.turnypt.app.security.BiometricDiagnostics
import com.turnypt.app.security.BiometricGate
import com.turnypt.app.companion.UiNotice
import com.turnypt.core.crypto.CipherAlgorithm
import com.turnypt.data.preferences.CipherPreference
import com.turnypt.security.policies.Argon2Profile
import com.turnypt.security.policies.KeyPolicy

/**
 * Settings (v0.5, "Calm Vault"): four titled sections — Features,
 * Session & security, Key derivation, About — with labeled switch rows and
 * a compact "Lock all sessions now" button. Every row still states its
 * trade-off; strings come from resources.
 *
 * v0.5.1: Features lead the screen and the session-timeout card shrank to
 * a chip row (user feedback: the timeout tuner is not an everyday control,
 * so it lost its pinned top slot and its seven radio rows).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    contactsViewModel: ContactsViewModel,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val biometricGate by viewModel.biometricGate.collectAsStateWithLifecycle()
    val overlayGranted by viewModel.overlayGranted.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Deep-scan F2: refresh on every resume — returning from the system
    // overlay-permission screen with the toggle on must start the service.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    // One-shot events: open the overlay-permission screen when the
    // assistant was enabled but the permission is missing.
    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                SettingsEvent.RequestOverlayPermission -> {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineSmall,
        )

        // ------------------------------------------------------- features
        // v0.5.1: Features FIRST — the everyday toggles lead the screen;
        // the once-a-year session-timeout tuning moved below and shrank to a
        // compact chip row (user feedback: "lock session time is not very
        // necessary so unpin it from top and make it smaller").

        SectionCard(
            title = stringResource(R.string.settings_features_section),
            icon = TurnyptIcons.Shield,
        ) {
            // --------------------------------------- fingerprint unlock

            // v0.4.1 CI fix: a local copy enables the null/AVAILABLE checks —
            // smart casts are impossible on delegated properties.
            val gate = biometricGate
            if (gate != null && gate != BiometricGate.AVAILABLE) {
                // v0.4.1: the honest reason per failure — the v0.4.0 row
                // guessed "No fingerprint enrolled" for every case.
                NoticeBanner(
                    UiNotice(BiometricDiagnostics.reasonRes(gate)),
                    NoticeTone.WARNING,
                )
            }
            SettingSwitchRow(
                title = stringResource(R.string.settings_biometric_title),
                subtitle = stringResource(R.string.settings_biometric_summary),
                checked = settings.biometricUnlockEnabled && gate == BiometricGate.AVAILABLE,
                enabled = gate == BiometricGate.AVAILABLE,
                onCheckedChange = viewModel::setBiometricEnabled,
            )

            // --------------------------------------- floating assistant

            if (!overlayGranted) {
                NoticeBanner(
                    UiNotice(R.string.settings_assistant_needs_overlay),
                    NoticeTone.INFO,
                )
            }
            SettingSwitchRow(
                title = stringResource(R.string.settings_assistant_title),
                subtitle = stringResource(R.string.settings_assistant_summary),
                checked = settings.assistantEnabled,
                enabled = true,
                onCheckedChange = viewModel::setAssistantEnabled,
            )
            if (settings.assistantEnabled && !overlayGranted) {
                TextButton(onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                }) { Text(stringResource(R.string.settings_assistant_open_settings)) }
            }

            // --------------------------------------- clipboard history (v0.5.2)

            SettingSwitchRow(
                title = stringResource(R.string.settings_clipboard_history_title),
                subtitle = stringResource(R.string.settings_clipboard_history_summary),
                checked = settings.clipboardHistoryEnabled,
                enabled = true,
                onCheckedChange = viewModel::setClipboardHistoryEnabled,
            )
            if (settings.clipboardHistoryEnabled) {
                TextButton(onClick = viewModel::clearClipboardHistory) {
                    Text(
                        stringResource(R.string.action_clear_history),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        // ------------------------------------------------ session & security
        // v0.5.1: compact — one summary line, a single chip row for the
        // timeout (was seven 40dp radio rows) and a small wrap-content lock
        // button (was a full-width 48dp error-toned button).

        SectionCard(
            title = stringResource(R.string.settings_unlock_section),
            icon = TurnyptIcons.Timer,
        ) {
            Text(
                stringResource(R.string.settings_timeout_title),
                style = MaterialTheme.typography.titleSmall,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                KeyPolicy.TIMEOUT_CHOICES_MILLIS.forEach { choice ->
                    FilterChip(
                        selected = settings.sessionTimeoutMillis == choice,
                        onClick = { viewModel.setSessionTimeoutMillis(choice) },
                        label = {
                            Text(
                                if (choice >= 60 * 60_000L) {
                                    stringResource(
                                        R.string.option_hours_short,
                                        (choice / (60 * 60_000L)).toInt(),
                                    )
                                } else {
                                    stringResource(
                                        R.string.option_minutes_short,
                                        (choice / 60_000L).toInt(),
                                    )
                                },
                            )
                        },
                    )
                }
            }
            Text(
                stringResource(R.string.settings_timeout_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { contactsViewModel.lockAll() },
                modifier = Modifier.heightIn(min = 40.dp),
            ) { Text(stringResource(R.string.lock_now)) }

            // -------------------------------------------- cipher (v0.6.0)
            Text(
                stringResource(R.string.settings_cipher_title),
                style = MaterialTheme.typography.titleSmall,
            )
            val resolvedCipher by viewModel.resolvedCipher.collectAsStateWithLifecycle()
            val cipherPreferenceLabel = when (settings.preferredCipher) {
                CipherPreference.AUTO -> stringResource(R.string.cipher_pref_auto)
                CipherPreference.AES_256_GCM -> stringResource(R.string.cipher_pref_aes)
                CipherPreference.CHACHA20_POLY1305 -> stringResource(R.string.cipher_pref_chacha)
            }
            var cipherDialogOpen by remember { mutableStateOf(false) }
            Surface(
                onClick = { cipherDialogOpen = true },
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        cipherPreferenceLabel,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.settings_cipher_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (cipherDialogOpen) {
                CipherPreferenceDialog(
                    current = settings.preferredCipher,
                    autoResolvedName = resolvedCipher?.let { algorithm ->
                        if (algorithm == CipherAlgorithm.AES_256_GCM) {
                            stringResource(R.string.cipher_pref_aes)
                        } else {
                            stringResource(R.string.cipher_pref_chacha)
                        }
                    },
                    onSelect = viewModel::setPreferredCipher,
                    onDismiss = { cipherDialogOpen = false },
                )
            }
        }

        // ------------------------------------------------ KDF strength

        SectionCard(
            title = stringResource(R.string.settings_kdf_title),
            icon = TurnyptIcons.Key,
        ) {
            Text(
                stringResource(R.string.settings_kdf_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Argon2Profile.entries.forEach { profile ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp),
                ) {
                    RadioButton(
                        selected = settings.argon2Profile == profile,
                        onClick = { viewModel.setArgon2Profile(profile) },
                    )
                    Column {
                        Text(
                            profile.name.lowercase().replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(
                                R.string.kdf_profile_desc,
                                profile.params.memoryKib / 1024,
                                profile.params.iterations,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // ------------------------------------------------ about
        SectionCard(
            title = stringResource(R.string.settings_about_title),
            icon = TurnyptIcons.Info,
        ) {
            // Deep-scan finding 7: the version comes from the build itself.
            Text(
                stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.settings_about_line2),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.settings_about_line3),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
