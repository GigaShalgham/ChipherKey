package com.turnypt.app.security

import androidx.biometric.BiometricManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v0.4.1: pins the honest biometric gate mapping. The mapping is symbolic
 * (it switches on the BiometricManager constants), so a library renumbering
 * cannot change its meaning — a raw-value pin was tried and dropped: CI
 * showed androidx.biometric 1.1.0 ships these in the 10+ range
 * (NONE_ENROLLED = 11), not the 1–4 the platform javadocs suggest.
 */
class BiometricDiagnosticsTest {

    @Test
    fun `every documented canAuthenticate result maps to its gate`() {
        assertEquals(BiometricGate.AVAILABLE, BiometricDiagnostics.gateFor(BiometricManager.BIOMETRIC_SUCCESS))
        assertEquals(BiometricGate.NONE_ENROLLED, BiometricDiagnostics.gateFor(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED))
        assertEquals(BiometricGate.NO_HARDWARE, BiometricDiagnostics.gateFor(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE))
        assertEquals(BiometricGate.HW_UNAVAILABLE, BiometricDiagnostics.gateFor(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE))
        assertEquals(
            BiometricGate.SECURITY_UPDATE,
            BiometricDiagnostics.gateFor(BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED),
        )
    }

    @Test
    fun `undocumented codes fall back to NOT_STRONG`() {
        assertEquals(BiometricGate.NOT_STRONG, BiometricDiagnostics.gateFor(-1))
        assertEquals(BiometricGate.NOT_STRONG, BiometricDiagnostics.gateFor(7))
        assertEquals(BiometricGate.NOT_STRONG, BiometricDiagnostics.gateFor(Int.MAX_VALUE))
    }
}
