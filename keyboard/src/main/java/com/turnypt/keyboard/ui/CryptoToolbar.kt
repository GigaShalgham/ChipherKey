package com.turnypt.keyboard.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.turnypt.keyboard.controller.ImeController

/**
 * The Turnypt action bar rendered above the letter keyboard.
 *
 * Hidden entirely on sensitive fields (passwords) — encryption actions must
 * never be offered there.
 */
@Composable
fun CryptoToolbar(
    state: ImeController.UiState,
    notice: String?,
    onSelectContact: (String) -> Unit,
    onEncrypt: () -> Unit,
    onDecrypt: () -> Unit,
    onLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var dropdownOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Contact selector chip
            Box {
                Row(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp))
                        .clickable { dropdownOpen = true }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val unlocked = state.activeContactId in state.unlockedContactIds
                    Text(
                        text = if (unlocked) "🔓" else "🔒",
                        fontSize = 14.sp,
                    )
                    Text(
                        text = state.activeContactName ?: "No contact",
                        modifier = Modifier.padding(start = 6.dp),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "▾",
                        modifier = Modifier.padding(start = 2.dp),
                        fontSize = 14.sp,
                    )
                }
                DropdownMenu(expanded = dropdownOpen, onDismissRequest = { dropdownOpen = false }) {
                    if (state.contacts.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("Add contacts in the app") },
                            onClick = { dropdownOpen = false },
                        )
                    }
                    state.contacts.forEach { contact ->
                        DropdownMenuItem(
                            text = { Text(contact.displayName) },
                            onClick = {
                                dropdownOpen = false
                                onSelectContact(contact.id.value)
                            },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            if (state.activeContactId in state.unlockedContactIds) {
                Text(
                    text = "lock",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { onLock() }
                        .padding(horizontal = 4.dp),
                )
            }

            Button(
                onClick = onEncrypt,
                enabled = !state.busy && state.activeContactId != null,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(32.dp),
            ) {
                Text("Encrypt", fontSize = 13.sp)
            }
            OutlinedButton(
                onClick = onDecrypt,
                enabled = !state.busy,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(32.dp),
            ) {
                Text("Decrypt", fontSize = 13.sp)
            }
        }

        if (state.busy) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
            )
        }
        AnimatedVisibility(visible = notice != null) {
            Text(
                text = notice ?: "",
                modifier = Modifier.padding(top = 2.dp, start = 4.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
