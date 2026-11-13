package com.turnypt.security.policies

import com.turnypt.core.crypto.Argon2Params

/**
 * Named Argon2id parameter profiles offered to the user.
 *
 * Memory is a power of two (see [Argon2Params]); the values below are chosen
 * for interactive use on consumer hardware:
 *
 *  - INTERACTIVE: 16 MiB / 3 passes — low-end devices, faster unlock.
 *  - BALANCED:    64 MiB / 3 passes — the shipped default; matches the
 *                 recommendation documented in docs/THREAT_MODEL.md.
 *  - PARANOID:    256 MiB / 4 passes — hostile-device assumption.
 *
 * All profiles use a single lane (parallelism = 1) so that derivation cost is
 * deterministic and independent of the device's core count.
 */
enum class Argon2Profile(val params: Argon2Params) {

    INTERACTIVE(Argon2Params(memoryExponent = 14, iterations = 3, parallelism = 1)),
    BALANCED(Argon2Params(memoryExponent = 16, iterations = 3, parallelism = 1)),
    PARANOID(Argon2Params(memoryExponent = 18, iterations = 4, parallelism = 1)),
    ;

    companion object {
        /** The profile applied to newly created session eras. */
        val DEFAULT: Argon2Profile = BALANCED

        fun fromName(name: String?): Argon2Profile? =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}
