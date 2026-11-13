package com.turnypt.keyboard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Digits + common symbols page (toggled by the "?123" key). */
@Composable
fun SymbolsKeyboard(
    onText: (String) -> Unit,
    onBackspace: () -> Unit,
    onSpace: () -> Unit,
    onEnter: () -> Unit,
    onBackToLetters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val row1 = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
    val row2 = listOf("@", "#", "$", "%", "&", "-", "+", "(", ")")
    val row3 = listOf("*", "\"", "'", ":", ";", ",", ".", "!", "?")

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SymbolRow(row1, onText)
        SymbolRow(row2, onText)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            KeyCap(
                label = "⌫",
                onClick = onBackspace,
                modifier = Modifier
                    .weight(1.5f)
                    .fillMaxSize(),
            )
            row3.forEach { symbol ->
                KeyCap(
                    label = symbol,
                    onClick = { onText(symbol) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize(),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            KeyCap(
                label = "ABC",
                onClick = onBackToLetters,
                modifier = Modifier
                    .weight(2f)
                    .fillMaxSize(),
            )
            KeyCap(
                label = "space",
                onClick = onSpace,
                modifier = Modifier
                    .weight(5f)
                    .fillMaxSize(),
            )
            KeyCap(
                label = "⏎",
                onClick = onEnter,
                modifier = Modifier
                    .weight(2f)
                    .fillMaxSize(),
            )
        }
    }
}

@Composable
private fun SymbolRow(symbols: List<String>, onText: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        symbols.forEach { symbol ->
            KeyCap(
                label = symbol,
                onClick = { onText(symbol) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
            )
        }
    }
}
