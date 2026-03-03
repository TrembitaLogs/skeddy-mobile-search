package com.skeddy.data

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SkeddyPreferencesLocalStateTest {

    private lateinit var preferences: SkeddyPreferences
    private lateinit var rawPrefs: SharedPreferences

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        rawPrefs = app.getSharedPreferences("skeddy_prefs", Context.MODE_PRIVATE)
        rawPrefs.edit().clear().apply()
        preferences = SkeddyPreferences(app)
    }

    // ==================== lastPingTime ====================

    @Test
    fun `lastPingTime default is 0`() {
        assertEquals(0L, preferences.lastPingTime)
    }

    @Test
    fun `lastPingTime can be set and read`() {
        val timestamp = 1707500000000L
        preferences.lastPingTime = timestamp

        assertEquals(timestamp, preferences.lastPingTime)
    }

    @Test
    fun `lastPingTime persists across instances`() {
        val timestamp = 1707500000000L
        preferences.lastPingTime = timestamp

        val newPreferences = SkeddyPreferences(RuntimeEnvironment.getApplication())
        assertEquals(timestamp, newPreferences.lastPingTime)
    }

    // ==================== ridesFoundToday ====================

    @Test
    fun `ridesFoundToday default is 0`() {
        assertEquals(0, preferences.ridesFoundToday)
    }

    @Test
    fun `ridesFoundToday can be set and read`() {
        // Set today's date so the getter doesn't reset
        rawPrefs.edit().putString("rides_counter_date", LocalDate.now().toString()).apply()

        preferences.ridesFoundToday = 5

        assertEquals(5, preferences.ridesFoundToday)
    }

    @Test
    fun `ridesFoundToday resets when date changes`() {
        // Set counter with an old date
        rawPrefs.edit()
            .putInt("rides_found_today", 10)
            .putString("rides_counter_date", "2020-01-01")
            .apply()

        // Reading should trigger reset because date differs from today
        assertEquals(0, preferences.ridesFoundToday)
    }

    @Test
    fun `ridesFoundToday does not reset when date is today`() {
        rawPrefs.edit()
            .putInt("rides_found_today", 7)
            .putString("rides_counter_date", LocalDate.now().toString())
            .apply()

        assertEquals(7, preferences.ridesFoundToday)
    }

    // ==================== incrementRidesFoundToday ====================

    @Test
    fun `incrementRidesFoundToday increments counter`() {
        preferences.incrementRidesFoundToday()

        assertEquals(1, preferences.ridesFoundToday)
    }

    @Test
    fun `incrementRidesFoundToday increments multiple times`() {
        preferences.incrementRidesFoundToday()
        preferences.incrementRidesFoundToday()
        preferences.incrementRidesFoundToday()

        assertEquals(3, preferences.ridesFoundToday)
    }

    @Test
    fun `incrementRidesFoundToday resets counter when date changes then increments`() {
        // Set counter with an old date
        rawPrefs.edit()
            .putInt("rides_found_today", 15)
            .putString("rides_counter_date", "2020-01-01")
            .apply()

        preferences.incrementRidesFoundToday()

        // Should be 1 (reset to 0, then incremented)
        assertEquals(1, preferences.ridesFoundToday)
    }

    // ==================== resetDailyCounterIfNeeded ====================

    @Test
    fun `resetDailyCounterIfNeeded saves current date`() {
        preferences.resetDailyCounterIfNeeded()

        val savedDate = rawPrefs.getString("rides_counter_date", null)
        assertEquals(LocalDate.now().toString(), savedDate)
    }

    @Test
    fun `resetDailyCounterIfNeeded resets counter on old date`() {
        rawPrefs.edit()
            .putInt("rides_found_today", 42)
            .putString("rides_counter_date", "2020-06-15")
            .apply()

        preferences.resetDailyCounterIfNeeded()

        assertEquals(0, rawPrefs.getInt("rides_found_today", -1))
        assertEquals(LocalDate.now().toString(), rawPrefs.getString("rides_counter_date", null))
    }

    @Test
    fun `resetDailyCounterIfNeeded does not reset when date is today`() {
        rawPrefs.edit()
            .putInt("rides_found_today", 8)
            .putString("rides_counter_date", LocalDate.now().toString())
            .apply()

        preferences.resetDailyCounterIfNeeded()

        assertEquals(8, rawPrefs.getInt("rides_found_today", -1))
    }

    // ==================== cachedIntervalSeconds ====================

    @Test
    fun `cachedIntervalSeconds default is DEFAULT_INTERVAL_SECONDS`() {
        assertEquals(SkeddyPreferences.DEFAULT_INTERVAL_SECONDS, preferences.cachedIntervalSeconds)
    }

    @Test
    fun `cachedIntervalSeconds can be set and read`() {
        preferences.cachedIntervalSeconds = 45

        assertEquals(45, preferences.cachedIntervalSeconds)
    }

    // ==================== cachedMinPrice ====================

    @Test
    fun `cachedMinPrice default is DEFAULT_MIN_PRICE`() {
        assertEquals(SkeddyPreferences.DEFAULT_MIN_PRICE, preferences.cachedMinPrice, 0.0)
    }

    @Test
    fun `cachedMinPrice can be set and read`() {
        preferences.cachedMinPrice = 35.99

        assertEquals(35.99, preferences.cachedMinPrice, 0.0)
    }

    @Test
    fun `cachedMinPrice preserves exact double precision`() {
        val price = 25.123456789
        preferences.cachedMinPrice = price

        assertEquals(price, preferences.cachedMinPrice, 0.0)
    }

    // ==================== updateCachedServerValues ====================

    @Test
    fun `updateCachedServerValues saves both values`() {
        preferences.updateCachedServerValues(intervalSeconds = 60, minPrice = 30.0)

        assertEquals(60, preferences.cachedIntervalSeconds)
        assertEquals(30.0, preferences.cachedMinPrice, 0.0)
    }

    @Test
    fun `updateCachedServerValues overwrites previous values`() {
        preferences.updateCachedServerValues(intervalSeconds = 30, minPrice = 20.0)
        preferences.updateCachedServerValues(intervalSeconds = 90, minPrice = 50.5)

        assertEquals(90, preferences.cachedIntervalSeconds)
        assertEquals(50.5, preferences.cachedMinPrice, 0.0)
    }

    @Test
    fun `updateCachedServerValues persists across instances`() {
        preferences.updateCachedServerValues(intervalSeconds = 45, minPrice = 25.0)

        val newPreferences = SkeddyPreferences(RuntimeEnvironment.getApplication())
        assertEquals(45, newPreferences.cachedIntervalSeconds)
        assertEquals(25.0, newPreferences.cachedMinPrice, 0.0)
    }

    // ==================== DEFAULT constants ====================

    @Test
    fun `DEFAULT_MIN_PRICE is 20`() {
        assertEquals(20.0, SkeddyPreferences.DEFAULT_MIN_PRICE, 0.0)
    }

    @Test
    fun `DEFAULT_INTERVAL_SECONDS is 30`() {
        assertEquals(30, SkeddyPreferences.DEFAULT_INTERVAL_SECONDS)
    }
}
