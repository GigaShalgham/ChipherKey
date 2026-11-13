package com.turnypt.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.turnypt.app.R
import com.turnypt.app.ui.theme.LocalSemanticColors
import com.turnypt.core.common.ContactId
import com.turnypt.data.repository.Contact

/**
 * Contact picker dropdown (v0.3.0) shared by the in-app Encrypt tab and any
 * flow that must choose a recipient. Shows lock state per contact.
 *
 * v0.5: per-item lock state is a leading ICON (green open lock / slate
 * closed lock) instead of a prepended word — state that reads at a glance
 * and does not translate. (docs/DESIGN.md §3.4)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactPicker(
    contacts: List<Contact>,
    selectedContactId: ContactId?,
    unlocked: Set<ContactId>,
    onContactSelected: (ContactId) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = contacts.firstOrNull { it.id == selectedContactId }
    val semantic = LocalSemanticColors.current

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        TextField(
            value = selected?.displayName ?: stringResource(R.string.contact_choose),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.encrypt_contact_label)) },
            leadingIcon = {
                Icon(
                    imageVector = if (selected != null && selected.id in unlocked) {
                        TurnyptIcons.LockOpen
                    } else {
                        TurnyptIcons.Lock
                    },
                    contentDescription = null,
                    tint = if (selected != null && selected.id in unlocked) {
                        semantic.success
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    modifier = Modifier.size(18.dp),
                )
            },
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
                val isUnlocked = contact.id in unlocked
                DropdownMenuItem(
                    text = { Text(contact.displayName) },
                    leadingIcon = {
                        Icon(
                            imageVector = if (isUnlocked) {
                                TurnyptIcons.LockOpen
                            } else {
                                TurnyptIcons.Lock
                            },
                            contentDescription = stringResource(
                                if (isUnlocked) R.string.contact_state_unlocked
                                else R.string.contact_state_locked,
                            ),
                            tint = if (isUnlocked) {
                                semantic.success
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                            modifier = Modifier.size(18.dp),
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
