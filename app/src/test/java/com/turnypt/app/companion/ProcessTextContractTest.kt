package com.turnypt.app.companion

import com.turnypt.app.companion.ProcessTextContract.SelectedText
import com.turnypt.core.messages.Ck1Limits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adversarial input tests for the companion hardening seam (spec 003,
 * FR-004): junk handed to the PROCESS_TEXT / SEND / clipboard entries must
 * produce a graceful, machine-checkable outcome — never an exception, never
 * a silent truncation.
 */
class ProcessTextContractTest {

    // ---------------------------------------------------------- empties

    @Test fun nullExtraIsEmpty() =
        assertEquals(SelectedText.Empty, ProcessTextContract.parsePlaintext(null))

    @Test fun emptyStringIsEmpty() =
        assertEquals(SelectedText.Empty, ProcessTextContract.parsePlaintext(""))

    @Test fun whitespaceOnlyIsEmpty() =
        assertEquals(SelectedText.Empty, ProcessTextContract.parsePlaintext("   \n\t\r  "))

    // ---------------------------------------------------------- valid text

    @Test fun plainAsciiIsValid() {
        val out = ProcessTextContract.parsePlaintext("hello world")
        assertTrue(out is SelectedText.Valid)
        assertEquals("hello world", (out as SelectedText.Valid).text)
    }

    @Test fun surroundingWhitespaceIsTrimmed() {
        val out = ProcessTextContract.parsePlaintext("\n  سلام دنیا  \t")
        assertEquals("سلام دنیا", (out as SelectedText.Valid).text)
    }

    @Test fun persianTextSurvivesUnmodified() {
        val text = "این یک پیام آزمایشی است 🔐 — با اعداد ۱۲۳"
        val out = ProcessTextContract.parsePlaintext(text)
        assertEquals(text, (out as SelectedText.Valid).text)
    }

    @Test fun rtlAndEmojiMixIsValid() {
        val out = ProcessTextContract.parsePlaintext("🔐🔒 بی‌متن")
        assertTrue(out is SelectedText.Valid)
    }

    @Test fun controlCharactersAreKeptAsData() {
        // Control chars are plaintext like any other byte; the AEAD layer's
        // job is to preserve them byte-exact, not to judge them.
        val out = ProcessTextContract.parsePlaintext("a\u0000b\u0007c")
        assertEquals("a\u0000b\u0007c", (out as SelectedText.Valid).text)
    }

    // ---------------------------------------------------------- size caps

    @Test fun exactlyAtPlaintextCapIsValid() {
        val atCap = "a".repeat(Ck1Limits.MAX_PLAINTEXT_BYTES)
        assertTrue(ProcessTextContract.parsePlaintext(atCap) is SelectedText.Valid)
    }

    @Test fun oneByteOverPlaintextCapIsOversized() {
        val over = "a".repeat(Ck1Limits.MAX_PLAINTEXT_BYTES + 1)
        assertEquals(
            SelectedText.Oversized(Ck1Limits.MAX_PLAINTEXT_BYTES),
            ProcessTextContract.parsePlaintext(over),
        )
    }

    @Test fun multibyteCrossingTheCapIsRejectedNotTruncated() {
        // 16383 ASCII bytes + one 2-byte char = 16385 UTF-8 bytes: one over
        // the cap. Must be Oversized — a silent truncation here would
        // encrypt a message that differs from the user's selection.
        val almost = "a".repeat(Ck1Limits.MAX_PLAINTEXT_BYTES - 1) + "é"
        assertEquals(
            SelectedText.Oversized(Ck1Limits.MAX_PLAINTEXT_BYTES),
            ProcessTextContract.parsePlaintext(almost),
        )
    }

    @Test fun multibyteExactlyAtCapIsValid() {
        // 8192 two-byte chars = exactly 16384 bytes.
        val atCap = "é".repeat(Ck1Limits.MAX_PLAINTEXT_BYTES / 2)
        assertTrue(ProcessTextContract.parsePlaintext(atCap) is SelectedText.Valid)
    }

    @Test fun envelopeCapIsLargerThanPlaintextCap() {
        // A 64 KiB payload is oversized for encrypt input but fine for a
        // (malformed) envelope candidate — the caps differ by contract.
        val big = "x".repeat(64 * 1024)
        assertTrue(ProcessTextContract.parsePlaintext(big) is SelectedText.Oversized)
        assertTrue(ProcessTextContract.parseEnvelope(big) is SelectedText.Valid)
    }

    @Test fun envelopeCapRejectsBeyond256KiB() {
        val huge = "CK1:" + "a".repeat(300 * 1024)
        assertTrue(ProcessTextContract.parseEnvelope(huge) is SelectedText.Oversized)
    }

    // v0.5.1: the character-count guard. A multi-megabyte selection/share
    // used to allocate its FULL UTF-8 encoding before the size check and
    // OOM-crash the app; it must now be rejected by the cheap length check.
    @Test fun multiMegabyteSelectionIsRejectedWithoutEncodingIt() {
        val oneMiB = "a".repeat(1024 * 1024)
        assertTrue(ProcessTextContract.parsePlaintext(oneMiB) is SelectedText.Oversized)
        assertTrue(ProcessTextContract.parseEnvelope(oneMiB) is SelectedText.Oversized)
    }

    @Test fun oversizedMultibyteTextIsRejectedByCharCountAlone() {
        // 100k Persian chars = 200k UTF-8 bytes — far over the plaintext cap
        // on both counts; the char guard fires before any byte allocation.
        val persianWall = "م".repeat(100_000)
        assertTrue(ProcessTextContract.parsePlaintext(persianWall) is SelectedText.Oversized)
    }

    // ---------------------------------------------------------- envelopes

    @Test fun envelopePrefixIsDetected() =
        assertTrue(ProcessTextContract.isEnvelope("CK1:QWxhZGRpbglvcGVuIHNlc2FtZQ"))

    @Test fun whitespacePaddedEnvelopeStillDetectedAfterTrim() {
        val out = ProcessTextContract.parseEnvelope("  CK1:abc123  ")
        assertEquals("CK1:abc123", (out as SelectedText.Valid).text)
        assertTrue(ProcessTextContract.isEnvelope(out.text))
    }

    @Test fun inviteCodeIsNotAnEnvelope() =
        assertFalse(ProcessTextContract.isEnvelope("CKS1:invite-material"))

    @Test fun lowercasePrefixIsNotAnEnvelope() =
        assertFalse(ProcessTextContract.isEnvelope("ck1:abc"))

    @Test fun arbitraryJunkIsNotAnEnvelope() {
        for (junk in listOf("", "hello", "CK1", "🤖", "null", "undefined")) {
            assertFalse("junk must not look like an envelope: '$junk'", ProcessTextContract.isEnvelope(junk))
        }
    }

    @Test fun barePrefixIsEnvelopeShapedButDecodesGracefullyLater() {
        // "CK1:" alone passes the cheap prefix check; rejecting its payload
        // is the decoder's job (strict parser, own golden tests). The
        // contract's responsibility is only shape detection + size.
        assertTrue(ProcessTextContract.isEnvelope("CK1:"))
    }
}
