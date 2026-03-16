package com.skeddy.error

import android.content.Context
import androidx.annotation.StringRes
import com.skeddy.R

/**
 * Sealed class hierarchy representing all error types in Skeddy.
 *
 * Each error contains:
 * - code: unique error code for identification
 * - message: technical message for logging (English)
 * - userMessageResId: string resource ID for user-friendly UI message (localized)
 *
 * Use [getUserMessage] with a Context to resolve the localized user message.
 * Sealed class ensures exhaustive checking when handling errors via when().
 */
sealed class SkeddyError(
    val code: String,
    val message: String,
    @StringRes val userMessageResId: Int
) {
    /**
     * Resolves the localized user-facing message from string resources.
     */
    fun getUserMessage(context: Context): String = context.getString(userMessageResId)

    // ==================== Navigation Errors (E001-E003) ====================

    /**
     * Lyft Driver app not found or not installed.
     */
    data object LyftAppNotFound : SkeddyError(
        code = "E001",
        message = "Lyft Driver app not found or not installed",
        userMessageResId = R.string.error_message_lyft_not_found
    )

    /**
     * Menu button (hamburger) not found on screen.
     */
    data object MenuButtonNotFound : SkeddyError(
        code = "E002",
        message = "Menu button not found on screen",
        userMessageResId = R.string.error_message_menu_not_found
    )

    /**
     * Scheduled Rides menu item not found.
     */
    data object ScheduledRidesNotFound : SkeddyError(
        code = "E003",
        message = "Scheduled Rides menu item not found",
        userMessageResId = R.string.error_message_scheduled_not_found
    )

    // ==================== Parsing/Timeout Errors (E004) ====================

    /**
     * Timeout while parsing UI elements.
     */
    data object ParseTimeout : SkeddyError(
        code = "E004",
        message = "UI parsing timeout - screen did not load in time",
        userMessageResId = R.string.error_message_parse_timeout
    )

    // ==================== Database Errors (E005) ====================

    /**
     * Database operation failed.
     */
    data object DatabaseError : SkeddyError(
        code = "E005",
        message = "Database operation failed",
        userMessageResId = R.string.error_message_database
    )

    // ==================== Service Errors (E006) ====================

    /**
     * Monitoring service was killed by the system.
     */
    data object ServiceKilled : SkeddyError(
        code = "E006",
        message = "Monitoring service was killed by the system",
        userMessageResId = R.string.error_message_service_killed
    )

    // ==================== Accessibility Errors (E007-E008) ====================

    /**
     * Accessibility Service is not enabled.
     */
    data object AccessibilityNotEnabled : SkeddyError(
        code = "E007",
        message = "Accessibility Service is not enabled",
        userMessageResId = R.string.error_message_accessibility_not_enabled
    )

    /**
     * Failed to perform an accessibility action.
     */
    data object AccessibilityActionFailed : SkeddyError(
        code = "E008",
        message = "Failed to perform accessibility action",
        userMessageResId = R.string.error_message_accessibility_action_failed
    )

    // ==================== Screen State Errors (E009-E011, E016) ====================

    /**
     * Unknown or unexpected screen state.
     */
    data object UnknownScreen : SkeddyError(
        code = "E009",
        message = "Unknown or unexpected screen state",
        userMessageResId = R.string.error_message_unknown_screen
    )

    /**
     * Failed to navigate back to main screen.
     */
    data object NavigationFailed : SkeddyError(
        code = "E010",
        message = "Failed to navigate back to main screen",
        userMessageResId = R.string.error_message_navigation_failed
    )

    /**
     * System dialog is blocking UI operations.
     */
    data object SystemDialogBlocking : SkeddyError(
        code = "E011",
        message = "System dialog is blocking UI operations",
        userMessageResId = R.string.error_message_system_dialog
    )

    /**
     * Your rides tab not found on Scheduled rides screen.
     */
    data object YourRidesTabNotFound : SkeddyError(
        code = "E016",
        message = "Your rides tab not found on Scheduled rides screen",
        userMessageResId = R.string.error_message_your_rides_tab_not_found
    )

    // ==================== Server Communication Errors (E020-E025) ====================

    /**
     * Server unreachable — network error or timeout.
     */
    data object ServerUnreachable : SkeddyError(
        code = "E020",
        message = "Server unreachable - network error or timeout",
        userMessageResId = R.string.error_message_server_unreachable
    )

    /**
     * Server returned 401 Unauthorized or 403 Forbidden.
     */
    data object ServerUnauthorized : SkeddyError(
        code = "E021",
        message = "Server unauthorized - invalid or expired device token",
        userMessageResId = R.string.error_message_server_unauthorized
    )

    /**
     * Server returned 429 Rate Limited.
     *
     * @param retryAfterSeconds seconds to wait before retrying, from Retry-After header (null if absent)
     */
    data class ServerRateLimited(
        val retryAfterSeconds: Long? = null
    ) : SkeddyError(
        code = "E022",
        message = "Server rate limited${retryAfterSeconds?.let { " - retry after ${it}s" } ?: ""}",
        userMessageResId = R.string.error_message_server_rate_limited
    )

    /**
     * Server returned 422 Validation Error.
     */
    data object ServerValidationError : SkeddyError(
        code = "E023",
        message = "Server validation error - invalid request data",
        userMessageResId = R.string.error_message_server_validation
    )

    /**
     * Server returned 503 Service Unavailable.
     */
    data object ServerServiceUnavailable : SkeddyError(
        code = "E024",
        message = "Server service unavailable - temporary outage",
        userMessageResId = R.string.error_message_server_unavailable
    )

    /**
     * Server returned 5xx Internal Error.
     */
    data object ServerInternalError : SkeddyError(
        code = "E025",
        message = "Server internal error",
        userMessageResId = R.string.error_message_server_internal
    )

    // ==================== Login Errors (E026) ====================

    /**
     * Login failed — invalid email or password (API returns 401).
     */
    data object LoginInvalidCredentials : SkeddyError(
        code = "E026",
        message = "Invalid email or password",
        userMessageResId = R.string.login_error_invalid_credentials
    )

    // ==================== Dynamic Errors ====================

    /**
     * Database error with operation details.
     *
     * @param operation operation name (insert, update, delete, query)
     * @param details additional error details
     */
    data class DatabaseOperationError(
        val operation: String,
        val details: String
    ) : SkeddyError(
        code = "E005",
        message = "Database $operation failed: $details",
        userMessageResId = R.string.error_message_database
    )

    /**
     * Timeout with screen information.
     *
     * @param expectedScreen the screen that was expected
     * @param timeoutMs timeout duration in milliseconds
     */
    data class ScreenTimeout(
        val expectedScreen: String,
        val timeoutMs: Long
    ) : SkeddyError(
        code = "E004",
        message = "Timeout waiting for $expectedScreen after ${timeoutMs}ms",
        userMessageResId = R.string.error_message_parse_timeout
    )

    /**
     * Custom error for unexpected situations.
     *
     * @param customCode error code
     * @param customMessage technical message
     */
    data class Custom(
        val customCode: String,
        val customMessage: String
    ) : SkeddyError(
        code = customCode,
        message = customMessage,
        userMessageResId = R.string.error_message_unknown
    )

    companion object {
        /**
         * All static (object) error types for code uniqueness checks.
         * Uses lazy to avoid initialization order issues.
         */
        val allStaticErrors: List<SkeddyError> by lazy {
            listOf(
                LyftAppNotFound,
                MenuButtonNotFound,
                ScheduledRidesNotFound,
                ParseTimeout,
                DatabaseError,
                ServiceKilled,
                AccessibilityNotEnabled,
                AccessibilityActionFailed,
                UnknownScreen,
                NavigationFailed,
                SystemDialogBlocking,
                YourRidesTabNotFound,
                ServerUnreachable,
                ServerUnauthorized,
                ServerValidationError,
                ServerServiceUnavailable,
                ServerInternalError,
                LoginInvalidCredentials
            )
        }

        /**
         * Get error by code.
         * @param code error code
         * @return SkeddyError or null if not found
         */
        fun fromCode(code: String): SkeddyError? {
            return allStaticErrors.find { it.code == code }
        }
    }
}
