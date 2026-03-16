package com.skeddy.service

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.skeddy.accessibility.SkeddyAccessibilityService
import com.skeddy.data.SkeddyPreferences
import com.skeddy.network.DeviceTokenManager
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Tests that ServiceRestartJobService checks AppState before restarting
 * MonitoringForegroundService.
 *
 * Test cases (from task 24.4 test strategy):
 * 1. FORCE_UPDATE -> verify startMonitoringService()
 * 2. NOT_CONFIGURED -> verify clearMonitoringFlag()
 * 3. NOT_LOGGED_IN -> verify clearMonitoringFlag() and service NOT started
 * 4. LOGGED_IN -> verify startMonitoringService()
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ServiceRestartJobServiceAppStateTest {

    private lateinit var context: Context
    private lateinit var deviceTokenManager: DeviceTokenManager
    private lateinit var preferences: SkeddyPreferences
    private lateinit var service: ServiceRestartJobService

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        deviceTokenManager = DeviceTokenManager(context)
        preferences = SkeddyPreferences(context)

        // Clear all state
        deviceTokenManager.clearDeviceToken()
        preferences.forceUpdateActive = false
        preferences.forceUpdateUrl = null
        clearAccessibility()
        ServiceRestartJobService.clearMonitoringFlag(context)

        service = Robolectric.setupService(ServiceRestartJobService::class.java)
    }

    @After
    fun tearDown() {
        deviceTokenManager.clearDeviceToken()
        preferences.forceUpdateActive = false
        preferences.forceUpdateUrl = null
        clearAccessibility()
        ServiceRestartJobService.clearMonitoringFlag(context)
    }

    // ==================== LOGGED_IN State ====================

    @Test
    fun `LOGGED_IN state restarts MonitoringForegroundService`() {
        setUpForRestart()
        deviceTokenManager.saveDeviceToken("test-token")
        enableAccessibility()

        val result = service.onStartJob(null)

        val startedService = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertNotNull("Service should be restarted in LOGGED_IN state", startedService)
        assertFalse("onStartJob should return false (job complete)", result)
    }

    // ==================== FORCE_UPDATE State ====================

    @Test
    fun `FORCE_UPDATE state restarts MonitoringForegroundService`() {
        setUpForRestart()
        deviceTokenManager.saveDeviceToken("test-token")
        enableAccessibility()
        preferences.forceUpdateActive = true
        preferences.forceUpdateUrl = "https://example.com/update"

        val result = service.onStartJob(null)

        val startedService = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertNotNull("Service should be restarted in FORCE_UPDATE state", startedService)
        assertFalse("onStartJob should return false (job complete)", result)
    }

    // ==================== NOT_LOGGED_IN State ====================

    @Test
    fun `NOT_LOGGED_IN state does not restart service`() {
        setUpForRestart()
        // No device token -> NOT_LOGGED_IN

        val result = service.onStartJob(null)

        val startedService = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertNull("Service should NOT be restarted in NOT_LOGGED_IN state", startedService)
        assertFalse("onStartJob should return false", result)
    }

    @Test
    fun `NOT_LOGGED_IN state clears monitoring flag`() {
        setUpForRestart()
        // No device token -> NOT_LOGGED_IN

        service.onStartJob(null)

        assertFalse(
            "Monitoring flag should be cleared for NOT_LOGGED_IN",
            ServiceRestartJobService.wasMonitoringActive(context)
        )
    }

    // ==================== NOT_CONFIGURED State ====================

    @Test
    fun `NOT_CONFIGURED state does not restart service`() {
        setUpForRestart()
        deviceTokenManager.saveDeviceToken("test-token")
        // Accessibility NOT enabled -> NOT_CONFIGURED

        val result = service.onStartJob(null)

        val startedService = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertNull("Service should NOT be restarted in NOT_CONFIGURED state", startedService)
        assertFalse("onStartJob should return false", result)
    }

    @Test
    fun `NOT_CONFIGURED state clears monitoring flag`() {
        setUpForRestart()
        deviceTokenManager.saveDeviceToken("test-token")
        // Accessibility NOT enabled -> NOT_CONFIGURED

        service.onStartJob(null)

        assertFalse(
            "Monitoring flag should be cleared for NOT_CONFIGURED",
            ServiceRestartJobService.wasMonitoringActive(context)
        )
    }

    // ==================== Helpers ====================

    /**
     * Sets up the preconditions: monitoring was active before kill.
     */
    private fun setUpForRestart() {
        ServiceRestartJobService.markMonitoringActive(context, true)
    }

    private fun enableAccessibility() {
        val componentName = ComponentName(
            context,
            SkeddyAccessibilityService::class.java
        ).flattenToString()

        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            componentName
        )
    }

    private fun clearAccessibility() {
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ""
        )
    }
}
