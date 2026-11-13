package com.turnypt.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.turnypt.security.policies.Argon2Profile
import com.turnypt.security.policies.KeyPolicy

private val Context.turnyptDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "turnypt_settings",
)

/**
 * v0.6.0: the user's cipher preference for NEW encryptions (text and
 * files alike). The algorithm travels in every envelope and file header,
 * so this is purely a SENDING choice — decryption always follows the wire
 * and never depends on it.
 */
enum class CipherPreference {
    /** Measure this device once and use the faster cipher (default). */
    AUTO,

    /** Always AES-256-GCM (hardware-accelerated on modern SoCs). */
    AES_256_GCM,

    /** Always ChaCha20-Poly1305 (constant-time, fast in pure software). */
    CHACHA20_POLY1305,

    ;

    companion object {
        fun fromName(name: String?): CipherPreference? =
            entries.firstOrNull { it.name == name }
    }
}

/**
 * User-facing security settings.
 *
 * Defaults come from [KeyPolicy]/[Argon2Profile]; persisted values are
 * re-validated on read so that a corrupted or forged store cannot loosen the
 * policy.
 */
data class AppSettings(
    val sessionTimeoutMillis: Long = KeyPolicy.DEFAULT_SESSION_TIMEOUT_MILLIS,
    val argon2Profile: Argon2Profile = Argon2Profile.DEFAULT,
    val onboardingCompleted: Boolean = false,
    /** v0.3.0: remember unlocked keys behind a fingerprint-gated wrap. */
    val biometricUnlockEnabled: Boolean = false,
    /** v0.3.0: floating assistant overlay (opt-in, off by default). */
    val assistantEnabled: Boolean = false,
    /** v0.5.2: clipboard history capture (on by default; sensitive copies are never stored). */
    val clipboardHistoryEnabled: Boolean = true,
    /** v0.6.0: cipher used for NEW encryptions (default Automatic). */
    val preferredCipher: CipherPreference = CipherPreference.AUTO,
)

class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
) {

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            // nearestTimeoutChoice: a persisted value that is not one of the
            // picker choices (e.g. v0.2's 1 h cap) still maps to a row.
            sessionTimeoutMillis = KeyPolicy.nearestTimeoutChoice(
                prefs[KEY_SESSION_TIMEOUT] ?: KeyPolicy.DEFAULT_SESSION_TIMEOUT_MILLIS,
            ),
            argon2Profile = Argon2Profile.fromName(prefs[KEY_ARGON2_PROFILE])
                ?: Argon2Profile.DEFAULT,
            onboardingCompleted = prefs[KEY_ONBOARDING_COMPLETED] ?: false,
            biometricUnlockEnabled = prefs[KEY_BIOMETRIC_UNLOCK] ?: false,
            assistantEnabled = prefs[KEY_ASSISTANT_ENABLED] ?: false,
            clipboardHistoryEnabled = prefs[KEY_CLIPBOARD_HISTORY] ?: true,
            preferredCipher = CipherPreference.fromName(prefs[KEY_PREFERRED_CIPHER])
                ?: CipherPreference.AUTO,
        )
    }

    suspend fun setSessionTimeoutMillis(requestedMillis: Long) {
        dataStore.edit { prefs ->
            prefs[KEY_SESSION_TIMEOUT] = KeyPolicy.clampSessionTimeout(requestedMillis)
        }
    }

    suspend fun setArgon2Profile(profile: Argon2Profile) {
        dataStore.edit { prefs ->
            prefs[KEY_ARGON2_PROFILE] = profile.name
        }
    }

    suspend fun setOnboardingCompleted() {
        dataStore.edit { prefs ->
            prefs[KEY_ONBOARDING_COMPLETED] = true
        }
    }

    /** v0.3.0: toggles fingerprint-gated key wrapping (see app/security). */
    suspend fun setBiometricUnlockEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_BIOMETRIC_UNLOCK] = enabled
        }
    }

    /** v0.3.0: toggles the floating assistant service. */
    suspend fun setAssistantEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_ASSISTANT_ENABLED] = enabled
        }
    }

    /** v0.5.2: toggles clipboard-history capture (never stores sensitive copies). */
    suspend fun setClipboardHistoryEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_CLIPBOARD_HISTORY] = enabled
        }
    }

    /** v0.6.0: the cipher preference for new encryptions. */
    suspend fun setPreferredCipher(preference: CipherPreference) {
        dataStore.edit { prefs ->
            prefs[KEY_PREFERRED_CIPHER] = preference.name
        }
    }

    /**
     * Sticky companion contact: the last contact the user encrypted for,
     * so the PROCESS_TEXT encrypt flow usually needs zero extra taps
     * (spec 003, FR-005).
     *
     * Pure storage — no existence validation here (this module cannot see
     * Room). Callers must clamp: if the id no longer matches a contact,
     * treat it as null.
     */
    val lastContactId: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_LAST_CONTACT_ID]
    }

    suspend fun setLastContactId(contactId: String?) {
        dataStore.edit { prefs ->
            if (contactId == null) {
                prefs.remove(KEY_LAST_CONTACT_ID)
            } else {
                prefs[KEY_LAST_CONTACT_ID] = contactId
            }
        }
    }

    companion object {
        private val KEY_SESSION_TIMEOUT = longPreferencesKey("session_timeout_millis")
        private val KEY_ARGON2_PROFILE = stringPreferencesKey("argon2_profile")
        private val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        private val KEY_LAST_CONTACT_ID = stringPreferencesKey("last_contact_id")
        private val KEY_BIOMETRIC_UNLOCK = booleanPreferencesKey("biometric_unlock_enabled")
        private val KEY_ASSISTANT_ENABLED = booleanPreferencesKey("assistant_enabled")
        private val KEY_CLIPBOARD_HISTORY = booleanPreferencesKey("clipboard_history_enabled")
        private val KEY_PREFERRED_CIPHER = stringPreferencesKey("preferred_cipher")

        /** Factory for the process-wide DataStore instance. */
        fun defaultDataStore(context: Context): DataStore<Preferences> = context.turnyptDataStore
    }
}
