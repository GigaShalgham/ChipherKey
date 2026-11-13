package com.turnypt.app.security

import androidx.biometric.BiometricManager

/**
 * v0.4.1: maps the raw canAuthenticate(BIOMETRIC_STRONG) result to an honest,
 * user-renderable gate.
 *
 * The fingerprint feature wraps keys in a Keystore key created with
 * AUTH_BIOMETRIC_STRONG — only Class 3 (Strong) sensors can authorize that
 * cipher. v0.4.0 accepted WEAK|STRONG at the gate and in the prompt while the
 * key demanded STRONG, so a Class 2 sensor passed the gate and then could
 * never authorize the wrap. The gate, the prompt and the Keystore key now all
 * agree on STRONG, and every failure surfaces a reason instead of silence.
 *
 * Pure int→enum mapping on purpose: unit-testable without any framework
 * (the BiometricManager constants are inlined at compile time, so the test
 * never loads the class).
 */
enum class BiometricGate {
    /** Strong (Class 3) biometrics enrolled and ready. */
    AVAILABLE,

    /** Sensor exists, nothing (strong-class) enrolled. */
    NONE_ENROLLED,

    /** No fingerprint sensor at all. */
    NO_HARDWARE,

    /** Sensor temporarily unavailable (busy / being enrolled). */
    HW_UNAVAILABLE,

    /** A pending security update blocks Keystore-backed auth. */
    SECURITY_UPDATE,

    /** Unexpected result — the sensor can't do Strong-class auth. */
    NOT_STRONG,
}

object BiometricDiagnostics {
    fun gateFor(canAuthenticateResult: Int): BiometricGate = when (canAuthenticateResult) {
        BiometricManager.BIOMETRIC_SUCCESS -> BiometricGate.AVAILABLE
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricGate.NONE_ENROLLED
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricGate.NO_HARDWARE
        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricGate.HW_UNAVAILABLE
        BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> BiometricGate.SECURITY_UPDATE
        else -> BiometricGate.NOT_STRONG
    }

    /**
     * v0.5.1: the localized user-facing reason for a closed gate — shared
     * by the Settings row and the biometric prompt pre-flight (previously
     * duplicated as a private mapping inside SettingsScreen).
     */
    fun reasonRes(gate: BiometricGate): Int = when (gate) {
        BiometricGate.NONE_ENROLLED -> com.turnypt.app.R.string.biometric_reason_none_enrolled
        BiometricGate.NO_HARDWARE -> com.turnypt.app.R.string.biometric_reason_no_hardware
        BiometricGate.HW_UNAVAILABLE -> com.turnypt.app.R.string.biometric_reason_hw_unavailable
        BiometricGate.SECURITY_UPDATE -> com.turnypt.app.R.string.biometric_reason_security_update
        BiometricGate.NOT_STRONG -> com.turnypt.app.R.string.biometric_reason_not_strong
        BiometricGate.AVAILABLE -> com.turnypt.app.R.string.settings_biometric_summary
    }
}
