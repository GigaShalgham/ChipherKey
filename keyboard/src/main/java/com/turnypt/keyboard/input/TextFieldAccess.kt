package com.turnypt.keyboard.input

/**
 * Outcome of reading the current draft (deep-scan findings 22/23).
 *
 * The three cases are distinct and map to distinct user-facing notices:
 * a field whose text cannot be read is NOT the same as an empty draft, and
 * a draft that provably exceeds the CK1 plaintext limit must never be
 * silently truncated (the security contract of ProcessTextContract applies
 * here too).
 */
sealed interface DraftRead {

    /** The complete draft (text before the cursor + selection + text after). */
    data class Draft(val text: String) : DraftRead

    /** No input connection, or a non-cooperating editor. */
    data object Unreadable : DraftRead

    /** The draft exceeds the protocol's plaintext limit. */
    data object TooLong : DraftRead
}

/**
 * Narrow, framework-independent view of the current text field.
 *
 * The keyboard's logic layer depends on this interface instead of
 * [android.view.inputmethod.InputConnection] so that replacement, insertion
 * and draft-reading behaviour can be unit tested on the JVM with fakes.
 */
interface TextFieldAccess {

    /** Commits [text] at the cursor. Returns false when no input connection is available. */
    fun commitText(text: String): Boolean

    /** Deletes the character before the cursor. Returns false when nothing was deleted. */
    fun deleteBackward(): Boolean

    /**
     * Replaces the entire content of the field with [newText].
     * Returns false when the field could not be edited.
     */
    fun replaceAll(newText: String): Boolean

    /**
     * Reads the current draft: the text before the cursor, the ACTIVE
     * SELECTION, and the text after the cursor (deep-scan finding 22 — the
     * selection used to be silently omitted, so select-all + encrypt
     * produced "Nothing to encrypt").
     */
    fun readDraft(): DraftRead

    /** True while the field reports that it is a password / sensitive field. */
    val isSensitiveField: Boolean
}
