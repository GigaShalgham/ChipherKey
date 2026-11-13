package org.cipherkey.core.crypto

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * CKF1 — the CipherKey File format, version 1 (v0.6.0).
 *
 * A self-contained, chunk-wise-authenticated container for payloads far
 * larger than any chat message (images today; any file later). A `.cki`
 * file is a normal attachment: send it over any messenger, save it
 * anywhere. docs/IMAGE-CRYPTO.md §3 carries the design rationale.
 *
 * Layout (all multi-byte integers big-endian):
 *
 * ```
 * offset size field
 * 0      4    magic "CKF1" (0x43 0x4B 0x46 0x31)
 * 4      55   the exact CK1 v1 header: version 0x01 | mode 0x01 | cipher id
 *            (0x01 AES-256-GCM / 0x02 ChaCha20-Poly1305) | kdf id | keyId(16)
 *            | Argon2 params(7) | salt(16) | base nonce(12)
 * 59     ...  sealed chunks, in order; each is AEAD(chunk ‖ 16-byte tag)
 * ```
 *
 * Chunk rules (the security core of the format):
 *
 *  - plaintext is split into [CHUNK_PLAINTEXT_BYTES] chunks;
 *  - a per-file subkey is derived FIRST:
 *    `fileKey = HKDF-SHA256(ikm = era key, salt = base nonce, info = HKDF_INFO)`
 *    so the 4+8-byte chunk nonces below can never collide across files
 *    under the same era key (the Tink streaming-AEAD argument);
 *  - chunk i nonce = `baseNonce[0..4) ‖ i (8 bytes BE)`;
 *  - chunk i AAD = `prefix(59) ‖ i (8 bytes BE) ‖ lastFlag(1)` — every
 *    chunk is bound to this file's header, its position and its finality:
 *    reordering, splicing and truncation all fail authentication;
 *  - the FINAL chunk is strictly SHORTER than the chunk size; an input
 *    whose size is an exact multiple gets a zero-length final chunk as
 *    the truncation sentinel. A stream ending without a short chunk is
 *    [CryptoError.FileTruncated];
 *  - peak memory is one chunk (~64 KiB + buffers): a multi-hundred-MiB
 *    input can never OOM the heap (the v0.5.1 lesson, by construction).
 */
object Ckf1 {

    /** "CKF1" — the file magic (byteArrayOf is not const-able in Kotlin). */
    val MAGIC: ByteArray = byteArrayOf(0x43, 0x4B, 0x46, 0x31)
    const val CHUNK_PLAINTEXT_BYTES: Int = 64 * 1024

    /** magic (4) + the shared CK1 header (55). */
    const val PREFIX_LENGTH: Int = 4 + ProtocolHeader.PASSPHRASE_HEADER_LENGTH

    /** HKDF `info` — binds the file subkey to this format and version. */
    val HKDF_INFO: ByteArray = "CipherKey-CKF1-v1".toByteArray(Charsets.US_ASCII)

    /** Suggested extension for encrypted files. */
    const val FILE_EXTENSION: String = "cki"

    /** MIME type used when sharing .cki files. */
    const val MIME_TYPE: String = "application/x-cipherkey"

    fun hasMagic(bytes: ByteArray, from: Int = 0): Boolean {
        if (bytes.size < from + 4) return false
        return MAGIC[0] == bytes[from] &&
            MAGIC[1] == bytes[from + 1] &&
            MAGIC[2] == bytes[from + 2] &&
            MAGIC[3] == bytes[from + 3]
    }

    /**
     * Parses and validates the 59-byte prefix: magic + the shared CK1
     * header field decoder (version, mode, cipher, kdf, keyId, Argon2
     * params, salt, base nonce). Throws [AeadFailure] with the same
     * taxonomy as the text envelope path — before any crypto work.
     */
    fun parsePrefix(prefix: ByteArray): EncryptedMessage {
        if (prefix.size < PREFIX_LENGTH || !hasMagic(prefix)) {
            throw AeadFailure(CryptoError.NotACipherFile)
        }
        return ProtocolHeader.parseHeaderFields(prefix, from = 4)
    }
}

/**
 * Streaming chunked AEAD for CKF1 files (v0.6.0). Pure java.io streams —
 * no Android dependency, fully JVM-unit-testable. The caller owns the
 * streams (and closes them); this class never buffers a whole file.
 */
class CkfChunkedCipher(
    private val aeads: AeadCiphers = AeadCiphers(),
) {

    /**
     * Streams [input] into a CKF1 container on [output] under the era's
     * key material. The [baseNonce] (12 bytes, fresh per file) is the HKDF
     * salt and the source of every chunk nonce prefix.
     */
    fun encrypt(
        key: ByteArray,
        keyId: ByteArray,
        salt: ByteArray,
        params: Argon2Params,
        algorithm: CipherAlgorithm,
        baseNonce: ByteArray,
        input: InputStream,
        output: OutputStream,
        onProgress: (plaintextBytes: Long) -> Unit = {},
    ) {
        if (key.size != algorithm.keyLengthBytes) throw AeadFailure(CryptoError.InvalidKey)
        if (baseNonce.size != algorithm.nonceLengthBytes) throw AeadFailure(CryptoError.InvalidNonce)

        val header = ProtocolHeader.serialize(
            EncryptedMessage(
                mode = EncryptionMode.PASSPHRASE,
                algorithm = algorithm,
                kdf = KdfAlgorithm.ARGON2ID,
                keyId = keyId,
                argon2Params = params,
                salt = salt,
                nonce = baseNonce,
                ciphertext = ByteArray(0),
            ),
        )
        val prefix = Ckf1.MAGIC + header
        val fileKey = Hkdf.sha256(
            ikm = key,
            salt = baseNonce,
            info = Ckf1.HKDF_INFO,
            lengthBytes = algorithm.keyLengthBytes,
        )
        val aead = aeads.of(algorithm)

        try {
            output.write(prefix)
            val chunk = ByteArray(Ckf1.CHUNK_PLAINTEXT_BYTES)
            var counter = 0L
            var plaintextBytes = 0L
            while (true) {
                // Read one chunk fully (short read = EOF = final chunk).
                var filled = 0
                while (filled < Ckf1.CHUNK_PLAINTEXT_BYTES) {
                    val read = input.read(chunk, filled, Ckf1.CHUNK_PLAINTEXT_BYTES - filled)
                    if (read == -1) break
                    filled += read
                }
                val last = filled < Ckf1.CHUNK_PLAINTEXT_BYTES
                val sealed = aead.seal(
                    key = fileKey,
                    nonce = chunkNonce(baseNonce, counter),
                    plaintext = chunk.copyOf(filled),
                    associatedData = chunkAad(prefix, counter, last),
                )
                output.write(sealed)
                plaintextBytes += filled
                onProgress(plaintextBytes)
                counter++
                if (last) break
                // A full chunk followed by EOF emits an empty final chunk
                // on the NEXT iteration — the truncation sentinel.
            }
            output.flush()
        } catch (e: IOException) {
            throw AeadFailure(CryptoError.MalformedMessage, e)
        }
    }

    /**
     * Streams a CKF1 container from [input], writing authenticated
     * plaintext to [output]. The FULL file is authenticated before any
     * byte becomes part of the result: a failure anywhere deletes the
     * caller's partial output (the repository layer owns that cleanup).
     */
    fun decrypt(
        input: InputStream,
        output: OutputStream,
        key: ByteArray,
        onProgress: (plaintextBytes: Long) -> Unit = {},
    ) {
        val prefix = ByteArray(Ckf1.PREFIX_LENGTH)
        if (!readFully(input, prefix)) throw AeadFailure(CryptoError.MalformedMessage)
        val header = Ckf1.parsePrefix(prefix)
        val algorithm = header.algorithm
        if (key.size != algorithm.keyLengthBytes) throw AeadFailure(CryptoError.InvalidKey)

        val fileKey = Hkdf.sha256(
            ikm = key,
            salt = header.nonce,
            info = Ckf1.HKDF_INFO,
            lengthBytes = algorithm.keyLengthBytes,
        )
        val aead = aeads.of(algorithm)

        try {
            val sealed = ByteArray(Ckf1.CHUNK_PLAINTEXT_BYTES + algorithm.tagLengthBytes)
            var counter = 0L
            var plaintextBytes = 0L
            while (true) {
                val sealedLength = readUpTo(input, sealed)
                if (sealedLength == -1) {
                    // EOF without a short final chunk: the file was cut.
                    throw AeadFailure(CryptoError.FileTruncated)
                }
                if (sealedLength < algorithm.tagLengthBytes) {
                    throw AeadFailure(CryptoError.MalformedMessage)
                }
                val plaintextLength = sealedLength - algorithm.tagLengthBytes
                // Format rule: only the final chunk may be short.
                val last = plaintextLength < Ckf1.CHUNK_PLAINTEXT_BYTES
                val plaintext = aead.open(
                    key = fileKey,
                    nonce = chunkNonce(header.nonce, counter),
                    sealed = sealed.copyOf(sealedLength),
                    associatedData = chunkAad(prefix, counter, last),
                )
                output.write(plaintext)
                plaintextBytes += plaintext.size
                onProgress(plaintextBytes)
                if (last) break
                counter++
            }
            // The final chunk authenticated — nothing may follow it.
            if (input.read() != -1) throw AeadFailure(CryptoError.MalformedMessage)
            output.flush()
        } catch (e: IOException) {
            throw AeadFailure(CryptoError.MalformedMessage, e)
        }
    }

    /** chunk nonce = baseNonce[0..4) ‖ counter (8 bytes BE). */
    private fun chunkNonce(baseNonce: ByteArray, counter: Long): ByteArray {
        val nonce = ByteArray(12)
        baseNonce.copyInto(nonce, 0, 0, 4)
        nonce[4] = (counter ushr 56).toByte()
        nonce[5] = (counter ushr 48).toByte()
        nonce[6] = (counter ushr 40).toByte()
        nonce[7] = (counter ushr 32).toByte()
        nonce[8] = (counter ushr 24).toByte()
        nonce[9] = (counter ushr 16).toByte()
        nonce[10] = (counter ushr 8).toByte()
        nonce[11] = counter.toByte()
        return nonce
    }

    /** chunk AAD = file prefix ‖ counter (8 bytes BE) ‖ last-flag. */
    private fun chunkAad(prefix: ByteArray, counter: Long, last: Boolean): ByteArray {
        val aad = ByteArray(prefix.size + 9)
        prefix.copyInto(aad, 0)
        val offset = prefix.size
        aad[offset] = (counter ushr 56).toByte()
        aad[offset + 1] = (counter ushr 48).toByte()
        aad[offset + 2] = (counter ushr 40).toByte()
        aad[offset + 3] = (counter ushr 32).toByte()
        aad[offset + 4] = (counter ushr 24).toByte()
        aad[offset + 5] = (counter ushr 16).toByte()
        aad[offset + 6] = (counter ushr 8).toByte()
        aad[offset + 7] = counter.toByte()
        aad[offset + 8] = if (last) 1 else 0
        return aad
    }

    /** Blocking full read; false when EOF hits before [target] is full. */
    private fun readFully(input: InputStream, target: ByteArray): Boolean {
        var filled = 0
        while (filled < target.size) {
            val read = input.read(target, filled, target.size - filled)
            if (read == -1) return false
            filled += read
        }
        return true
    }

    /**
     * Reads up to [target].size bytes; -1 on immediate EOF. Chunk frames
     * are exactly CHUNK or CHUNK+tag sized, so one pass reads one frame.
     */
    private fun readUpTo(input: InputStream, target: ByteArray): Int {
        var filled = 0
        while (filled < target.size) {
            val read = input.read(target, filled, target.size - filled)
            if (read == -1) break
            filled += read
        }
        return if (filled == 0) -1 else filled
    }
}
