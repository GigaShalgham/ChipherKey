package com.turnypt.app.companion

import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import com.turnypt.app.R
import com.turnypt.app.companion.ProcessTextContract.SelectedText

/**
 * PROCESS_TEXT entry point for the incoming workflow (spec 003, User
 * Story 2): the user selects a received "CK1:…" message and taps
 * "Decrypt (Turnypt)". Works on read-only text (EXTRA_PROCESS_TEXT_
 * READONLY) as well as editable fields. Shows an explicit preview before
 * anything is copied; offers result-insertion only in editable contexts.
 */
@AndroidEntryPoint
class DecryptActivity : BaseCompanionActivity() {

    override val companionMode: CompanionMode = CompanionMode.DECRYPT

    override fun dispatchIntent(intent: Intent) {
        if (isClipboardLaunch(intent)) {
            // Clipboard fallback launched from Turnypt's Home screen.
            // Focus-deferred by the base class.
            viewModel.beginFromClipboard(CompanionMode.DECRYPT)
            return
        }

        val readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)

        when (
            val parsed = ProcessTextContract.parseEnvelope(
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT),
            )
        ) {
            is SelectedText.Valid ->
                viewModel.beginDecrypt(
                    text = parsed.text,
                    source = TextSource.SELECTION,
                    readOnly = readOnly,
                )
            SelectedText.Empty -> showNotice(
                UiNotice(R.string.notice_no_selection_decrypt),
            )
            is SelectedText.Oversized -> showNotice(
                UiNotice(R.string.notice_oversized_decrypt, "${parsed.limitBytes / 1024}"),
            )
        }
    }
}
