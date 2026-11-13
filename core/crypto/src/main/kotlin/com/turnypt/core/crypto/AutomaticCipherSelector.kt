package com.turnypt.core.crypto

/**
 * v0.6.0: resolves the "Automatic" cipher preference by MEASURING this
 * device instead of guessing from a CPU-name table.
 *
 * AES-256-GCM is hardware-accelerated on every ARMv8 SoC (the crypto
 * extensions) and on x86 (AES-NI + PCLMUL): there it reaches multiple
 * GB/s and beats ChaCha20. Devices without the unit (older ARMv7 SoCs,
 * odd cores) must run constant-time software AES, and there ChaCha20's
 * ARX design is the clear winner — same security, much faster.
 *
 * The benchmark seals 256 KiB with each implementation (throwaway key and
 * nonce, nothing user-derived) and picks the faster one. It reflects the
 * REAL conditions — hardware units, the JIT, the bundled ChaCha
 * implementation — and is cached per process. Security is never part of
 * the choice: both are AEAD with a 256-bit key and a 128-bit tag, and
 * both decrypt each other's messages (the algorithm travels in every
 * envelope and file header).
 *
 * Pure JVM: identical behavior in unit tests and on Android.
 */
class AutomaticCipherSelector(
    private val aeads: AeadCiphers = AeadCiphers(),
    private val benchmarkBytes: Int = DEFAULT_BENCHMARK_BYTES,
) {

    @Volatile
    private var cached: CipherAlgorithm? = null

    /** The faster cipher on this device (cached after the first call). */
    fun select(): CipherAlgorithm = cached ?: benchmark().also { cached = it }

    /**
     * The cached choice without triggering a benchmark (for UI labels:
     * "Automatic — currently AES-256-GCM"); null before the first select().
     */
    fun peek(): CipherAlgorithm? = cached

    private fun benchmark(): CipherAlgorithm {
        if (benchmarkBytes <= 0) return CipherAlgorithm.AES_256_GCM
        val data = ByteArray(benchmarkBytes)
        val key = ByteArray(CipherAlgorithm.AES_256_GCM.keyLengthBytes)
        val nonce = ByteArray(CipherAlgorithm.AES_256_GCM.nonceLengthBytes)
        return try {
            val aes = measure { aeads.of(CipherAlgorithm.AES_256_GCM).seal(key, nonce, data, EMPTY) }
            val chacha = measure { aeads.of(CipherAlgorithm.CHACHA20_POLY1305).seal(key, nonce, data, EMPTY) }
            if (aes <= chacha) CipherAlgorithm.AES_256_GCM else CipherAlgorithm.CHACHA20_POLY1305
        } catch (e: Exception) {
            // AES-GCM via JCA is always present (it is the existing v0.1
            // path); if anything unexpected happens, stay on it.
            CipherAlgorithm.AES_256_GCM
        }
    }

    private inline fun measure(block: () -> ByteArray): Long {
        // Warm-up: JIT-compile the hot path so the measurement compares
        // steady-state throughput, not interpreter overhead.
        repeat(WARMUP_ROUNDS) { block() }
        val start = System.nanoTime()
        block()
        return System.nanoTime() - start
    }

    private companion object {
        val EMPTY = ByteArray(0)
        const val WARMUP_ROUNDS = 3
        const val DEFAULT_BENCHMARK_BYTES = 256 * 1024
    }
}
