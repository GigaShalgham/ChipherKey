package com.turnypt.app.companion

import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import com.turnypt.app.R
import com.turnypt.app.companion.ProcessTextContract.SelectedText

/**
 * ACTION_SEND (text/plain) entry point for the incoming workflow
 * (spec 003, User Story 2): messengers where sharing the message is more
 * natural than selecting it offer "Decrypt with Turnypt" in the
 * sharesheet. Always read-only by nature — the flow ends in an explicit
 * preview plus Copy.
 */
@AndroidEntryPoint
class ShareDecryptActivity : BaseCompanionActivity() {

    override val companionMode: CompanionMode = CompanionMode.DECRYPT

    override fun dispatchIntent(intent: Intent) {
        val shared = when (intent.action) {
            Intent.ACTION_SEND ->
                intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            else -> null
        }

        when (val parsed = ProcessTextContract.parseEnvelope(shared)) {
            is SelectedText.Valid ->
                viewModel.beginDecrypt(
                    text = parsed.text,
                    source = TextSource.SHARE,
                    readOnly = true,
                )
            SelectedText.Empty -> showNotice(
                UiNotice(R.string.notice_nothing_shared),
            )
            is SelectedText.Oversized -> showNotice(
                UiNotice(R.string.notice_oversized_shared, "${parsed.limitBytes / 1024}"),
            )
        }
    }
}
