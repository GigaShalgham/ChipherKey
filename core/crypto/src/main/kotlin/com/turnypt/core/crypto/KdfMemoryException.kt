package com.turnypt.core.crypto

/**
 * v0.5.1: the Argon2id profile asked for more heap than this JVM/Android
 * process can ever provide.
 *
 * BouncyCastle's [Argon2BytesGenerator] allocates the ENTIRE memory
 * parameter as one `int[]` (`memoryKib * 256` words = `memoryKib * 1024`
 * bytes) before the first pass — a 256 MiB profile (the PARANOID preset)
 * needs a contiguous 256 MiB array. On a stock Android heap (128–256 MB
 * without `largeHeap`) that allocation throws [OutOfMemoryError], which
 * used to escape as an `Error` and kill the process mid-unlock.
 *
 * This exception is the typed, catchable form: the repository maps it to a
 * graceful [com.turnypt.data.repository.UnlockOutcome.MemoryHungry] so the
 * UI can honestly say "this profile does not fit this device's memory —
 * pick a lower strength" instead of crashing.
 */
class KdfMemoryException(val requiredBytes: Long) : Exception(
    "Argon2id requires $requiredBytes bytes of heap for this profile"
)

/**
 * v0.5.1: pure heap arithmetic for the Argon2id pre-flight check.
 *
 * Kept as a separate object (no Runtime access) so the budget math is
 * unit-testable on the JVM.
 */
object Argon2MemoryGuard {

    /**
     * BouncyCastle allocates `int[memoryKib * 256]` — exactly 1024 bytes
     * per KiB of the profile's memory parameter — plus modest working
     * state (block indices, the pending buffer, GCM outputs).
     */
    const val HEADROOM = 1.25

    /** The single-allocation size the profile forces, in bytes. */
    fun requiredHeapBytes(params: Argon2Params): Long = params.memoryKib.toLong() * 1024L

    /**
     * True when a heap capped at [maxHeapBytes] can serve [params] with
     * the [HEADROOM] safety margin. `Long.MAX_VALUE` means "unbounded"
     * (a desktop JVM `-Xmx` setup that never rejects).
     */
    fun fits(maxHeapBytes: Long, params: Argon2Params): Boolean {
        if (maxHeapBytes == Long.MAX_VALUE) return true
        val required = requiredHeapBytes(params)
        // Overflow-safe: required * HEADROOM <= maxHeapBytes
        return required <= maxHeapBytes / HEADROOM
    }
}
