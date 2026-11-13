package com.turnypt.app.assistant

/**
 * Pure decision logic for the floating assistant's lifecycle (v0.3.0) —
 * extracted so it is unit-testable without a Service.
 */
object AssistantReadiness {

    /**
     * The assistant auto-starts only when the user enabled it AND the
     * overlay permission is already granted. Missing permission never
     * crashes the launch path: the Settings row explains how to grant it.
     */
    fun shouldAutoStart(enabled: Boolean, overlayGranted: Boolean): Boolean =
        enabled && overlayGranted

    /** What the Settings toggle wants the service to do. */
    sealed interface Action {
        data object StartService : Action
        data object StopService : Action
        data object None : Action
    }

    fun actionFor(enabled: Boolean, overlayGranted: Boolean): Action = when {
        enabled && overlayGranted -> Action.StartService
        !enabled -> Action.StopService
        else -> Action.None
    }
}
