package com.skeddy.ui

import com.skeddy.data.SkeddyPreferences
import com.skeddy.network.DeviceTokenManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppStateDeterminerTest {

    private lateinit var deviceTokenManager: DeviceTokenManager
    private lateinit var preferences: SkeddyPreferences
    private lateinit var determiner: AppStateDeterminer

    @Before
    fun setUp() {
        deviceTokenManager = mockk()
        preferences = mockk()
        determiner = AppStateDeterminer(deviceTokenManager, preferences)
    }

    // ==================== NOT_PAIRED State ====================

    @Test
    fun `returns NotPaired when device is not paired`() {
        every { deviceTokenManager.isPaired() } returns false

        val result = determiner.determine(isAccessibilityEnabled = true)

        assertEquals(AppState.NotPaired, result)
    }

    @Test
    fun `returns NotPaired regardless of accessibility when not paired`() {
        every { deviceTokenManager.isPaired() } returns false

        val result = determiner.determine(isAccessibilityEnabled = false)

        assertEquals(AppState.NotPaired, result)
    }

    @Test
    fun `returns NotPaired regardless of force update when not paired`() {
        every { deviceTokenManager.isPaired() } returns false
        every { preferences.forceUpdateActive } returns true

        val result = determiner.determine(isAccessibilityEnabled = true)

        assertEquals(AppState.NotPaired, result)
    }

    // ==================== NOT_CONFIGURED State ====================

    @Test
    fun `returns NotConfigured when paired but accessibility disabled`() {
        every { deviceTokenManager.isPaired() } returns true

        val result = determiner.determine(isAccessibilityEnabled = false)

        assertEquals(AppState.NotConfigured, result)
    }

    @Test
    fun `returns NotConfigured over ForceUpdate when accessibility disabled`() {
        every { deviceTokenManager.isPaired() } returns true
        every { preferences.forceUpdateActive } returns true

        val result = determiner.determine(isAccessibilityEnabled = false)

        assertEquals(AppState.NotConfigured, result)
    }

    // ==================== FORCE_UPDATE State ====================

    @Test
    fun `returns ForceUpdate when paired, accessible, and force update active`() {
        every { deviceTokenManager.isPaired() } returns true
        every { preferences.forceUpdateActive } returns true
        every { preferences.forceUpdateUrl } returns "https://play.google.com/store/apps/details?id=com.skeddy"

        val result = determiner.determine(isAccessibilityEnabled = true)

        assertTrue(result is AppState.ForceUpdate)
        assertEquals(
            "https://play.google.com/store/apps/details?id=com.skeddy",
            (result as AppState.ForceUpdate).updateUrl
        )
    }

    @Test
    fun `returns ForceUpdate with null url when url not set`() {
        every { deviceTokenManager.isPaired() } returns true
        every { preferences.forceUpdateActive } returns true
        every { preferences.forceUpdateUrl } returns null

        val result = determiner.determine(isAccessibilityEnabled = true)

        assertTrue(result is AppState.ForceUpdate)
        assertEquals(null, (result as AppState.ForceUpdate).updateUrl)
    }

    // ==================== PAIRED State ====================

    @Test
    fun `returns Paired when device is paired, accessible, and no force update`() {
        every { deviceTokenManager.isPaired() } returns true
        every { preferences.forceUpdateActive } returns false

        val result = determiner.determine(isAccessibilityEnabled = true)

        assertEquals(AppState.Paired, result)
    }

    // ==================== Priority Order ====================

    @Test
    fun `NotPaired has highest priority over all other conditions`() {
        every { deviceTokenManager.isPaired() } returns false
        every { preferences.forceUpdateActive } returns true
        every { preferences.forceUpdateUrl } returns "https://example.com"

        val result = determiner.determine(isAccessibilityEnabled = false)

        assertEquals(AppState.NotPaired, result)
    }

    @Test
    fun `NotConfigured has higher priority than ForceUpdate`() {
        every { deviceTokenManager.isPaired() } returns true
        every { preferences.forceUpdateActive } returns true
        every { preferences.forceUpdateUrl } returns "https://example.com"

        val result = determiner.determine(isAccessibilityEnabled = false)

        assertEquals(AppState.NotConfigured, result)
    }

    @Test
    fun `ForceUpdate has higher priority than Paired`() {
        every { deviceTokenManager.isPaired() } returns true
        every { preferences.forceUpdateActive } returns true
        every { preferences.forceUpdateUrl } returns "https://example.com"

        val result = determiner.determine(isAccessibilityEnabled = true)

        assertTrue(result is AppState.ForceUpdate)
    }
}
