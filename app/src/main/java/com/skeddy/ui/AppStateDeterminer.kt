package com.skeddy.ui

import com.skeddy.data.SkeddyPreferences
import com.skeddy.network.DeviceTokenManager

/**
 * Determines the current application state based on login status,
 * accessibility service state, and server-driven force update flag.
 *
 * Priority order (first match wins):
 * 1. NOT_LOGGED_IN - no device token stored
 * 2. NOT_CONFIGURED - logged in but Accessibility Service disabled
 * 3. FORCE_UPDATE - server flagged a required update
 * 4. LOGGED_IN - fully operational
 */
class AppStateDeterminer(
    private val deviceTokenManager: DeviceTokenManager,
    private val preferences: SkeddyPreferences
) {

    /**
     * Determines the current [AppState].
     *
     * @param isAccessibilityEnabled whether the Accessibility Service is currently enabled.
     * @return the determined [AppState].
     */
    fun determine(isAccessibilityEnabled: Boolean): AppState {
        if (!deviceTokenManager.isLoggedIn()) {
            return AppState.NotLoggedIn
        }

        if (!isAccessibilityEnabled) {
            return AppState.NotConfigured
        }

        if (preferences.forceUpdateActive) {
            return AppState.ForceUpdate(preferences.forceUpdateUrl)
        }

        return AppState.LoggedIn
    }
}
