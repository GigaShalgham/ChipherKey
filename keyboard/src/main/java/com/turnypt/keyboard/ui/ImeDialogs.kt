package com.turnypt.keyboard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.turnypt.keyboard.controller.ImeController

/** In-IME passphrase prompt. The buffer is wiped after submission. */
@Composable
fun PassphraseDialog(
    prompt: ImeController.PassphrasePrompt,
    busy: Boolean,
    onSubmit: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    val reasonText = when (prompt.reason) {
        ImeController.PromptReason.SEND ->
            "Enter the shared passphrase to encrypt for ${prompt.contactName}."
        ImeController.PromptReason.RECEIVE ->
            "Enter the shared passphrase to decrypt this message from ${prompt.contactName}."
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Unlock ${prompt.contactName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(reasonText, fontSize = 13.sp)
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    label = { Text("Passphrase") },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val chars = passphrase.toCharArray()
                    passphrase = ""
                    onSubmit(chars)
                },
                enabled = !busy && passphrase.isNotEmpty(),
            ) { Text("Unlock") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
        },
    )
}

/** Decrypted-message preview: shown ONLY after successful authentication. */
@Composable
fun DecryptPreviewPanel(
    preview: ImeController.DecryptedPreview,
    onInsert: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "From ${preview.contactName}",
                fontSize = 13.sp,
                style = MaterialTheme.typography.titleSmall,
            )
            SelectionContainer {
                Text(
                    text = preview.plaintext,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .heightIn(max = 160.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
            Text(
                text = "Decrypted message — handle as confidential.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text("Discard") }
                TextButton(onClick = onInsert) { Text("Insert") }
            }
        }
    }
}
