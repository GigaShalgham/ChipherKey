package com.turnypt.security.clipboard

import android.content.ClipboardManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Outcome of reading the system clipboard.
 *
 * The distinction matters for user-facing messaging (spec 004): an empty
 * clipboard, a non-text clip, and an access failure are different problems
 * with different remedies, and collapsing them into "null" produced the
 * misleading "clipboard is empty" notice seen on Samsung devices.
 */
sealed interface ClipboardRead {

    /** A usable text clip. The text is returned raw (no trimming). */
    data class Text(val text: String) : ClipboardRead

    /** No clip, a clip with zero items, or a text clip whose text is blank. */
    data object Empty : ClipboardRead

    /** A clip whose description advertises non-text content (e.g. image/png). */
    data class NonText(val mimeType: String?) : ClipboardRead

    /**
     * The system refused or failed the read (SecurityException on some OEM
     * builds when the window is not focused, clipboard service unavailable).
     */
    data class Blocked(val reason: String?) : ClipboardRead
}

/**
 * Pure, Android-free snapshot of a clip, so the classification logic is
 * unit-testable on the JVM (spec 004 test matrix: ordinary text, Persian,
 * emoji, multiline, empty, non-text).
 */
data class ClipSnapshot(
    val mimeTypes: List<String> = emptyList(),
    val items: List<CharSequence?> = emptyList(),
)

/**
 * Pure decision logic mapping a clip snapshot to a [ClipboardRead].
 *
 * SECURITY CONTRACT: this object performs no I/O and holds no state; it only
 * classifies. Order of precedence:
 *  1. first non-blank item text wins ([ClipboardRead.Text], raw);
 *  2. all items blank + a non-text MIME type advertised -> [NonText];
 *  3. otherwise -> [Empty].
 */
object ClipboardParser {

    fun parse(clip: ClipSnapshot?): ClipboardRead {
        if (clip == null) return ClipboardRead.Empty
        if (clip.items.isEmpty()) return ClipboardRead.Empty

        val firstUsable = clip.items.firstOrNull { item ->
            !item.isNullOrBlank()
        }?.toString() ?: return classifyBlank(clip)

        return ClipboardRead.Text(firstUsable)
    }

    private fun classifyBlank(clip: ClipSnapshot): ClipboardRead {
        val nonTextMime = clip.mimeTypes.firstOrNull { mime ->
            !mime.startsWith("text/", ignoreCase = true)
        }
        return if (nonTextMime != null) {
            ClipboardRead.NonText(nonTextMime)
        } else {
            ClipboardRead.Empty
        }
    }
}

/**
 * Narrow, auditable view of the system clipboard.
 *
 * SECURITY CONTRACT — this interface exists so that the clipboard read path
 * is a single, reviewable chokepoint:
 *  - it is invoked ONLY from an explicit user action (the Home-screen
 *    "Encrypt clipboard" / "Decrypt clipboard" buttons, the result Copy
 *    button, or the keyboard's "Decrypt clipboard" button);
 *  - Turnypt never registers clipboard listeners and never reads the
 *    clipboard in the background;
 *  - on Android 10+ the platform restricts clipboard access to the app that
 *    currently owns the FOCUSED window (or the focused input method). A
 *    window gains focus only AFTER Activity.onCreate — callers must
 *    therefore read from onWindowFocusChanged(hasFocus = true) or from a
 *    user gesture inside the focused activity, never from onCreate/onStart.
 */
interface ClipboardAccess {

    /**
     * Reads the current primary clip with a full outcome taxonomy.
     *
     * The caller is responsible for the focus precondition above; a read
     * performed from an unfocused window returns [ClipboardRead.Empty] or
     * [ClipboardRead.Blocked] depending on the OEM build.
     */
    fun read(): ClipboardRead

    /**
     * Legacy bridge: the current clip as text, or null when there is no
     * usable text (empty, non-text, or blocked). Retained for the :keyboard
     * module's clipboard button; new callers should use [read].
     */
    fun currentText(): String?

    /**
     * Overwrites the primary clip with [text].
     *
     * SECURITY CONTRACT: used ONLY as an explicit, user-visible action
     * (copying an envelope or a decrypted result). Never called implicitly.
     */
    fun setText(text: String): Boolean

    /**
     * Overwrites the primary clip with SENSITIVE text (decrypted
     * plaintext). Implementations mark the clip so Android 13+ hides its
     * content preview (deep-scan finding 17); older platforms degrade to a
     * plain copy. Same explicit-user-action contract as [setText].
     *
     * Defaults to [setText] so JVM fakes need no changes.
     */
    fun setSensitiveText(text: String): Boolean = setText(text)
}

@Singleton
class AndroidClipboardAccess @Inject constructor(
    @ApplicationContext private val context: Context,
) : ClipboardAccess {

    override fun read(): ClipboardRead {
        val manager = manager() ?: return ClipboardRead.Blocked("clipboard service unavailable")
        return try {
            val clip = manager.primaryClip ?: return ClipboardRead.Empty
            val snapshot = ClipSnapshot(
                mimeTypes = runCatching {
                    val description = clip.description
                    if (description == null) {
                        emptyList()
                    } else {
                        (0 until description.mimeTypeCount).map { description.getMimeType(it) }
                    }
                }.getOrDefault(emptyList()),
                items = (0 until clip.itemCount).map { i ->
                    val item = runCatching { clip.getItemAt(i) }.getOrNull() ?: return@map null
                    // item.text is the cheap, IPC-free path; coerceToText
                    // additionally resolves Intent/URI-style items (bounded
                    // by the explicit user action that triggered the read).
                    runCatching { item.text }.getOrNull()
                        ?: runCatching { item.coerceToText(context)?.toString() }.getOrNull()
                },
            )
            ClipboardParser.parse(snapshot)
        } catch (e: Exception) {
            // Some OEM builds (observed on Samsung) throw instead of
            // returning null when the calling window is not focused.
            ClipboardRead.Blocked(e.message ?: e.javaClass.simpleName)
        }
    }

    override fun currentText(): String? =
        (read() as? ClipboardRead.Text)?.text

    override fun setText(text: String): Boolean = setClip(text, sensitive = false)

    override fun setSensitiveText(text: String): Boolean = setClip(text, sensitive = true)

    private fun setClip(text: String, sensitive: Boolean): Boolean {
        val manager = manager() ?: return false
        return runCatching {
            val clip = android.content.ClipData.newPlainText("Turnypt", text)
            if (sensitive) {
                // Android 13+ honours EXTRA_IS_SENSITIVE: the clipboard
                // overlay shows only "Sensitive content copied" instead of
                // the plaintext. Harmless on older versions (unknown extra).
                clip.description.extras = android.os.PersistableBundle().apply {
                    putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
            manager.setPrimaryClip(clip)
            true
        }.getOrDefault(false)
    }

    private fun manager(): ClipboardManager? =
        context.getSystemService(ClipboardManager::class.java)
}
