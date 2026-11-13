package com.turnypt.core.sessions

/**
 * Wall-clock source, injectable so that session-expiry logic is deterministic
 * under test.
 */
fun interface TimeSource {

    fun nowMillis(): Long

    companion object {
        val SYSTEM: TimeSource = TimeSource { System.currentTimeMillis() }
    }
}

/** Best-effort zeroing of a passphrase buffer after use. */
fun CharArray.wipe() {
    java.util.Arrays.fill(this, '\u0000')
}
