package com.turnypt.app.companion

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * v0.5.0: no-display trampoline for the launcher's static app shortcuts
 * ("Encrypt clipboard" / "Decrypt clipboard" — long-press the app icon,
 * see docs/COMPATIBILITY.md).
 *
 * Why it exists: static shortcut intents cannot carry extras, so the two
 * shortcuts in res/xml/shortcuts.xml target this activity with dedicated
 * actions. It forwards into the EXISTING clipboard companion flows —
 * the same audited path the Home-screen buttons and the floating
 * assistant use (EXTRA_FROM_CLIPBOARD): focus-deferred clipboard read
 * through the ClipboardAccess chokepoint, FLAG_SECURE, noHistory.
 *
 * Why the selection toolbar cannot appear in Telegram's own message menu
 * (the problem these shortcuts answer): Telegram draws its own selection
 * menu for message bubbles and does not host third-party
 * ACTION_PROCESS_TEXT entries there — no app can add itself to it. The
 * clipboard ladder is the universal path; research notes and sources:
 * docs/COMPATIBILITY.md.
 */
class ClipboardShortcutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = when (intent?.action) {
            ACTION_ENCRYPT_CLIPBOARD -> EncryptActivity::class.java
            ACTION_DECRYPT_CLIPBOARD -> DecryptActivity::class.java
            else -> null
        }
        if (target != null) {
            startActivity(
                Intent(this, target).putExtra(EXTRA_FROM_CLIPBOARD, true),
            )
        }
        // Theme.NoDisplay: finish before anything renders; the companion
        // sheet (or nothing, for an unknown action) takes it from here.
        finish()
    }

    companion object {
        const val ACTION_ENCRYPT_CLIPBOARD = "com.turnypt.app.action.ENCRYPT_CLIPBOARD"
        const val ACTION_DECRYPT_CLIPBOARD = "com.turnypt.app.action.DECRYPT_CLIPBOARD"
    }
}
