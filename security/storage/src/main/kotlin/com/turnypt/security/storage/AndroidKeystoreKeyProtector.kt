package com.turnypt.security.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.turnypt.core.keys.KeyProtector
import com.turnypt.core.keys.KeyProtectionException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * KeyProtector backed by a non-exportable AES-256-GCM key in the Android
 * Keystore.
 *
 * Purpose (see docs/THREAT_MODEL.md): derived session keys are never stored
 * in the clear. After a successful unlock the derived key is wrapped with
 * this protector and only the wrapped blob reaches the database. The blob is
 * device-bound — the Android Keystore key cannot be extracted — and doubles
 * as an on-device passphrase verifier: unwrapping with the keyId as
 * associated data fails when the wrong passphrase was used.
 *
 * Wire format (identical to FixedKeyProtector in core:keys):
 *   iv(12) || ciphertext||tag(16)
 *
 * Keystore quirk honoured here: for AndroidKeyStore keys in ENCRYPT mode the
 * IV must NOT be caller-supplied; the Keystore generates it and we read it
 * back from the cipher.
 */
class AndroidKeystoreKeyProtector : KeyProtector {

    override fun wrap(plainKey: ByteArray, keyId: ByteArray): ByteArray {
        if (plainKey.isEmpty()) throw KeyProtectionException("refusing to wrap empty key")
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, masterKey())
            cipher.updateAAD(keyId)
            val sealed = cipher.doFinal(plainKey)
            val iv = cipher.iv
            if (iv == null || iv.size != IV_LENGTH) {
                throw KeyProtectionException("unexpected Keystore IV length ${iv?.size}")
            }
            iv + sealed
        } catch (e: KeyProtectionException) {
            throw e
        } catch (e: Exception) {
            throw KeyProtectionException("Keystore wrap failed", e)
        }
    }

    override fun unwrap(wrapped: ByteArray, keyId: ByteArray): ByteArray {
        if (wrapped.size < IV_LENGTH + TAG_LENGTH) {
            throw KeyProtectionException("wrapped blob truncated")
        }
        val iv = wrapped.copyOfRange(0, IV_LENGTH)
        val sealed = wrapped.copyOfRange(IV_LENGTH, wrapped.size)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(keyId)
            cipher.doFinal(sealed)
        } catch (e: Exception) {
            // Deep-scan finding 2: an unwrap failure is a Keystore/blob
            // infrastructure problem (e.g. the master key was invalidated by
            // a system update), never a wrong-passphrase signal — the
            // passphrase is not involved in this operation at all. Evict the
            // cached SecretKey handle so the next attempt reloads (or
            // recreates) it instead of reusing a possibly-invalidated handle
            // forever.
            cachedKey = null
            throw KeyProtectionException("unwrap failed (Keystore error or keyId mismatch)", e)
        }
    }

    @Volatile
    private var cachedKey: SecretKey? = null

    private fun masterKey(): SecretKey {
        cachedKey?.let { return it }
        synchronized(this) {
            cachedKey?.let { return it }
            val key = loadOrCreateMasterKey()
            cachedKey = key
            return key
        }
    }

    private fun loadOrCreateMasterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(MASTER_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                MASTER_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // No setUserAuthenticationRequired: session gating is done by
                // the app's passphrase prompt; biometric binding is future
                // work (see docs/ARCHITECTURE.md).
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val MASTER_KEY_ALIAS = "turnypt.master.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_LENGTH = 16
        const val TAG_BITS = 128
    }
}
