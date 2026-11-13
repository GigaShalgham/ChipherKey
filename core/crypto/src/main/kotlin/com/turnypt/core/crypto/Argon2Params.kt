package com.turnypt.core.crypto

/**
 * Argon2id parameters (RFC 9106), restricted to the ranges Turnypt is
 * willing to place on the wire.
 *
 * Memory is encoded as a power-of-two exponent in KiB to keep the envelope
 * compact and canonical: memory = 2^[memoryExponent] KiB.
 *
 * Security/performance trade-off (documented in docs/THREAT_MODEL.md):
 * the default shipped policy derives keys with 64 MiB / 3 passes / 1 lane,
 * which corresponds to RFC 9106's "second recommended option" scaled for
 * interactive mobile use.
 */
data class Argon2Params(
    val memoryExponent: Int,
    val iterations: Int,
    val parallelism: Int,
) {
    init {
        require(memoryExponent in MIN_MEMORY_EXPONENT..MAX_MEMORY_EXPONENT) {
            "memory exponent must be in $MIN_MEMORY_EXPONENT..$MAX_MEMORY_EXPONENT (KiB, power of two)"
        }
        require(iterations in 1..MAX_ITERATIONS) { "iterations must be in 1..$MAX_ITERATIONS" }
        require(parallelism in 1..MAX_PARALLELISM) { "parallelism must be in 1..$MAX_PARALLELISM" }
        // RFC 9106: memory must be at least 8*p KiB.
        require(1 shl memoryExponent >= 8 * parallelism) {
            "memory (2^$memoryExponent KiB) must be at least 8*parallelism KiB"
        }
    }

    /** Memory in KiB. */
    val memoryKib: Int get() = 1 shl memoryExponent

    /**
     * Canonical binary encoding used both inside the CK1 envelope and the
     * CKS1 invite: exponent (1 byte) || iterations (4 bytes BE) ||
     * parallelism (2 bytes BE).
     */
    fun encode(): ByteArray {
        val out = ByteArray(ENCODED_LENGTH)
        out[0] = memoryExponent.toByte()
        out[1] = (iterations ushr 24).toByte()
        out[2] = (iterations ushr 16).toByte()
        out[3] = (iterations ushr 8).toByte()
        out[4] = iterations.toByte()
        out[5] = (parallelism ushr 8).toByte()
        out[6] = parallelism.toByte()
        return out
    }

    companion object {
        const val ENCODED_LENGTH = 7
        const val MIN_MEMORY_EXPONENT = 3 // 8 KiB — test vectors only
        const val MAX_MEMORY_EXPONENT = 22 // 4 GiB — hard upper bound
        const val MAX_ITERATIONS = 65_536
        const val MAX_PARALLELISM = 64

        fun decode(bytes: ByteArray): Argon2Params {
            if (bytes.size != ENCODED_LENGTH) throw IllegalArgumentException("bad Argon2Params length")
            val exponent = bytes[0].toInt() and 0xFF
            val iterations = ((bytes[1].toInt() and 0xFF) shl 24) or
                ((bytes[2].toInt() and 0xFF) shl 16) or
                ((bytes[3].toInt() and 0xFF) shl 8) or
                (bytes[4].toInt() and 0xFF)
            val parallelism = ((bytes[5].toInt() and 0xFF) shl 8) or (bytes[6].toInt() and 0xFF)
            return Argon2Params(exponent, iterations, parallelism)
        }
    }
}
