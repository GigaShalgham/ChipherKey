package com.turnypt.app.companion

import com.turnypt.core.messages.Ck1
import com.turnypt.core.messages.Ck1Limits

/**
 * Pure-JVM hardening seam between arbitrary OS input and the companion app.
 *
 * Every text that enters Turnypt through ACTION_PROCESS_TEXT, ACTION_SEND,
 * or the clipboard passes through one of the parse functions here before
 * any UI or crypto work happens. This is the single place where hostile or
 * accidental input (null extras, blank selections, oversized payloads,
 * multi-byte boundary cases) is rejected — with a machine-testable outcome
 * instead of an exception.
 *
 * SECURITY CONTRACT: no function in this object ever truncates silently —
 * an oversized input is rejected wholesale ([SelectedText.Oversized])
 * because a silently truncated plaintext would encrypt into a message whose
 * decrypt differs from what the user selected.
 */
object ProcessTextContract {

    /** Outcome of sanitizing text handed to Turnypt from the OS. */
    sealed interface SelectedText {
        /** Cleaned, non-empty text within the size limit. */
        data class Valid(val text: String) : SelectedText

        /** Nothing usable (null extra, blank, whitespace-only). */
        data object Empty : SelectedText

        /** Input exceeds the limit in UTF-8 bytes — reject, never truncate. */
        data class Oversized(val limitBytes: Int) : SelectedText
    }

    /** Sanitizes a draft to be encrypted (plaintext cap: 16 KiB). */
    fun parsePlaintext(raw: CharSequence?): SelectedText =
        parse(raw, Ck1Limits.MAX_PLAINTEXT_BYTES)

    /** Sanitizes an envelope candidate (envelope cap: 256 KiB). */
    fun parseEnvelope(raw: CharSequence?): SelectedText =
        parse(raw, Ck1Limits.MAX_ENVELOPE_BYTES)

    private fun parse(raw: CharSequence?, limitBytes: Int): SelectedText {
        if (raw == null) return SelectedText.Empty
        val trimmed = raw.toString().trim()
        if (trimmed.isEmpty()) return SelectedText.Empty
        // v0.5.1: a UTF-8 byte is never shorter than a char, so a character
        // count above the cap is DEFINITELY above the byte cap. This cheap
        // guard rejects a multi-hundred-MiB share/selection WITHOUT first
        // materialising its full UTF-8 encoding — the old check allocated
        // the entire byte array up front and OOM-crashed the app.
        if (trimmed.length > limitBytes) {
            return SelectedText.Oversized(limitBytes)
        }
        val utf8Size = trimmed.toByteArray(Charsets.UTF_8).size
        if (utf8Size > limitBytes) {
            return SelectedText.Oversized(limitBytes)
        }
        return SelectedText.Valid(trimmed)
    }

    /** True when the sanitized text carries the CK1 envelope prefix. */
    fun isEnvelope(text: String): Boolean = Ck1.isEnvelope(text)
}
