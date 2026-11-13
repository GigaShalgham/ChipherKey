package com.turnypt.app.clipboard

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.turnypt.data.preferences.SettingsRepository
import com.turnypt.data.repository.ClipboardHistoryRepository
import com.turnypt.security.clipboard.ClipboardAccess
import com.turnypt.security.clipboard.ClipboardRead
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v0.5.2: the single chokepoint that turns clipboard reads into history
 * entries — "the list of things you copied and had copied before".
 *
 * SECURITY CONTRACT (extends ClipboardAccess's):
 *  - [captureFromFocusedWindow] is called ONLY from
 *    onWindowFocusChanged(hasFocus = true) or an explicit user action —
 *    Turnypt still never registers a clipboard listener and never reads
 *    in the background; on Android 10+ it could not anyway;
 *  - the Settings toggle (`clipboardHistoryEnabled`) gates every capture;
 *  - SENSITIVE copies (decrypted plaintext, suggested passphrases) go
 *    through [onSensitiveCopied], which records NOTHING and marks the text
 *    as already-seen so the next focus read of the same clip is skipped —
 *    secrets never enter the history database;
 *  - the repository enforces the size/count/age caps.
 *
 * FOCUS-CHANGE BURSTS: dialogs, the keyboard and the assistant panel cause
 * rapid focus flips; [FOCUS_QUIET_PERIOD_MILLIS] coalesces them into one
 * read, and the in-memory [lastSeen] dedupes an unchanged clip so the
 * database is only touched for genuinely new copies.
 */
@Singleton
class ClipboardHistoryRecorder @Inject constructor(
    private val settings: SettingsRepository,
    private val clipboard: ClipboardAccess,
    private val history: ClipboardHistoryRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Last clip text this recorder decided about (null = nothing yet). */
    private val lastSeen = AtomicReference<String?>(null)

    /** Monotonic millis of the last capture attempt (burst coalescing). */
    private val lastCaptureAt = AtomicLong(0L)

    /** Serializes captures so delete-then-insert never interleaves. */
    private val mutex = Mutex()

    /**
     * Records the current clip. The caller MUST own the focused window
     * (MainActivity's onWindowFocusChanged(true) — the one place a
     * foreground app may read the clipboard on Android 10+).
     */
    fun captureFromFocusedWindow() {
        val now = System.currentTimeMillis()
        val previous = lastCaptureAt.get()
        if (now - previous < FOCUS_QUIET_PERIOD_MILLIS) return
        if (!lastCaptureAt.compareAndSet(previous, now)) return
        scope.launch {
            try {
                if (!settings.settings.first().clipboardHistoryEnabled) return@launch
                val clip = clipboard.read()
                if (clip is ClipboardRead.Text) {
                    recordIfNew(clip.text)
                }
            } catch (_: Exception) {
                // History capture must never disturb the caller's flow.
            }
        }
    }

    /**
     * Records text the app itself just copied as PUBLIC (envelopes, invite
     * codes, ordinary results) so the user can re-use it from the history.
     */
    fun onCopied(text: String) {
        scope.launch {
            try {
                if (!settings.settings.first().clipboardHistoryEnabled) return@launch
                mutex.withLock {
                    if (lastSeen.get() == text) return@withLock
                    lastSeen.set(text)
                    history.record(text)
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * The app just copied SENSITIVE text (decrypted plaintext, a suggested
     * passphrase): record NOTHING and mark it seen so the next focus read
     * of the same clip cannot store it either.
     */
    fun onSensitiveCopied(text: String) {
        lastSeen.set(text)
    }

    /**
     * Explicit user action (Paste / the history dialog / the assistant's
     * clipboard rows): record the text that was just read. Fire-and-forget
     * by design — history capture must never block or break the caller's
     * flow, so this launches its own coroutine and swallows its errors.
     */
    fun recordReadText(text: String) {
        scope.launch {
            try {
                if (!settings.settings.first().clipboardHistoryEnabled) return@launch
                mutex.withLock {
                    if (lastSeen.get() == text) return@withLock
                    lastSeen.set(text)
                    history.record(text)
                }
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun recordIfNew(text: String) {
        mutex.withLock {
            if (lastSeen.get() == text) return@withLock
            lastSeen.set(text)
            history.record(text)
        }
    }

    fun dispose() {
        scope.cancel()
    }

    companion object {
        /** Focus flips inside ~2 s (dialog open/close) count as one capture. */
        const val FOCUS_QUIET_PERIOD_MILLIS = 2_000L
    }
}
