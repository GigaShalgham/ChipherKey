package com.turnypt.plugins.registry

import kotlinx.coroutines.test.runTest
import com.turnypt.plugins.api.PluginKeyPair
import com.turnypt.plugins.api.PluginSealedMessage
import com.turnypt.plugins.api.PublicKeyCipherPlugin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginRegistryTest {

    private class FakePlugin(override val id: String, override val displayName: String) : PublicKeyCipherPlugin {
        override val protocolVersion: Int = 1
        override suspend fun generateKeyPair(): PluginKeyPair =
            PluginKeyPair(ByteArray(32), ByteArray(32))
        override suspend fun encrypt(plaintext: ByteArray, recipientPublicKey: ByteArray) =
            PluginSealedMessage(1, plaintext)
        override suspend fun decrypt(sealed: PluginSealedMessage, privateKey: ByteArray): ByteArray = sealed.payload
    }

    @Test
    fun `empty registry reports mode B unavailable`() {
        val registry = PluginRegistry()
        assertTrue(registry.isEmpty)
        assertNull(registry.byId("anything"))
    }

    @Test
    fun `bundled plugins ship no public key implementation`() {
        // Honest-availability contract: v0.1 ships none.
        assertTrue(BundledPlugins.registry.isEmpty)
        assertTrue(!BundledPlugins.MODE_B_AVAILABLE)
    }

    @Test
    fun `register makes a plugin discoverable by id`() {
        val registry = PluginRegistry()
        val plugin = FakePlugin("test-protocol", "Test Protocol")
        registry.register(plugin)
        assertEquals(plugin, registry.byId("test-protocol"))
        assertEquals(listOf(plugin), registry.available())
    }

    @Test
    fun `available lists plugins sorted by display name`() {
        val registry = PluginRegistry()
        val beta = FakePlugin("b", "Zeta")
        val alpha = FakePlugin("a", "Alpha")
        registry.register(beta).register(alpha)
        assertEquals(listOf<FakePlugin>(alpha, beta), registry.available())
    }

    @Test
    fun `registering the same id replaces the plugin`() {
        val registry = PluginRegistry()
        val first = FakePlugin("x", "First")
        val second = FakePlugin("x", "Second")
        registry.register(first).register(second)
        assertEquals(second, registry.byId("x"))
        assertEquals(1, registry.available().size)
    }

    @Test
    fun `blank id is rejected`() {
        try {
            PluginRegistry().register(FakePlugin(" ", "Blank"))
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `fake plugin roundtrip through the contract`() = runTest {
        val plugin = FakePlugin("t", "T")
        val pair = plugin.generateKeyPair()
        val sealed = plugin.encrypt("hello".toByteArray(), pair.publicKey)
        assertEquals("hello", String(plugin.decrypt(sealed, pair.privateKey)))
    }
}
