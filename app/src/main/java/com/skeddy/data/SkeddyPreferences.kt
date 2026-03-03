package com.skeddy.data

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate

/**
 * Manages local app state using SharedPreferences.
 *
 * Stores:
 * - Force update state (persisted for onResume checks)
 * - Last ping time and daily rides counter (for Main Screen UI)
 * - Cached server values (interval, min_price) for offline fallback
 *
 * Device token is managed by DeviceTokenManager (EncryptedSharedPreferences).
 * Ride filters and intervals come from the server via ping.
 *
 * @param context Application or Activity context
 */
class SkeddyPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    companion object {
        private const val PREFS_NAME = "skeddy_prefs"
        private const val KEY_FORCE_UPDATE_ACTIVE = "force_update_active"
        private const val KEY_FORCE_UPDATE_URL = "force_update_url"
        private const val KEY_LAST_PING_TIME = "last_ping_time"
        private const val KEY_RIDES_FOUND_TODAY = "rides_found_today"
        private const val KEY_RIDES_COUNTER_DATE = "rides_counter_date"
        private const val KEY_CACHED_INTERVAL = "cached_interval"
        private const val KEY_CACHED_MIN_PRICE = "cached_min_price"
        private const val KEY_WAS_SEARCHING = "was_searching"
        private const val KEY_LAST_SEARCH_STATE_TIME = "last_search_state_time"
        private const val KEY_SEARCH_FLOW_TYPE = "search_flow_type"

        const val DEFAULT_MIN_PRICE = 20.0
        const val DEFAULT_INTERVAL_SECONDS = 30
    }

    /**
     * Whether a force update is currently active (server-driven).
     * Persisted so that onResume() can check state even if broadcast was missed.
     * Default: false
     */
    var forceUpdateActive: Boolean
        get() = prefs.getBoolean(KEY_FORCE_UPDATE_ACTIVE, false)
        set(value) = prefs.edit().putBoolean(KEY_FORCE_UPDATE_ACTIVE, value).apply()

    /**
     * URL for the app update page (provided by server in ping response).
     * Null when no force update is active.
     */
    var forceUpdateUrl: String?
        get() = prefs.getString(KEY_FORCE_UPDATE_URL, null)
        set(value) {
            if (value != null) {
                prefs.edit().putString(KEY_FORCE_UPDATE_URL, value).apply()
            } else {
                prefs.edit().remove(KEY_FORCE_UPDATE_URL).apply()
            }
        }

    /**
     * Timestamp of the last successful ping (System.currentTimeMillis()).
     * Used for "Last ping time" display on Main Screen.
     * Default: 0 (no ping yet)
     */
    var lastPingTime: Long
        get() = prefs.getLong(KEY_LAST_PING_TIME, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_PING_TIME, value).apply()

    /**
     * Number of rides found today. Automatically resets when the date changes.
     * Used for "Rides found today" counter on Main Screen.
     * Default: 0
     */
    var ridesFoundToday: Int
        get() {
            resetDailyCounterIfNeeded()
            return prefs.getInt(KEY_RIDES_FOUND_TODAY, 0)
        }
        set(value) = prefs.edit().putInt(KEY_RIDES_FOUND_TODAY, value).apply()

    /**
     * Cached server-provided search interval in seconds.
     * Used as fallback when server is unreachable after service restart.
     * Default: [DEFAULT_INTERVAL_SECONDS]
     */
    var cachedIntervalSeconds: Int
        get() = prefs.getInt(KEY_CACHED_INTERVAL, DEFAULT_INTERVAL_SECONDS)
        set(value) = prefs.edit().putInt(KEY_CACHED_INTERVAL, value).apply()

    /**
     * Cached server-provided minimum price filter.
     * Used as fallback when server is unreachable after service restart.
     * Default: [DEFAULT_MIN_PRICE]
     */
    var cachedMinPrice: Double
        get() = Double.fromBits(prefs.getLong(KEY_CACHED_MIN_PRICE, DEFAULT_MIN_PRICE.toBits()))
        set(value) = prefs.edit().putLong(KEY_CACHED_MIN_PRICE, value.toBits()).apply()

    /**
     * Whether the server last told us to search (search=true in ping response).
     * Persisted as fallback for when initial ping fails on service restart (DEF-16).
     * Default: false
     */
    var wasSearching: Boolean
        get() = prefs.getBoolean(KEY_WAS_SEARCHING, false)
        set(value) = prefs.edit().putBoolean(KEY_WAS_SEARCHING, value).apply()

    /**
     * Timestamp when [wasSearching] was last saved (System.currentTimeMillis()).
     * Used to expire stale state — only restore if saved within last 24 hours.
     * Default: 0 (never saved)
     */
    var lastSearchStateTime: Long
        get() = prefs.getLong(KEY_LAST_SEARCH_STATE_TIME, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SEARCH_STATE_TIME, value).apply()

    /**
     * Saves the current search state with timestamp in a single transaction.
     *
     * @param isSearchActive Whether the server says search should be active
     */
    fun saveSearchState(isSearchActive: Boolean) {
        prefs.edit()
            .putBoolean(KEY_WAS_SEARCHING, isSearchActive)
            .putLong(KEY_LAST_SEARCH_STATE_TIME, System.currentTimeMillis())
            .apply()
    }

    /**
     * Increments the daily rides counter.
     * Resets the counter first if the date has changed since last increment.
     */
    fun incrementRidesFoundToday() {
        resetDailyCounterIfNeeded()
        val current = prefs.getInt(KEY_RIDES_FOUND_TODAY, 0)
        prefs.edit().putInt(KEY_RIDES_FOUND_TODAY, current + 1).apply()
    }

    /**
     * Resets the daily rides counter if the stored date differs from today.
     * Updates the stored date to today after reset.
     */
    internal fun resetDailyCounterIfNeeded() {
        val today = LocalDate.now().toString()
        val savedDate = prefs.getString(KEY_RIDES_COUNTER_DATE, null)
        if (savedDate != today) {
            prefs.edit()
                .putInt(KEY_RIDES_FOUND_TODAY, 0)
                .putString(KEY_RIDES_COUNTER_DATE, today)
                .apply()
        }
    }

    /**
     * The selected search flow strategy type.
     * Possible values: "CLASSIC" or "OPTIMIZED".
     * Can be overridden by the server via PingResponse.searchFlow.
     * Default: "CLASSIC" for backward compatibility.
     */
    var searchFlowType: String
        get() = prefs.getString(KEY_SEARCH_FLOW_TYPE, "OPTIMIZED") ?: "OPTIMIZED"
        set(value) = prefs.edit().putString(KEY_SEARCH_FLOW_TYPE, value).apply()

    /**
     * Updates both cached server values in a single transaction.
     *
     * @param intervalSeconds Search interval from server ping response
     * @param minPrice Minimum price filter from server ping response
     */
    fun updateCachedServerValues(intervalSeconds: Int, minPrice: Double) {
        prefs.edit()
            .putInt(KEY_CACHED_INTERVAL, intervalSeconds)
            .putLong(KEY_CACHED_MIN_PRICE, minPrice.toBits())
            .apply()
    }
}
