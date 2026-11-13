package com.turnypt.keyboard.input

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM unit tests for the sensitive-field policy.
 *
 * Values are raw integers mirroring android.text.InputType combinations;
 * the production implementation inlines those constants at compile time.
 */
class SensitiveFieldPolicyTest {

    @Test
    fun `plain text field is not sensitive`() {
        // TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_NORMAL
        assertEquals(false, SensitiveFieldPolicy.isSensitive(0x00000001))
    }

    @Test
    fun `password variation is sensitive`() {
        // TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_PASSWORD
        assertEquals(true, SensitiveFieldPolicy.isSensitive(0x00000081))
    }

    @Test
    fun `visible password variation is sensitive`() {
        // TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        assertEquals(true, SensitiveFieldPolicy.isSensitive(0x00000091))
    }

    @Test
    fun `web password variation is sensitive`() {
        // TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_WEB_PASSWORD
        assertEquals(true, SensitiveFieldPolicy.isSensitive(0x000000e1))
    }

    @Test
    fun `number password variation is sensitive`() {
        // TYPE_CLASS_NUMBER | TYPE_NUMBER_VARIATION_PASSWORD
        assertEquals(true, SensitiveFieldPolicy.isSensitive(0x00000012))
    }

    @Test
    fun `plain number field is not sensitive`() {
        // TYPE_CLASS_NUMBER
        assertEquals(false, SensitiveFieldPolicy.isSensitive(0x00000002))
    }

    @Test
    fun `email and uri variations are not sensitive`() {
        // TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        assertEquals(false, SensitiveFieldPolicy.isSensitive(0x00000021))
        // TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_URI
        assertEquals(false, SensitiveFieldPolicy.isSensitive(0x00000011))
    }

    @Test
    fun `null editor info is not sensitive`() {
        assertEquals(false, SensitiveFieldPolicy.isSensitive(null as android.view.inputmethod.EditorInfo?))
    }
}
