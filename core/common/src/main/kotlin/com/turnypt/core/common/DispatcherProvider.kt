package com.turnypt.core.common

import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/**
 * Central provider of coroutine dispatchers.
 *
 * Constructed and injected (rather than referencing [Dispatchers] directly
 * at call sites) so that unit tests can substitute deterministic
 * single-threaded dispatchers.
 */
class DispatcherProvider(
    val io: CoroutineContext = Dispatchers.IO,
    val default: CoroutineContext = Dispatchers.Default,
    val main: CoroutineContext = Dispatchers.Main,
)
