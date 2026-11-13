package com.turnypt.keyboard.input

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * Decides whether the current input field must be treated as sensitive.
 *
 * Turnypt NEVER offers encryption actions on password fields: reading or
 * replacing the content of a credential field would be a security defect,
 * not a feature. On sensitive fields the keyboard degrades to a plain
 * typing surface and the encryption toolbar is disabled.
 *
 * The [Int]-based overload is intentionally free of Android types in its
 * signature so that it can be unit tested on the JVM (android.text.InputType
 * constants are compile-time inlined by the Kotlin compiler).
 */
object SensitiveFieldPolicy {

    fun isSensitive(editorInfo: EditorInfo?): Boolean =
        editorInfo != null && isSensitive(editorInfo.inputType)

    fun isSensitive(inputType: Int): Boolean {
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION

        return when (inputClass) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }
}
