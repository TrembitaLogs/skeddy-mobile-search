package com.skeddy.ui

/**
 * Represents the possible states of the application,
 * determining which screen to display.
 *
 * Priority order for state determination (first match wins):
 * 1. [NotLoggedIn] - no device token stored
 * 2. [NotConfigured] - logged in but Accessibility Service disabled
 * 3. [ForceUpdate] - server flagged a required app update
 * 4. [LoggedIn] - fully operational
 */
sealed class AppState {

    /** Device is not logged in - show LoginActivity. */
    data object NotLoggedIn : AppState()

    /** Logged in, but Accessibility Service is disabled - show SetupRequiredActivity. */
    data object NotConfigured : AppState()

    /** Server requires app update - show ForceUpdateActivity. */
    data class ForceUpdate(val updateUrl: String?) : AppState()

    /** Logged in and fully configured - show main screen. */
    data object LoggedIn : AppState()
}
