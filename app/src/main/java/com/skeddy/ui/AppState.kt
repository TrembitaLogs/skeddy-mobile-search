package com.skeddy.ui

/**
 * Represents the possible states of the application,
 * determining which screen to display.
 *
 * Priority order for state determination (first match wins):
 * 1. [NotPaired] - no device token stored
 * 2. [NotConfigured] - paired but Accessibility Service disabled
 * 3. [ForceUpdate] - server flagged a required app update
 * 4. [Paired] - fully operational
 */
sealed class AppState {

    /** Device is not paired - show PairingActivity. */
    data object NotPaired : AppState()

    /** Paired, but Accessibility Service is disabled - show SetupRequiredActivity. */
    data object NotConfigured : AppState()

    /** Server requires app update - show ForceUpdateActivity. */
    data class ForceUpdate(val updateUrl: String?) : AppState()

    /** Paired and fully configured - show main screen. */
    data object Paired : AppState()
}
