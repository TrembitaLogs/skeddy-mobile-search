package com.skeddy.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.skeddy.accessibility.SkeddyAccessibilityService
import com.skeddy.data.SkeddyPreferences
import com.skeddy.network.DeviceTokenManager
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Tests that BootCompletedReceiver checks AppState before starting
 * MonitoringForegroundService after boot.
 *
 * Test cases (from task 24.4 test strategy):
 * 1. PAIRED -> verify startMonitoringService()
 * 2. NOT_PAIRED -> verify service NOT started
 * 3. NOT_CONFIGURED -> verify service NOT started
 * 4. FORCE_UPDATE -> verify startMonitoringService()
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BootCompletedReceiverAppStateTest {

    private lateinit var context: Context
    private lateinit var deviceTokenManager: DeviceTokenManager
    private lateinit var preferences: SkeddyPreferences
    private lateinit var receiver: BootCompletedReceiver

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        deviceTokenManager = DeviceTokenManager(context)
        preferences = SkeddyPreferences(context)
        receiver = BootCompletedReceiver()

        // Clear all state
        deviceTokenManager.clearDeviceToken()
        preferences.forceUpdateActive = false
        preferences.forceUpdateUrl = null
        clearAccessibility()
        BootCompletedReceiver.setAutoStartEnabled(context, false)
        ServiceRestartJobService.clearMonitoringFlag(context)
    }

    @After
    fun tearDown() {
        deviceTokenManager.clearDeviceToken()
        preferences.forceUpdateActive = false
        preferences.forceUpdateUrl = null
        clearAccessibility()
        BootCompletedReceiver.setAutoStartEnabled(context, false)
        ServiceRestartJobService.clearMonitoringFlag(context)
    }

    // ==================== PAIRED State ====================

    @Test
    fun `PAIRED state starts MonitoringForegroundService`() {
        setUpForServiceStart()
        deviceTokenManager.saveDeviceToken("test-token")
        enableAccessibility()

        sendBootCompleted()

        val startedService = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertNotNull("Service should be started in PAIRED state", startedService)
    }

    // ==================== NOT_PAIRED State ====================

    @Test
    fun `NOT_PAIRED state does not start service`() {
        setUpForServiceStart()
        // No device token -> NOT_PAIRED

        sendBootCompleted()

        val startedService = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertNull("Service should NOT be started in NOT_PAIRED state", startedService)
    }

    // ==================== NOT_CONFIGURED State ====================

    @Test
    fun `NOT_CONFIGURED state does not start service`() {
        setUpForServiceStart()
        deviceTokenManager.saveDeviceToken("test-token")
        // Accessibility NOT enabled -> NOT_CONFIGURED

        sendBootCompleted()

        val startedService = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertNull("Service should NOT be started in NOT_CONFIGURED state", startedService)
    }

    // ==================== FORCE_UPDATE State ====================

    @Test
    fun `FORCE_UPDATE state starts MonitoringForegroundService`() {
        setUpForServiceStart()
        deviceTokenManager.saveDeviceToken("test-token")
        enableAccessibility()
        preferences.forceUpdateActive = true
        preferences.forceUpdateUrl = "https://example.com/update"

        sendBootCompleted()

        val startedService = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertNotNull("Service should be started in FORCE_UPDATE state", startedService)
    }

    // ==================== Guard Checks Still Work ====================

    @Test
    fun `does not start service when auto-start is disabled even if PAIRED`() {
        // auto-start disabled, but monitoring was active and device is paired
        BootCompletedReceiver.setAutoStartEnabled(context, false)
        ServiceRestartJobService.markMonitoringActive(context, true)
        deviceTokenManager.saveDeviceToken("test-token")
        enableAccessibility()

        sendBootCompleted()

        val startedService = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertNull("Service should NOT start when auto-start is disabled", startedService)
    }

    @Test
    fun `does not start service when monitoring was not active even if PAIRED`() {
        // auto-start enabled, but monitoring was NOT active
        BootCompletedReceiver.setAutoStartEnabled(context, true)
        ServiceRestartJobService.markMonitoringActive(context, false)
        deviceTokenManager.saveDeviceToken("test-token")
        enableAccessibility()

        sendBootCompleted()

        val startedService = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertNull("Service should NOT start when monitoring was not active", startedService)
    }

    // ==================== Helpers ====================

    /**
     * Sets up the preconditions that existed before the AppState check was added:
     * auto-start enabled + monitoring was active.
     */
    private fun setUpForServiceStart() {
        BootCompletedReceiver.setAutoStartEnabled(context, true)
        ServiceRestartJobService.markMonitoringActive(context, true)
    }

    private fun sendBootCompleted() {
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)
        receiver.onReceive(context, intent)
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
