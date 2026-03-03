package com.skeddy.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SkeddyPreferencesTest {

    private lateinit var context: Context
    private lateinit var preferences: SkeddyPreferences

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        clearPreferences()
        preferences = SkeddyPreferences(context)
    }

    @After
    fun teardown() {
        clearPreferences()
    }

    private fun clearPreferences() {
        context.getSharedPreferences("skeddy_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun forceUpdateActive_defaultValue_isFalse() {
        assertFalse(preferences.forceUpdateActive)
    }

    @Test
    fun forceUpdateActive_setTrue_returnsTrue() {
        preferences.forceUpdateActive = true

        assertTrue(preferences.forceUpdateActive)
    }

    @Test
    fun forceUpdateUrl_defaultValue_isNull() {
        assertNull(preferences.forceUpdateUrl)
    }

    @Test
    fun forceUpdateUrl_setAndGet_returnsCorrectValue() {
        preferences.forceUpdateUrl = "https://example.com/update"

        assertEquals("https://example.com/update", preferences.forceUpdateUrl)
    }

    @Test
    fun forceUpdateUrl_setNull_clearsValue() {
        preferences.forceUpdateUrl = "https://example.com/update"
        preferences.forceUpdateUrl = null

        assertNull(preferences.forceUpdateUrl)
    }

    @Test
    fun forceUpdateActive_persistsAfterRecreation() {
        preferences.forceUpdateActive = true

        val newPreferences = SkeddyPreferences(context)

        assertTrue(newPreferences.forceUpdateActive)
    }

    @Test
    fun forceUpdateUrl_persistsAfterRecreation() {
        preferences.forceUpdateUrl = "https://example.com/update"

        val newPreferences = SkeddyPreferences(context)

        assertEquals("https://example.com/update", newPreferences.forceUpdateUrl)
    }

    @Test
    fun multiplePreferencesInstances_shareState() {
        val prefs1 = SkeddyPreferences(context)
        val prefs2 = SkeddyPreferences(context)

        prefs1.forceUpdateActive = true

        assertTrue(prefs2.forceUpdateActive)
    }
}
