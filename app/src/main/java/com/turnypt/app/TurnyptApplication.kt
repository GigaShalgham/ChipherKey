package com.turnypt.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.turnypt.core.sessions.KeySessionManager

/**
 * Turnypt application entry point.
 *
 * Hilt dependency graph root. The application deliberately holds no
 * network-facing components: Turnypt is local-first and every release
 * artifact operates without any backend.
 */
@HiltAndroidApp
class TurnyptApplication : Application() {

    @Inject lateinit var sessions: KeySessionManager

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Deep-scan finding 8: session expiry in KeySessionManager is lazy
        // (checked on query), so an expired-but-never-queried key could stay
        // in memory until process death. This low-frequency sweeper bounds
        // the residual window to roughly one interval; the per-contact
        // checks remain the authoritative gate for USE of a key.
        appScope.launch {
            while (isActive) {
                delay(SWEEP_INTERVAL_MILLIS)
                sessions.sweepExpired()
            }
        }
    }

    override fun onTerminate() {
        appScope.cancel()
        super.onTerminate()
    }

    private companion object {
        const val SWEEP_INTERVAL_MILLIS = 60_000L
    }
}
