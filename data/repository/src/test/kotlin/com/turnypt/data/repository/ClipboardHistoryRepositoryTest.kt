package com.turnypt.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import com.turnypt.core.common.DispatcherProvider
import com.turnypt.core.messages.Ck1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.5.2: the clipboard-history policy — the guarantees the UI depends on
 * and the security posture the docs promise:
 *  - blank clips are ignored;
 *  - oversized clips are stored TRUNCATED at the cap (never a multi-MB row);
 *  - CK1 envelopes are detected for monospace rendering;
 *  - re-copying known text moves it to the TOP (recents, not a log);
 *  - the history never exceeds MAX_ENTRIES and drops entries older than a
 *    week on every write;
 *  - clear() wipes everything.
 */
class ClipboardHistoryRepositoryTest {

    private fun repo(dao: FakeClipboardHistoryDao = FakeClipboardHistoryDao(), time: MutableTimeSource = MutableTimeSource()) =
        ClipboardHistoryRepository(
            dao = dao,
            timeSource = time,
            dispatchers = DispatcherProvider(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined),
        )

    @Test
    fun `blank text is never recorded`() = runTest {
        val repository = repo()
        assertFalse(repository.record("   "))
        assertFalse(repository.record(""))
        assertTrue(repository.snapshot().isEmpty())
    }

    @Test
    fun `a clip longer than the cap is stored truncated at the cap`() = runTest {
        val repository = repo()
        val longClip = "x".repeat(ClipboardHistoryRepository.MAX_STORED_CHARS + 5_000)
        assertTrue(repository.record(longClip))
        val stored = repository.snapshot().single()
        assertEquals(ClipboardHistoryRepository.MAX_STORED_CHARS, stored.text.length)
        assertTrue(stored.truncated)
    }

    @Test
    fun `a CK1 envelope is flagged for monospace rendering`() = runTest {
        val repository = repo()
        // A real envelope's exact shape does not matter here — the flag is
        // the prefix detection the dialog renders monospace + CK1 tag on.
        val envelope = "CK1:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        repository.record(envelope)
        val stored = repository.snapshot().single()
        assertTrue(stored.isEnvelope)
        assertTrue(Ck1.isEnvelope(stored.text))

        // Ordinary text must NOT be flagged.
        repository.record("just a message")
        assertFalse(repository.snapshot().first { it.text == "just a message" }.isEnvelope)
    }

    @Test
    fun `re-copying known text moves it to the top instead of duplicating`() = runTest {
        val dao = FakeClipboardHistoryDao()
        val time = MutableTimeSource(now = 1_000_000L)
        val repository = repo(dao, time)

        repository.record("first")
        time.now += 60_000L
        repository.record("second")
        time.now += 60_000L
        repository.record("first") // re-copy

        val entries = repository.snapshot()
        assertEquals(listOf("first", "second"), entries.map { it.text })
        assertEquals(1_120_000L, entries.first().capturedAt)
    }

    @Test
    fun `the history is capped at MAX_ENTRIES newest-first`() = runTest {
        val dao = FakeClipboardHistoryDao()
        val time = MutableTimeSource()
        val repository = repo(dao, time)

        repeat(ClipboardHistoryRepository.MAX_ENTRIES + 10) { n ->
            time.now += 1_000L
            repository.record("clip-$n")
        }

        val entries = repository.snapshot()
        assertEquals(ClipboardHistoryRepository.MAX_ENTRIES, entries.size)
        // The NEWEST clip is on top; the oldest ten were pruned.
        assertEquals("clip-${ClipboardHistoryRepository.MAX_ENTRIES + 9}", entries.first().text)
        assertEquals("clip-10", entries.last().text)
    }

    @Test
    fun `entries older than a week are pruned on the next write`() = runTest {
        val time = MutableTimeSource(now = 10_000_000L)
        val repository = repo(FakeClipboardHistoryDao(), time)

        repository.record("ancient")
        // Jump the clock past the retention window, then write a fresh clip.
        time.now += ClipboardHistoryRepository.MAX_AGE_MILLIS + 1
        repository.record("fresh")

        val entries = repository.snapshot()
        assertEquals(listOf("fresh"), entries.map { it.text })
    }

    @Test
    fun `clear wipes the whole history`() = runTest {
        val repository = repo()
        repository.record("one")
        repository.record("two")
        repository.clear()
        assertTrue(repository.snapshot().isEmpty())
    }

    @Test
    fun `delete removes exactly one entry`() = runTest {
        val repository = repo()
        repository.record("one")
        repository.record("two")
        val id = repository.snapshot().first { it.text == "one" }.id
        repository.delete(id)
        assertEquals(listOf("two"), repository.snapshot().map { it.text })
    }

    @Test
    fun `entries flow emits newest-first snapshots`() = runTest {
        val time = MutableTimeSource()
        val repository = repo(FakeClipboardHistoryDao(), time)
        repository.record("a")
        time.now += 1_000
        repository.record("b")
        assertEquals(listOf("b", "a"), repository.entries.first().map { it.text })
    }
}
