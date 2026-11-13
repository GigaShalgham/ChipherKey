package com.turnypt.plugins.registry

import com.turnypt.plugins.api.PublicKeyCipherPlugin
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry of available public-key protocol plugins.
 *
 * Discovery is explicit (no ServiceLoader classpath scanning): the
 * application registers trusted plugins at startup. An empty registry means
 * mode B is unavailable — the UI must surface this honestly instead of
 * silently falling back to passphrase mode.
 */
class PluginRegistry {

    private val plugins = ConcurrentHashMap<String, PublicKeyCipherPlugin>()

    fun register(plugin: PublicKeyCipherPlugin): PluginRegistry {
        require(plugin.id.isNotBlank()) { "plugin id must not be blank" }
        plugins[plugin.id] = plugin
        return this
    }

    fun byId(id: String): PublicKeyCipherPlugin? = plugins[id]

    fun available(): List<PublicKeyCipherPlugin> = plugins.values.sortedBy { it.displayName }

    val isEmpty: Boolean
        get() = plugins.isEmpty()
}

/**
 * The plugin set bundled with this build.
 *
 * v0.1 ships NONE: the public-key mode is defined but not integrated
 * (docs/PLUGINS.md documents the evaluation). This object exists so the
 * wiring point is explicit and greppable.
 */
object BundledPlugins {

    val registry: PluginRegistry = PluginRegistry()

    /** Human-readable availability status for the UI. */
    const val MODE_B_AVAILABLE: Boolean = false
}
