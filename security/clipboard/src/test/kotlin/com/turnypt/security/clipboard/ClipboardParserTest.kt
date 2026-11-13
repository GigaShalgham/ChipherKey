package com.turnypt.security.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 004 test matrix for the clipboard classification seam: ordinary
 * text, Unicode (Persian), emoji, multiline, empty, non-text. The parser
 * is pure; the Android-side snapshot builder is exercised on the emulator
 * procedure documented in the spec.
 */
class ClipboardParserTest {

    // ---------------------------------------------------------- text clips

    @Test fun ordinaryTextIsText() {
        val out = ClipboardParser.parse(ClipSnapshot(items = listOf("hello world")))
        assertEquals(ClipboardRead.Text("hello world"), out)
    }

    @Test fun persianTextIsText() {
        val text = "سلام دنیا، این یک پیام آزمایشی است"
        val out = ClipboardParser.parse(
            ClipSnapshot(mimeTypes = listOf("text/plain"), items = listOf(text)),
        )
        assertEquals(ClipboardRead.Text(text), out)
    }

    @Test fun emojiOnlyTextIsText() {
        val out = ClipboardParser.parse(ClipSnapshot(items = listOf("😀🎉🔐")))
        assertEquals(ClipboardRead.Text("😀🎉🔐"), out)
    }

    @Test fun mixedRtlEmojiAndLatinIsText() {
        val text = " Turnypt 🔐 قیمت: 45,000 تومان — OK "
        val out = ClipboardParser.parse(ClipSnapshot(items = listOf(text)))
        // Raw text is returned unmodified; trimming belongs to the contract.
        assertEquals(ClipboardRead.Text(text), out)
    }

    @Test fun multilineLfTextIsText() {
        val text = "line one\nline two\nline three"
        assertEquals(ClipboardRead.Text(text), ClipboardParser.parse(ClipSnapshot(items = listOf(text))))
    }

    @Test fun multilineCrLfTextIsText() {
        val text = "line one\r\nline two\r\nline three"
        assertEquals(ClipboardRead.Text(text), ClipboardParser.parse(ClipSnapshot(items = listOf(text))))
    }

    @Test fun multilineWithEmojiAndPersianIsText() {
        val text = "سلام 🔐\nHow are you?\r\nخوبم، ممنون"
        assertEquals(ClipboardRead.Text(text), ClipboardParser.parse(ClipSnapshot(items = listOf(text))))
    }

    @Test fun multipleItemsFirstUsableWins() {
        val out = ClipboardParser.parse(
            ClipSnapshot(items = listOf(null, "  ", "second item")),
        )
        assertEquals(ClipboardRead.Text("second item"), out)
    }

    @Test fun textWithoutMimeTypesIsStillText() {
        // URI-less clips may have no description; items carry the content.
        val out = ClipboardParser.parse(ClipSnapshot(items = listOf("no mime info")))
        assertEquals(ClipboardRead.Text("no mime info"), out)
    }

    // ---------------------------------------------------------- empties

    @Test fun nullSnapshotIsEmpty() =
        assertEquals(ClipboardRead.Empty, ClipboardParser.parse(null))

    @Test fun zeroItemsIsEmpty() =
        assertEquals(ClipboardRead.Empty, ClipboardParser.parse(ClipSnapshot(items = emptyList())))

    @Test fun nullItemTextsAreEmpty() =
        assertEquals(ClipboardRead.Empty, ClipboardParser.parse(ClipSnapshot(items = listOf(null, null))))

    @Test fun blankItemTextsAreEmpty() {
        assertEquals(
            ClipboardRead.Empty,
            ClipboardParser.parse(
                ClipSnapshot(mimeTypes = listOf("text/plain"), items = listOf("", "   ", "\n\t")),
            ),
        )
    }

    // ---------------------------------------------------------- non-text

    @Test fun imageClipIsNonText() {
        val out = ClipboardParser.parse(
            ClipSnapshot(mimeTypes = listOf("image/png"), items = listOf(null)),
        )
        assertEquals(ClipboardRead.NonText("image/png"), out)
    }

    @Test fun nonTextMimeAmongTextMimesWins() {
        // ClipData descriptions can advertise several types; any non-text
        // type with no usable text means the clip is not processable.
        val out = ClipboardParser.parse(
            ClipSnapshot(mimeTypes = listOf("text/plain", "application/x-foo"), items = listOf(null)),
        )
        assertEquals(ClipboardRead.NonText("application/x-foo"), out)
    }

    @Test fun nonTextMimeWithBlankTextIsNonText() {
        val out = ClipboardParser.parse(
            ClipSnapshot(mimeTypes = listOf("image/jpeg"), items = listOf("  ")),
        )
        assertEquals(ClipboardRead.NonText("image/jpeg"), out)
    }

    // ---------------------------------------------------------- precedence

    @Test fun usableTextBeatsNonTextMime() {
        // Descriptions can lie (e.g. "text/* + image/*" web copies): usable
        // item text always wins — never refuse text the platform delivered.
        val out = ClipboardParser.parse(
            ClipSnapshot(mimeTypes = listOf("image/png"), items = listOf("actually text")),
        )
        assertEquals(ClipboardRead.Text("actually text"), out)
    }

    @Test fun htmlMimeTypeWithTextIsText() {
        val out = ClipboardParser.parse(
            ClipSnapshot(mimeTypes = listOf("text/html"), items = listOf("<b>bold</b>")),
        )
        assertEquals(ClipboardRead.Text("<b>bold</b>"), out)
    }

    // ---------------------------------------------------------- blocked (wrapper contract)

    @Test fun blockedOutcomeCarriesReason() {
        // The Android wrapper maps thrown clipboard exceptions to Blocked;
        // the sealed shape itself must round-trip a reason string.
        val blocked = ClipboardRead.Blocked("Denying clipboard access to com.turnypt.app")
        assertTrue(blocked.reason!!.startsWith("Denying"))
    }
}
