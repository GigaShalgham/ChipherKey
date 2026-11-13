package com.turnypt.keyboard.input

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.turnypt.core.messages.Ck1Limits

/**
 * [TextFieldAccess] implementation backed by the IME's current
 * [InputConnection].
 *
 * Every operation is defensive: InputConnection state varies wildly across
 * apps and can be null or disconnected at any time. No operation throws;
 * all failures are reported through return values so the keyboard can keep
 * its normal typing behaviour intact.
 *
 * NOTE: never logs field content. Not even in debug builds.
 */
class InputConnectionTextField(
    private val connectionProvider: () -> InputConnection?,
    private val editorInfoProvider: () -> EditorInfo?,
) : TextFieldAccess {

    override val isSensitiveField: Boolean
        get() = SensitiveFieldPolicy.isSensitive(editorInfoProvider())

    override fun commitText(text: String): Boolean {
        if (text.isEmpty()) return true
        val connection = connectionProvider() ?: return false
        return connection.commitText(text, 1)
    }

    override fun deleteBackward(): Boolean {
        val connection = connectionProvider() ?: return false
        if (connection.deleteSurroundingText(1, 0)) return true
        // Fallback for editors that do not implement deleteSurroundingText.
        val down = connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        val up = connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        return down && up
    }

    override fun replaceAll(newText: String): Boolean {
        val connection = connectionProvider() ?: return false
        connection.finishComposingText()

        // An active selection is replaced natively by commitText — deleting
        // around the cursor would leave it in place (deep-scan finding 22's
        // replace-side sibling).
        if (connection.getSelectedText(0) != null) {
            return connection.commitText(newText, 1)
        }

        // Strategy 1: batch delete+insert around the cursor (deep-scan
        // finding 21 — the pair used to be non-atomic: a successful
        // deleteSurroundingText followed by a failed commitText destroyed
        // the user's draft with no insertion and no rollback).
        val before = connection.getTextBeforeCursor(MAX_DRAFT_LENGTH, 0)
        val after = connection.getTextAfterCursor(MAX_DRAFT_LENGTH, 0)
        if (before != null && after != null) {
            val batching = connection.beginBatchEdit()
            try {
                val deleted = connection.deleteSurroundingText(before.length, after.length)
                val inserted = deleted && connection.commitText(newText, 1)
                if (deleted && !inserted) {
                    // Best-effort restore: true rollback is impossible over
                    // IPC, but re-committing the deleted text is strictly
                    // better than leaving the field empty.
                    connection.commitText(before.toString() + after.toString(), 1)
                }
                if (inserted) return true
            } finally {
                if (batching) connection.endBatchEdit()
            }
        }

        // Strategy 2: select-all via context menu action, then overwrite the
        // selection. Works in most editors (incl. web views) but is not
        // guaranteed everywhere.
        val selected = connection.performContextMenuAction(android.R.id.selectAll)
        if (selected) {
            return connection.commitText(newText, 1)
        }
        return false
    }

    override fun readDraft(): DraftRead {
        val connection = connectionProvider() ?: return DraftRead.Unreadable

        // Deep-scan finding 23: read at most limit+1 characters per side. A
        // side that comes back at limit+1 PROVES the draft exceeds the
        // protocol limit without hauling the whole field over Binder
        // (previously up to 100,000 chars per side, ~400 KB, synchronously
        // on the main dispatcher — while CK1 caps plaintext at 16 KiB).
        // A side shorter than the request is exact.
        val before = connection.getTextBeforeCursor(MAX_DRAFT_LENGTH, 0)
            ?: return DraftRead.Unreadable
        val after = connection.getTextAfterCursor(MAX_DRAFT_LENGTH, 0)
            ?: return DraftRead.Unreadable
        val selected = connection.getSelectedText(0) // null: no selection or unsupported

        val limit = Ck1Limits.MAX_PLAINTEXT_BYTES
        val selectionLength = selected?.length ?: 0
        if (before.length > limit || after.length > limit || selectionLength > limit ||
            before.length + selectionLength + after.length > limit
        ) {
            return DraftRead.TooLong
        }

        return DraftRead.Draft(
            buildString {
                append(before)
                if (selected != null) append(selected) // deep-scan finding 22
                append(after)
            }.toString(),
        )
    }

    private companion object {
        /** Protocol limit + 1: enough to detect overflow, never more. */
        const val MAX_DRAFT_LENGTH = Ck1Limits.MAX_PLAINTEXT_BYTES + 1
    }
}
