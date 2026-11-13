package com.turnypt.keyboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A physical-ish key on the base keyboard. */
public enum class KeyAction { Text, Shift, Backspace, Space, Enter, SymbolToggle }

public data class Key(
    val label: String,
    val action: KeyAction,
    val text: String = label,
    val weight: Float = 1f,
)

internal val Row1 = listOf(
    "q", "w", "e", "r", "t", "y", "u", "i", "o", "p",
).map { Key(it, KeyAction.Text, it) }

internal val Row2 = listOf(
    "a", "s", "d", "f", "g", "h", "j", "k", "l",
).map { Key(it, KeyAction.Text, it) }

internal val Row3First = listOf("z", "x", "c", "v", "b", "n", "m").map { Key(it, KeyAction.Text, it) }

internal val Row4 = listOf(
    Key("?123", KeyAction.SymbolToggle),
    Key(",", KeyAction.Text, ","),
    Key(" ", KeyAction.Space, " ", weight = 5f),
    Key(".", KeyAction.Text, "."),
    Key("⏎", KeyAction.Enter),
)

/**
 * Base (normal-mode) keyboard surface: three letter rows plus a bottom row.
 *
 * The Turnypt toolbar and encryption UI live above this surface and are
 * composed by [com.turnypt.keyboard.ui.KeyboardScreen] once wired to the
 * crypto core.
 */
@Composable
fun BaseKeyboard(
    shiftOn: Boolean,
    onKey: (Key) -> Unit,
    modifier: Modifier = Modifier,
) {
    val keys3 = buildList {
        add(Key(if (shiftOn) "⇧" else "⇧", KeyAction.Shift))
        addAll(Row3First)
        add(Key("⌫", KeyAction.Backspace))
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        KeyboardRow(Row1, shiftOn, onKey)
        KeyboardRow(Row2, shiftOn, onKey)
        KeyboardRow(keys3, shiftOn, onKey)
        KeyboardRow(Row4, shiftOn, onKey)
    }
}

@Composable
private fun KeyboardRow(keys: List<Key>, shiftOn: Boolean, onKey: (Key) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        keys.forEach { key ->
            KeyboardKey(
                key = key,
                highlighted = key.action == KeyAction.Shift && shiftOn,
                modifier = Modifier
                    .weight(key.weight)
                    .fillMaxSize(),
                onKey = onKey,
            )
        }
    }
}

@Composable
private fun KeyboardKey(
    key: Key,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
    onKey: (Key) -> Unit,
) {
    val background = when {
        highlighted -> MaterialTheme.colorScheme.primary
        key.action == KeyAction.Text || key.action == KeyAction.Space -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    }
    Box(
        modifier = modifier
            .background(background, RoundedCornerShape(8.dp))
            .clickable { onKey(key) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = key.label.trim().ifEmpty { "space" },
            color = if (highlighted) Color.White else MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
