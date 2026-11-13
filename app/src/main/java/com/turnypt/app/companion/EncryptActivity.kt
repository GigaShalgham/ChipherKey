package com.turnypt.app.companion

import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import com.turnypt.app.R
import com.turnypt.app.companion.ProcessTextContract.SelectedText

/**
 * PROCESS_TEXT entry point for the outgoing workflow (spec 003, User
 * Story 1): the user selects the draft they just typed (with any keyboard,
 * including Samsung Keyboard) and taps "Encrypt (Turnypt)" in the
 * selection toolbar. The activity encrypts the selection for the sticky
 * contact and returns the CK1 envelope as the activity result, which the
 * system uses to replace the selected text in the calling field.
 */
@AndroidEntryPoint
class EncryptActivity : BaseCompanionActivity() {

    override val companionMode: CompanionMode = CompanionMode.ENCRYPT

    override fun dispatchIntent(intent: Intent) {
        if (isClipboardLaunch(intent)) {
            // Clipboard fallback launched from Turnypt's Home screen.
            // Focus-deferred by the base class; the read happens through
            // the ClipboardAccess chokepoint while this window has focus.
            viewModel.beginFromClipboard(CompanionMode.ENCRYPT)
            return
        }

        // PROCESS_TEXT contract: when the selection lives in a read-only
        // view the caller passes EXTRA_PROCESS_TEXT_READONLY=true and the
        // result would be ignored — the sheet must not offer
        // "Replace in field" (spec 004 fix).
        val readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)

        when (
            val parsed = ProcessTextContract.parsePlaintext(
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT),
            )
        ) {
            is SelectedText.Valid ->
                viewModel.beginEncrypt(
                    text = parsed.text,
                    source = TextSource.SELECTION,
                    readOnly = readOnly,
                )
            SelectedText.Empty -> showNotice(
                UiNotice(R.string.notice_no_selection_encrypt),
            )
            is SelectedText.Oversized -> showNotice(
                UiNotice(R.string.notice_oversized_encrypt, "${parsed.limitBytes / 1024}"),
            )
        }
    }
}
