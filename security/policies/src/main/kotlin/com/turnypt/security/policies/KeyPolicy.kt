package com.turnypt.security.policies

/**
 * Central security policy constants for key material and sessions.
 *
 * Every consumer (app UI, keyboard UI, repositories) validates against this
 * object so that the enforced limits exist in exactly one place.
 */
object KeyPolicy {

    /** Minimum accepted shared-passphrase length, in characters. */
    const val MIN_PASSPHRASE_CHARS = 8

    /** Hard upper bound for passphrase length (protects the KDF input). */
    const val MAX_PASSPHRASE_CHARS = 1024

    /** Default in-memory session lifetime after a successful unlock. */
    const val DEFAULT_SESSION_TIMEOUT_MILLIS: Long = 5 * 60_000L

    /** Shortest user-selectable session timeout. */
    const val MIN_SESSION_TIMEOUT_MILLIS: Long = 60_000L

    /**
     * Longest user-selectable session timeout.
     *
     * v0.3.0: extended from 1 h to 24 h — the timeout picker offers a full
     * day so the floating-assistant quick actions stay usable on a phone
     * that is unlocked in the morning, while still auto-wiping daily.
     */
    const val MAX_SESSION_TIMEOUT_MILLIS: Long = 24 * 60 * 60_000L

    /**
     * The timeout choices offered in Settings, ascending. Values must stay
     * inside [MIN_SESSION_TIMEOUT_MILLIS]..[MAX_SESSION_TIMEOUT_MILLIS].
     */
    val TIMEOUT_CHOICES_MILLIS: List<Long> = listOf(
        60_000L,          // 1 min
        300_000L,         // 5 min
        900_000L,         // 15 min (default)
        3_600_000L,       // 1 h
        14_400_000L,      // 4 h
        43_200_000L,      // 12 h
        86_400_000L,      // 24 h
    )

    /** Clamps a requested timeout into the accepted range. */
    fun clampSessionTimeout(requestedMillis: Long): Long =
        requestedMillis.coerceIn(MIN_SESSION_TIMEOUT_MILLIS, MAX_SESSION_TIMEOUT_MILLIS)

    /**
     * The picker choice closest to [millis] (after clamping), so a persisted
     * value that is not an exact choice — e.g. a 1 h cap carried over from
     * v0.2 settings — still renders as a selected row instead of nothing.
     */
    fun nearestTimeoutChoice(millis: Long): Long {
        val clamped = clampSessionTimeout(millis)
        return TIMEOUT_CHOICES_MILLIS.minBy { kotlin.math.abs(it - clamped) }
    }

    /**
     * Returns a human-readable problem with the passphrase, or null when the
     * passphrase satisfies the policy. Deliberately minimal: length-only.
     * Composition/entropy rules give a false sense of security for shared
     * passphrases typed on phones; guidance text in the UI carries the real
     * advice (long, random, shared in person).
     */
    fun passphraseProblem(passphrase: CharArray): String? = when {
        passphrase.size < MIN_PASSPHRASE_CHARS ->
            "Passphrase must be at least $MIN_PASSPHRASE_CHARS characters"
        passphrase.size > MAX_PASSPHRASE_CHARS ->
            "Passphrase must be at most $MAX_PASSPHRASE_CHARS characters"
        passphrase.all { it.isWhitespace() } ->
            "Passphrase must not consist only of whitespace"
        else -> null
    }
}
