package com.turnypt.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import com.turnypt.core.common.DispatcherProvider
import com.turnypt.core.messages.Ck1
import com.turnypt.core.sessions.TimeSource
import com.turnypt.data.database.ClipboardHistoryDao
import com.turnypt.data.database.ClipboardHistoryEntity

/**
 * v0.5.2: one clipboard-history row for the UI (domain copy of the entity).
 */
data class ClipboardHistoryEntry(
    val id: Long,
    val text: String,
    val isEnvelope: Boolean,
    val truncated: Boolean,
    val capturedAt: Long,
)

/**
 * v0.5.2: the clipboard history the user asked for — "the list of things
 * you copied now and had copied before", reusable from the Encrypt and
 * Decrypt tabs.
 *
 * POLICY (all enforced here, unit-tested):
 *  - entries arrive ONLY via [record], called from a focus-gated or
 *    user-initiated read (never a background listener — Android 10+ would
 *    deny it anyway; see ClipboardAccess's security contract);
 *  - blank text is ignored;
 *  - the stored text is capped at [MAX_STORED_CHARS] characters (a
 *    multi-megabyte copy must never bloat the database); [truncated] tells
 *    the UI the original clip was longer;
 *  - re-copying known text MOVES it to the top (delete-then-insert), so the
 *    list reads like a recents list, not a log;
 *  - the history is pruned to the newest [MAX_ENTRIES] entries and entries
 *    older than [MAX_AGE_MILLIS] are dropped on every write.
 *
 * PRIVACY: decrypted plaintext and suggested passphrases are copied through
 * the SENSITIVE clipboard path and are never passed to [record] — the
 * recorder explicitly marks them as seen instead (see
 * com.turnypt.app.clipboard.ClipboardHistoryRecorder).
 */
class ClipboardHistoryRepository(
    private val dao: ClipboardHistoryDao,
    private val timeSource: TimeSource,
    private val dispatchers: DispatcherProvider,
) {

    companion object {
        /** Newest-first cap ("last 25 things you copied"). */
        const val MAX_ENTRIES = 25

        /** A week, in millis. */
        const val MAX_AGE_MILLIS: Long = 7L * 24L * 60L * 60L * 1000L

        /** Per-entry storage cap — envelopes of normal messages fit comfortably. */
        const val MAX_STORED_CHARS = 2000
    }

    /** Newest-first snapshot flow for the history dialog. */
    val entries: Flow<List<ClipboardHistoryEntry>> = dao.observeAll()
        .map { list -> list.map { it.toDomain() } }

    /**
     * Records a clip (move-to-top semantics). Returns false when the text
     * was blank. Called ONLY with non-sensitive text.
     */
    suspend fun record(text: String): Boolean = withContext(dispatchers.io) {
        if (text.isBlank()) return@withContext false
        val stored = text.take(MAX_STORED_CHARS)
        dao.deleteByText(stored)
        dao.upsert(
            ClipboardHistoryEntity(
                text = stored,
                isEnvelope = Ck1.isEnvelope(stored.trim()),
                truncated = text.length > MAX_STORED_CHARS,
                capturedAt = timeSource.nowMillis(),
            ),
        )
        prune()
        true
    }

    /** Removes one entry (the per-row ✕ in the history dialog). */
    suspend fun delete(id: Long) = withContext(dispatchers.io) {
        dao.deleteById(id)
    }

    /** Wipes the whole history (the "Clear history" action). */
    suspend fun clear() = withContext(dispatchers.io) {
        dao.clearAll()
    }

    /** Newest-first one-shot read for dialog refreshes. */
    suspend fun snapshot(): List<ClipboardHistoryEntry> = withContext(dispatchers.io) {
        dao.all().map { it.toDomain() }
    }

    private suspend fun prune() {
        val now = timeSource.nowMillis()
        dao.pruneOlderThan(now - MAX_AGE_MILLIS)
        dao.keepNewest(MAX_ENTRIES)
    }

    private fun ClipboardHistoryEntity.toDomain() = ClipboardHistoryEntry(
        id = id,
        text = text,
        isEnvelope = isEnvelope,
        truncated = truncated,
        capturedAt = capturedAt,
    )
}
