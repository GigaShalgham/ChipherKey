package com.turnypt.app.security

import java.io.File
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Singleton
import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * v0.3.0 fingerprint-gated key storage.
 *
 * DESIGN — why this is safe even though it stores key material on disk:
 *  - the ONLY thing ever written is the raw session key wrapped with an
 *    AES-256-GCM cipher whose key lives in the Android Keystore and is
 *    flagged `setUserAuthenticationRequired(true)` — Android refuses to use
 *    it (and the system BiometricPrompt must run) before the wrap or unwrap
 *    can happen;
 *  - the AAD of every wrap is the biometric contact key
 *    (`"$contactId:$eraId"`), so a wrap can never open a DIFFERENT contact or
 *    a rotated key era — GCM authentication fails and the entry is cleared;
 *  - blob format is `IV(12) || ciphertext+tag`, minimum length 28 bytes;
 *  - disabling the feature ([clearAll]) deletes every blob AND the Keystore
 *    wrap key itself — nothing encrypted under the old key is recoverable.
 *
 * All failures are reported as null/false return values; nothing throws to
 * callers (the UX degrades to the passphrase path, which always works).
 */
interface BiometricKeyStore {

    /**
     * True when this device can authenticate with Class 3 (Strong)
     * biometrics — the only class that can authorize the Keystore wrap key.
     */
    fun isDeviceCapable(): Boolean

    /**
     * The raw canAuthenticate(BIOMETRIC_STRONG) result (v0.4.1). The UI maps
     * it to an honest reason via [com.turnypt.app.security.BiometricDiagnostics]
     * instead of guessing "nothing enrolled".
     */
    fun strongAuthResult(): Int

    /** True when a wrapped blob exists for [contactKey]. */
    fun hasWrapped(contactKey: String): Boolean

    /** The wrapped blob, or null when absent/unreadable. */
    fun readWrapped(contactKey: String): ByteArray?

    /** Best-effort persist of a wrapped blob. */
    fun persistWrapped(contactKey: String, blob: ByteArray)

    /** Best-effort removal of one contact's blob (e.g. wrap gone stale). */
    fun clear(contactKey: String)

    /** Wipes every blob and the Keystore wrap key (feature disabled). */
    fun clearAll()

    /**
     * A cipher in WRAP mode initialized with the Keystore key, or null when
     * the Keystore is unavailable. Must be used inside BiometricPrompt's
     * CryptoObject so the fingerprint authorizes the operation.
     */
    fun newWrapCipher(): Cipher?

    /**
     * A cipher in UNWRAP mode initialized with the blob's IV, or null for a
     * short/garbage blob or an unavailable Keystore.
     */
    fun newUnwrapCipher(blob: ByteArray): Cipher?

    /** Runs the wrap: AAD first, then the plaintext; returns IV||ciphertext. */
    fun finishWrap(cipher: Cipher, plain: ByteArray, aad: ByteArray): ByteArray

    /** Runs the unwrap: AAD first, then blob[12..]; returns the plaintext. */
    fun finishUnwrap(cipher: Cipher, blob: ByteArray, aad: ByteArray): ByteArray
}

@Singleton
class AndroidBiometricKeyStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : BiometricKeyStore {

    private var cachedKey: SecretKey? = null

    override fun isDeviceCapable(): Boolean =
        strongAuthResult() == BiometricManager.BIOMETRIC_SUCCESS

    override fun strongAuthResult(): Int =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS)

    override fun hasWrapped(contactKey: String): Boolean = fileFor(contactKey).exists()

    override fun readWrapped(contactKey: String): ByteArray? = try {
        val file = fileFor(contactKey)
        if (file.exists()) file.readBytes() else null
    } catch (e: Exception) {
        null
    }

    override fun persistWrapped(contactKey: String, blob: ByteArray) {
        try {
            fileFor(contactKey).writeBytes(blob)
        } catch (e: Exception) {
            // Best-effort: a failed persist only costs the convenience.
        }
    }

    override fun clear(contactKey: String) {
        fileFor(contactKey).delete()
    }

    override fun clearAll() {
        dir().listFiles()?.forEach { it.delete() }
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            keyStore.deleteEntry(WRAP_KEY_ALIAS)
        } catch (e: Exception) {
            // A missing/unavailable Keystore entry needs no cleanup.
        }
        synchronized(this) { cachedKey = null }
    }

    override fun newWrapCipher(): Cipher? = try {
        Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, wrapKey()) }
    } catch (e: Exception) {
        null
    }

    override fun newUnwrapCipher(blob: ByteArray): Cipher? {
        if (blob.size < MIN_BLOB_LENGTH) return null
        return try {
            val iv = blob.copyOfRange(0, IV_LENGTH)
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(TAG_BITS, iv))
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun finishWrap(cipher: Cipher, plain: ByteArray, aad: ByteArray): ByteArray =
        try {
            cipher.updateAAD(aad)
            val sealed = cipher.doFinal(plain)
            val iv = cipher.iv
            check(iv != null && iv.size == IV_LENGTH) { "bad IV" }
            iv + sealed
        } catch (e: Exception) {
            throw IllegalStateException("wrap failed", e)
        }

    override fun finishUnwrap(cipher: Cipher, blob: ByteArray, aad: ByteArray): ByteArray =
        try {
            val sealed = blob.copyOfRange(IV_LENGTH, blob.size)
            cipher.updateAAD(aad)
            cipher.doFinal(sealed)
        } catch (e: Exception) {
            throw IllegalStateException("unwrap failed", e)
        }

    // ------------------------------------------------------------ internals

    private fun dir(): File =
        File(context.filesDir, "biometric").apply { mkdirs() }

    /**
     * One file per biometric contact key. The key contains only safe
     * characters (`[A-Za-z0-9_-]`, everything else replaced) so it is a
     * filename, never a path.
     */
    private fun fileFor(contactKey: String): File =
        File(dir(), UNSAFE_FILENAME_CHARS.replace(contactKey, "_") + ".bin")

    private fun wrapKey(): SecretKey {
        cachedKey?.let { return it }
        synchronized(this) {
            cachedKey?.let { return it }
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = (keyStore.getEntry(WRAP_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)
                ?.secretKey
            val key = existing ?: generateWrapKey()
            cachedKey = key
            return key
        }
    }

    private fun generateWrapKey(): SecretKey {
        val generator = KeyGenerator.getInstance("AES", ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            WRAP_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setUserAuthenticationRequired(true)
            .apply {
                if (Build.VERSION.SDK_INT >= 30) {
                    setUserAuthenticationParameters(
                        /* timeout = */ 0,
                        KeyProperties.AUTH_BIOMETRIC_STRONG,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(-1)
                }
            }
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val WRAP_KEY_ALIAS = "turnypt.biometric.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        private const val IV_LENGTH = 12
        private const val TAG_BITS = 128
        private const val MIN_BLOB_LENGTH = IV_LENGTH + TAG_BITS / 8
        /**
         * v0.4.1: STRONG only. The wrap key is created with
         * AUTH_BIOMETRIC_STRONG (below), so the gate must accept exactly the
         * class that can authorize the cipher — v0.4.0 asked for WEAK|STRONG
         * here while the key demanded STRONG, so a Class 2 sensor passed the
         * gate and then could never authorize the wrap.
         */
        private const val AUTHENTICATORS =
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        private val UNSAFE_FILENAME_CHARS = Regex("[^A-Za-z0-9_-]")
    }
}
