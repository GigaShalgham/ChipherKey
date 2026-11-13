package com.turnypt.app.companion

import androidx.annotation.StringRes

/**
 * A user-facing notice as a string resource + optional format argument
 * (v0.3.0).
 *
 * Why typed instead of a prebuilt String: the notice now survives process
 * death and configuration changes untranslated-free — the UI resolves the
 * resource at render time, in the current locale, with the argument kept
 * separate (never concatenated), and unit tests assert on the resource id
 * instead of brittle English text.
 */
data class UiNotice(
    @StringRes val res: Int,
    val arg: String? = null,
)
