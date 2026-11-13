package com.turnypt.keyboard.compose

import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * Owns the Jetpack lifecycles required to host Compose content inside an
 * [android.inputmethodservice.InputMethodService].
 *
 * An IME is not an Activity, so Compose must be given explicit
 * Lifecycle / ViewModelStore / SavedStateRegistry owners before a
 * [androidx.compose.ui.platform.ComposeView] can be attached. This is the
 * same fundamental approach used by production Compose-based keyboards
 * (e.g. FlorisBoard).
 *
 * Security note: the saved-state store is intentionally restored with an
 * empty bundle. Keyboard UI state is transient and must never be persisted
 * to disk (it may contain draft text or decrypted previews).
 */
internal class KeyboardLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val viewModelStoreImpl = ViewModelStore()

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val viewModelStore: ViewModelStore
        get() = viewModelStoreImpl

    /** Must be called before [androidx.lifecycle.Lifecycle.Event.ON_CREATE] is dispatched. */
    fun performRestore(savedState: Bundle?) {
        savedStateRegistryController.performRestore(savedState)
    }

    fun dispatchEvent(event: Lifecycle.Event) {
        lifecycleRegistry.handleLifecycleEvent(event)
    }

    fun clearViewModels() {
        viewModelStoreImpl.clear()
    }
}
