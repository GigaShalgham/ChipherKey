package org.cipherkey.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.cipherkey.app.MainViewModel
import org.cipherkey.app.R
import org.cipherkey.app.companion.DestinationRoute
import org.cipherkey.app.companion.EXTRA_FROM_CLIPBOARD

/**
 * Home (v0.5, "Calm Vault"): brand header, one status card, one
 * quick-actions grid — nothing else on screen. The v0.4.1 removal of the
 * how-it-works walls stays; the v0.5 pass restyles what remains (tonal
 * grouping, success-tone state chips, 88dp action tiles — see
 * docs/DESIGN.md §3.5).
 */
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onNavigate: (String) -> Unit,
) {
    val contactCount by viewModel.contactCount.collectAsStateWithLifecycle()
    val unlockedCount by viewModel.unlockedCount.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Deep-scan F3: entering the Home tab re-reads the counters (LaunchedEffect
    // alone would miss a resume after locking a session in the tools tabs,
    // since saveState/restoreState keeps the composable alive).
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Brand header: shield mark + name + tagline.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = CircleShape,
            ) {
                Icon(
                    CipherKeyIcons.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .padding(10.dp)
                        .size(24.dp),
                )
            }
            Column {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = stringResource(R.string.tagline),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionCard(
            title = stringResource(R.string.home_status_title),
            icon = CipherKeyIcons.Shield,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusChip(
                    text = stringResource(R.string.home_status_contacts, contactCount),
                    positive = contactCount > 0,
                    icon = CipherKeyIcons.People,
                )
                StatusChip(
                    text = if (unlockedCount > 0) {
                        stringResource(R.string.home_status_unlocked, unlockedCount)
                    } else {
                        stringResource(R.string.home_status_locked)
                    },
                    positive = unlockedCount > 0,
                    icon = if (unlockedCount > 0) {
                        CipherKeyIcons.LockOpen
                    } else {
                        CipherKeyIcons.Lock
                    },
                )
            }
        }

        Text(
            text = stringResource(R.string.home_quick_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickActionTile(
                    label = stringResource(R.string.home_quick_encrypt_text),
                    icon = CipherKeyIcons.Lock,
                    onClick = { onNavigate(DestinationRoute.ENCRYPT) },
                    modifier = Modifier.weight(1f),
                )
                QuickActionTile(
                    label = stringResource(R.string.home_quick_decrypt_text),
                    icon = CipherKeyIcons.LockOpen,
                    onClick = { onNavigate(DestinationRoute.DECRYPT) },
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickActionTile(
                    label = stringResource(R.string.home_quick_encrypt_clip),
                    icon = CipherKeyIcons.Copy,
                    onClick = {
                        context.startActivity(
                            Intent(
                                context,
                                org.cipherkey.app.companion.EncryptActivity::class.java,
                            ).putExtra(EXTRA_FROM_CLIPBOARD, true),
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
                QuickActionTile(
                    label = stringResource(R.string.home_quick_decrypt_clip),
                    icon = CipherKeyIcons.Paste,
                    onClick = {
                        context.startActivity(
                            Intent(
                                context,
                                org.cipherkey.app.companion.DecryptActivity::class.java,
                            ).putExtra(EXTRA_FROM_CLIPBOARD, true),
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            // v0.6.0: image encryption takes its place in Quick Access —
            // not a side door (docs/IMAGE-CRYPTO.md §5).
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickActionTile(
                    label = stringResource(R.string.home_quick_encrypt_image),
                    icon = CipherKeyIcons.Image,
                    onClick = { onNavigate(DestinationRoute.ENCRYPT_IMAGE) },
                    modifier = Modifier.weight(1f),
                )
                QuickActionTile(
                    label = stringResource(R.string.home_quick_decrypt_image),
                    icon = CipherKeyIcons.FileDoc,
                    onClick = { onNavigate(DestinationRoute.DECRYPT_IMAGE) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}
