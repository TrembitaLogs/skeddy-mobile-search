package com.skeddy.data

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SkeddyPreferencesForceUpdateTest {

    private lateinit var preferences: SkeddyPreferences

    @Before
    fun setUp() {
        // Clear preferences before each test
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("skeddy_prefs", Context.MODE_PRIVATE)
            .edit().clear().apply()

        preferences = SkeddyPreferences(RuntimeEnvironment.getApplication())
    }

    @Test
    fun `forceUpdateActive default is false`() {
        assertFalse(preferences.forceUpdateActive)
    }

    @Test
    fun `forceUpdateActive can be set to true`() {
        preferences.forceUpdateActive = true
        assertTrue(preferences.forceUpdateActive)
    }

    @Test
    fun `forceUpdateUrl default is null`() {
        assertNull(preferences.forceUpdateUrl)
    }

    @Test
    fun `forceUpdateUrl can be set and read`() {
        val url = "https://play.google.com/store/apps/details?id=com.skeddy"
        preferences.forceUpdateUrl = url
        assertEquals(url, preferences.forceUpdateUrl)
    }

    @Test
    fun `forceUpdateUrl can be set to null`() {
        preferences.forceUpdateUrl = "https://example.com"
        assertEquals("https://example.com", preferences.forceUpdateUrl)

        preferences.forceUpdateUrl = null
        assertNull(preferences.forceUpdateUrl)
    }

    @Test
    fun `forceUpdateActive persists across instances`() {
        preferences.forceUpdateActive = true

        // Create new instance with same context
        val newPreferences = SkeddyPreferences(RuntimeEnvironment.getApplication())
        assertTrue(newPreferences.forceUpdateActive)
    }
}
