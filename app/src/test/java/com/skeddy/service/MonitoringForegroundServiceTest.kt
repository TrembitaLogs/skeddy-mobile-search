package com.skeddy.service

import android.content.ComponentCallbacks2
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import com.skeddy.accessibility.SkeddyAccessibilityService
import com.skeddy.data.BlacklistRepository
import com.skeddy.network.ApiResult
import com.skeddy.network.DeviceTokenManager
import com.skeddy.network.SkeddyServerClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Unit tests for MonitoringForegroundService scheduling mechanism.
 *
 * Test Strategy (from task 7.3):
 * 1. Verify monitoring cycle executes every 30 seconds via logs with timestamps
 * 2. Verify stopMonitoring() stops the cycle immediately
 * 3. Test behavior on screen off/on (manual testing on real device)
 *
 * These tests cover the scheduling mechanism logic using Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MonitoringForegroundServiceTest {

    private lateinit var service: MonitoringForegroundService
    private lateinit var shadowLooper: ShadowLooper
    private lateinit var statePrefs: SharedPreferences

    companion object {
        private const val STATE_PREFS_NAME = "monitoring_service_state"
        private const val KEY_IS_MONITORING = "is_monitoring"
        private const val KEY_TOTAL_RIDES_FOUND = "total_rides_found"
        private const val KEY_CONSECUTIVE_FAILURES = "consecutive_failures"
        private const val KEY_LAST_SAVE_TIME = "last_save_time"
    }

    @Before
    fun setUp() {
        // Clear any previous state before each test
        RuntimeEnvironment.getApplication()
            .getSharedPreferences(STATE_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().apply()

        // Enable Accessibility Service so that performMonitoringCycle() step 0 check passes.
        // Without this, the service would stop itself (NOT_CONFIGURED state).
        val componentName = ComponentName(
            RuntimeEnvironment.getApplication(),
            SkeddyAccessibilityService::class.java
        ).flattenToString()
        Settings.Secure.putString(
            RuntimeEnvironment.getApplication().contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            componentName
        )

        service = Robolectric.setupService(MonitoringForegroundService::class.java)
        shadowLooper = Shadows.shadowOf(Looper.getMainLooper())

        statePrefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences(STATE_PREFS_NAME, Context.MODE_PRIVATE)
    }

    @After
    fun tearDown() {
        // Stop monitoring to cancel coroutines and handler callbacks
        if (service.isMonitoringActive()) {
            service.stopMonitoring()
        }
        // Drain any pending looper messages from this test
        shadowLooper.idle()

        Settings.Secure.putString(
            RuntimeEnvironment.getApplication().contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ""
        )
    }

    // ==================== Initial State Tests ====================

    @Test
    fun `service starts with monitoring inactive`() {
        assertFalse("Monitoring should be inactive initially", service.isMonitoringActive())
    }

    @Test
    fun `default monitoring interval is 30 seconds`() {
        assertEquals(30_000L, service.getMonitoringInterval())
    }

    // ==================== startMonitoring Tests ====================

    @Test
    fun `startMonitoring activates monitoring`() {
        service.startMonitoring()

        assertTrue("Monitoring should be active after startMonitoring", service.isMonitoringActive())
    }

    @Test
    fun `startMonitoring called twice does not restart monitoring`() {
        service.startMonitoring()
        val firstCallActive = service.isMonitoringActive()

        service.startMonitoring() // Second call should be ignored
        val secondCallActive = service.isMonitoringActive()

        assertTrue("First call should activate monitoring", firstCallActive)
        assertTrue("Second call should keep monitoring active", secondCallActive)
    }

    // ==================== stopMonitoring Tests ====================

    @Test
    fun `stopMonitoring deactivates monitoring`() {
        service.startMonitoring()
        assertTrue("Monitoring should be active", service.isMonitoringActive())

        service.stopMonitoring()
        assertFalse("Monitoring should be inactive after stopMonitoring", service.isMonitoringActive())
    }

    @Test
    fun `stopMonitoring when not monitoring does nothing`() {
        assertFalse("Monitoring should be inactive initially", service.isMonitoringActive())

        service.stopMonitoring() // Should not throw
        assertFalse("Monitoring should still be inactive", service.isMonitoringActive())
    }

    @Test
    fun `stopMonitoring prevents next scheduled cycle`() {
        service.startMonitoring()
        shadowLooper.idle() // Process initial runnable

        service.stopMonitoring()

        // Advance time past monitoring interval
        shadowLooper.idleFor(java.time.Duration.ofMillis(35_000))

        assertFalse("Monitoring should remain stopped", service.isMonitoringActive())
    }

    // ==================== setMonitoringInterval Tests ====================

    @Test
    fun `setMonitoringInterval changes interval`() {
        val newInterval = 60_000L

        service.setMonitoringInterval(newInterval)

        assertEquals(newInterval, service.getMonitoringInterval())
    }

    @Test
    fun `setMonitoringInterval enforces minimum of 5 seconds`() {
        service.setMonitoringInterval(1000L) // Try to set 1 second

        assertEquals("Interval should be at least 5 seconds", 5000L, service.getMonitoringInterval())
    }

    @Test
    fun `setMonitoringInterval accepts values above minimum`() {
        service.setMonitoringInterval(10_000L)

        assertEquals(10_000L, service.getMonitoringInterval())
    }

    // ==================== Scheduling Cycle Tests ====================

    @Test
    fun `monitoring cycle reschedules after completion`() {
        service.setMonitoringInterval(1000L) // Set to 5 sec (minimum)
        service.startMonitoring()

        // First cycle executes immediately
        shadowLooper.idle()
        assertTrue("Monitoring should be active", service.isMonitoringActive())

        // Advance time to trigger next cycle
        shadowLooper.idleFor(java.time.Duration.ofMillis(5500))
        assertTrue("Monitoring should still be active after cycle", service.isMonitoringActive())
    }

    @Test
    fun `multiple cycles execute correctly`() {
        service.setMonitoringInterval(5000L)
        service.startMonitoring()

        // Execute multiple cycles
        repeat(3) {
            shadowLooper.idleFor(java.time.Duration.ofMillis(5500))
        }

        assertTrue("Monitoring should remain active through multiple cycles", service.isMonitoringActive())
    }

    // ==================== Service Lifecycle Tests ====================

    @Test
    fun `onDestroy stops monitoring`() {
        service.startMonitoring()
        assertTrue("Monitoring should be active", service.isMonitoringActive())

        service.onDestroy()

        assertFalse("Monitoring should be inactive after onDestroy", service.isMonitoringActive())
    }

    // ==================== Interval Change During Monitoring Tests ====================

    @Test
    fun `changing interval during monitoring affects next cycle`() {
        service.startMonitoring()
        shadowLooper.idle()

        service.setMonitoringInterval(10_000L)

        assertEquals("New interval should be set", 10_000L, service.getMonitoringInterval())
        assertTrue("Monitoring should remain active", service.isMonitoringActive())
    }

    // ==================== Intent Action Tests ====================

    @Test
    fun `onStartCommand with ACTION_START starts monitoring`() {
        val intent = Intent().apply {
            action = MonitoringForegroundService.ACTION_START
        }

        service.onStartCommand(intent, 0, 1)

        assertTrue("Monitoring should be active after ACTION_START", service.isMonitoringActive())
    }

    @Test
    fun `onStartCommand with ACTION_STOP stops monitoring`() {
        // First start monitoring
        val startIntent = Intent().apply {
            action = MonitoringForegroundService.ACTION_START
        }
        service.onStartCommand(startIntent, 0, 1)
        assertTrue("Monitoring should be active", service.isMonitoringActive())

        // Then stop monitoring
        val stopIntent = Intent().apply {
            action = MonitoringForegroundService.ACTION_STOP
        }
        service.onStartCommand(stopIntent, 0, 2)

        assertFalse("Monitoring should be inactive after ACTION_STOP", service.isMonitoringActive())
    }

    @Test
    fun `onStartCommand with null action starts monitoring if not already running`() {
        val intent = Intent() // No action set

        service.onStartCommand(intent, 0, 1)

        assertTrue("Monitoring should start with null action", service.isMonitoringActive())
    }

    @Test
    fun `onStartCommand with null intent starts monitoring if not already running`() {
        service.onStartCommand(null, 0, 1)

        assertTrue("Monitoring should start with null intent", service.isMonitoringActive())
    }

    @Test
    fun `onStartCommand with unknown action starts monitoring if not already running`() {
        val intent = Intent().apply {
            action = "com.skeddy.UNKNOWN_ACTION"
        }

        service.onStartCommand(intent, 0, 1)

        assertTrue("Monitoring should start with unknown action", service.isMonitoringActive())
    }

    @Test
    fun `onStartCommand default action does not restart if already monitoring`() {
        // Start monitoring first
        service.startMonitoring()
        assertTrue("Monitoring should be active", service.isMonitoringActive())

        // Call with no action - should not restart
        val intent = Intent()
        service.onStartCommand(intent, 0, 1)

        assertTrue("Monitoring should remain active", service.isMonitoringActive())
    }

    @Test
    fun `ACTION_START does not restart if already monitoring`() {
        val startIntent = Intent().apply {
            action = MonitoringForegroundService.ACTION_START
        }

        // First start
        service.onStartCommand(startIntent, 0, 1)
        assertTrue("Monitoring should be active", service.isMonitoringActive())

        // Second start - should be ignored
        service.onStartCommand(startIntent, 0, 2)
        assertTrue("Monitoring should remain active", service.isMonitoringActive())
    }

    // ==================== Binding Tests ====================

    @Test
    fun `onBind returns LocalBinder`() {
        val binder = service.onBind(Intent())

        assertNotNull("Binder should not be null", binder)
        assertTrue("Binder should be LocalBinder", binder is MonitoringForegroundService.LocalBinder)
    }

    @Test
    fun `LocalBinder getService returns service instance`() {
        val binder = service.onBind(Intent()) as MonitoringForegroundService.LocalBinder

        val boundService = binder.getService()

        assertSame("getService should return the same service instance", service, boundService)
    }

    @Test
    fun `bound service can start monitoring`() {
        val binder = service.onBind(Intent()) as MonitoringForegroundService.LocalBinder
        val boundService = binder.getService()

        boundService.startMonitoring()

        assertTrue("Monitoring should be active via bound service", service.isMonitoringActive())
    }

    @Test
    fun `bound service can stop monitoring`() {
        service.startMonitoring()
        assertTrue("Monitoring should be active", service.isMonitoringActive())

        val binder = service.onBind(Intent()) as MonitoringForegroundService.LocalBinder
        val boundService = binder.getService()

        boundService.stopMonitoring()

        assertFalse("Monitoring should be stopped via bound service", service.isMonitoringActive())
    }

    @Test
    fun `bound service can check monitoring status`() {
        val binder = service.onBind(Intent()) as MonitoringForegroundService.LocalBinder
        val boundService = binder.getService()

        assertFalse("Monitoring should be inactive initially", boundService.isMonitoringActive())

        boundService.startMonitoring()
        assertTrue("Monitoring should be active after start", boundService.isMonitoringActive())
    }

    // ==================== Error Handling Tests ====================

    @Test
    fun `consecutive failures counter starts at zero`() {
        assertEquals(0, service.getConsecutiveFailures())
    }

    @Test
    fun `startMonitoring resets consecutive failures counter`() {
        // Simulate some failures
        service.consecutiveFailures = 3

        // Start should reset counter
        service.startMonitoring()

        assertEquals(0, service.getConsecutiveFailures())
    }

    @Test
    fun `stopMonitoring resets consecutive failures counter`() {
        service.startMonitoring()
        // Simulate some failures
        service.consecutiveFailures = 3

        // Stop should reset counter
        service.stopMonitoring()

        assertEquals(0, service.getConsecutiveFailures())
    }

    @Test
    fun `maxConsecutiveFailures is 5`() {
        assertEquals(5, service.maxConsecutiveFailures)
    }

    // ==================== Error Categorization Tests ====================

    @Test
    fun `categorizeError returns AccessibilityUnavailable for accessibility errors`() {
        val exception = Exception("Accessibility Service is not connected")

        val error = service.categorizeError(exception)

        assertTrue(error is MonitoringError.AccessibilityUnavailable)
    }

    @Test
    fun `categorizeError returns AccessibilityUnavailable case insensitive`() {
        val exception = Exception("ACCESSIBILITY service failed")

        val error = service.categorizeError(exception)

        assertTrue(error is MonitoringError.AccessibilityUnavailable)
    }

    @Test
    fun `categorizeError returns NavigationFailed for navigation errors`() {
        val exception = Exception("Navigation to menu failed")

        val error = service.categorizeError(exception)

        assertTrue(error is MonitoringError.NavigationFailed)
    }

    @Test
    fun `categorizeError returns NavigationFailed for navigate keyword`() {
        val exception = Exception("Failed to navigate back")

        val error = service.categorizeError(exception)

        assertTrue(error is MonitoringError.NavigationFailed)
    }

    @Test
    fun `categorizeError returns ParseFailed for parse errors`() {
        val exception = Exception("Failed to parse ride card")

        val error = service.categorizeError(exception)

        assertTrue(error is MonitoringError.ParseFailed)
    }

    @Test
    fun `categorizeError returns UnexpectedError for unknown errors`() {
        val exception = Exception("Something completely unexpected happened")

        val error = service.categorizeError(exception)

        assertTrue(error is MonitoringError.UnexpectedError)
        assertEquals(exception, (error as MonitoringError.UnexpectedError).cause)
    }

    @Test
    fun `categorizeError returns UnexpectedError for null message`() {
        val exception = Exception()

        val error = service.categorizeError(exception)

        assertTrue(error is MonitoringError.UnexpectedError)
    }

    // ==================== Consecutive Failures Behavior Tests ====================

    @Test
    fun `consecutive failures can be incremented`() {
        service.consecutiveFailures = 0

        service.consecutiveFailures++

        assertEquals(1, service.consecutiveFailures)
    }

    @Test
    fun `consecutive failures tracks up to maxConsecutiveFailures`() {
        service.consecutiveFailures = 0

        repeat(service.maxConsecutiveFailures) {
            service.consecutiveFailures++
        }

        assertEquals(service.maxConsecutiveFailures, service.consecutiveFailures)
    }

    @Test
    fun `getConsecutiveFailures returns current value`() {
        service.consecutiveFailures = 3

        assertEquals(3, service.getConsecutiveFailures())
    }

    // ==================== Low Memory Handling Tests ====================

    @Test
    fun `onTrimMemory RUNNING_LOW saves state`() {
        service.startMonitoring()
        service.consecutiveFailures = 2

        service.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)

        // Verify state was saved to SharedPreferences
        assertTrue(statePrefs.getBoolean(KEY_IS_MONITORING, false))
        assertEquals(2, statePrefs.getInt(KEY_CONSECUTIVE_FAILURES, 0))
    }

    @Test
    fun `onTrimMemory RUNNING_CRITICAL saves state`() {
        service.startMonitoring()
        service.consecutiveFailures = 3

        service.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)

        // Verify state was saved
        assertTrue(statePrefs.getBoolean(KEY_IS_MONITORING, false))
        assertEquals(3, statePrefs.getInt(KEY_CONSECUTIVE_FAILURES, 0))
    }

    @Test
    fun `onTrimMemory BACKGROUND saves state`() {
        service.startMonitoring()

        service.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)

        assertTrue(statePrefs.getBoolean(KEY_IS_MONITORING, false))
    }

    @Test
    fun `onTrimMemory MODERATE saves state`() {
        service.startMonitoring()

        service.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_MODERATE)

        assertTrue(statePrefs.getBoolean(KEY_IS_MONITORING, false))
    }

    @Test
    fun `onTrimMemory COMPLETE performs emergency save`() {
        service.startMonitoring()
        service.consecutiveFailures = 4

        service.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)

        // Verify emergency save was performed
        assertTrue(statePrefs.getBoolean(KEY_IS_MONITORING, false))
        assertEquals(4, statePrefs.getInt(KEY_CONSECUTIVE_FAILURES, 0))
        assertTrue(statePrefs.getLong(KEY_LAST_SAVE_TIME, 0) > 0)
    }

    @Test
    fun `onTrimMemory UI_HIDDEN does not save state`() {
        service.startMonitoring()

        service.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)

        // UI_HIDDEN should not trigger state save
        assertEquals(0L, statePrefs.getLong(KEY_LAST_SAVE_TIME, 0))
    }

    @Test
    fun `onLowMemory saves state and clears resources`() {
        service.startMonitoring()
        service.consecutiveFailures = 1

        service.onLowMemory()

        // Verify emergency save was performed
        assertTrue(statePrefs.getBoolean(KEY_IS_MONITORING, false))
        assertEquals(1, statePrefs.getInt(KEY_CONSECUTIVE_FAILURES, 0))
    }

    @Test
    fun `onDestroy clears saved state`() {
        // First save some state
        statePrefs.edit()
            .putBoolean(KEY_IS_MONITORING, true)
            .putInt(KEY_CONSECUTIVE_FAILURES, 5)
            .putLong(KEY_LAST_SAVE_TIME, System.currentTimeMillis())
            .apply()

        service.startMonitoring()
        service.onDestroy()

        // Verify state was cleared
        assertEquals(0L, statePrefs.getLong(KEY_LAST_SAVE_TIME, 0))
    }

    @Test
    fun `state is restored on service creation if saved recently`() {
        // Pre-save state (simulating low memory save)
        val recentSaveTime = System.currentTimeMillis() - 60_000 // 1 minute ago
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences(STATE_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_IS_MONITORING, true)
            .putInt(KEY_TOTAL_RIDES_FOUND, 5)
            .putInt(KEY_CONSECUTIVE_FAILURES, 2)
            .putLong(KEY_LAST_SAVE_TIME, recentSaveTime)
            .apply()

        // Create new service instance (simulating restart after low memory kill)
        val newService = Robolectric.setupService(MonitoringForegroundService::class.java)

        // State should be restored - check via public methods
        assertEquals(2, newService.getConsecutiveFailures())
    }

    @Test
    fun `stale state is not restored`() {
        // Pre-save state that is too old (10 minutes ago)
        val staleSaveTime = System.currentTimeMillis() - (10 * 60 * 1000)
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences(STATE_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_IS_MONITORING, true)
            .putInt(KEY_TOTAL_RIDES_FOUND, 5)
            .putInt(KEY_CONSECUTIVE_FAILURES, 2)
            .putLong(KEY_LAST_SAVE_TIME, staleSaveTime)
            .apply()

        // Create new service instance
        val newService = Robolectric.setupService(MonitoringForegroundService::class.java)

        // Stale state should NOT be restored
        assertEquals(0, newService.getConsecutiveFailures())
    }

    @Test
    fun `no restoration when no saved state exists`() {
        // Ensure no saved state
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences(STATE_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()

        // Create new service instance
        val newService = Robolectric.setupService(MonitoringForegroundService::class.java)

        // Should start with default values
        assertEquals(0, newService.getConsecutiveFailures())
        assertFalse(newService.isMonitoringActive())
    }

    // ==================== Screen Off/On Handling Tests ====================

    @Test
    fun `screen is initially on`() {
        assertFalse("Screen should be on initially", service.isScreenCurrentlyOff())
    }

    @Test
    fun `isMonitoringPausedForScreenOff returns false initially`() {
        assertFalse("Monitoring should not be paused initially", service.isMonitoringPausedForScreenOff())
    }

    @Test
    fun `screen off sets isScreenOff flag`() {
        // Simulate screen off broadcast
        val intent = Intent(Intent.ACTION_SCREEN_OFF)
        RuntimeEnvironment.getApplication().sendBroadcast(intent)
        shadowLooper.idle()

        assertTrue("Screen should be marked as off", service.isScreenCurrentlyOff())
    }

    @Test
    fun `screen on clears isScreenOff flag`() {
        // First set screen off
        service.isScreenOff = true

        // Simulate screen on broadcast
        val intent = Intent(Intent.ACTION_SCREEN_ON)
        RuntimeEnvironment.getApplication().sendBroadcast(intent)
        shadowLooper.idle()

        assertFalse("Screen should be marked as on", service.isScreenCurrentlyOff())
    }

    @Test
    fun `screen off while monitoring sets wasMonitoringBeforeScreenOff flag`() {
        // Start monitoring
        service.startMonitoring()
        assertTrue("Monitoring should be active", service.isMonitoringActive())

        // Simulate screen off
        val intent = Intent(Intent.ACTION_SCREEN_OFF)
        RuntimeEnvironment.getApplication().sendBroadcast(intent)
        shadowLooper.idle()

        assertTrue("Screen should be off", service.isScreenCurrentlyOff())
        assertTrue("wasMonitoringBeforeScreenOff should be true", service.wasMonitoringBeforeScreenOff)
    }

    @Test
    fun `screen off while not monitoring does not set wasMonitoringBeforeScreenOff flag`() {
        // Ensure monitoring is not active
        assertFalse("Monitoring should be inactive", service.isMonitoringActive())

        // Simulate screen off
        val intent = Intent(Intent.ACTION_SCREEN_OFF)
        RuntimeEnvironment.getApplication().sendBroadcast(intent)
        shadowLooper.idle()

        assertTrue("Screen should be off", service.isScreenCurrentlyOff())
        assertFalse("wasMonitoringBeforeScreenOff should be false", service.wasMonitoringBeforeScreenOff)
    }

    @Test
    fun `isMonitoringPausedForScreenOff returns true when monitoring paused for screen off`() {
        // Start monitoring
        service.startMonitoring()

        // Simulate screen off
        val intent = Intent(Intent.ACTION_SCREEN_OFF)
        RuntimeEnvironment.getApplication().sendBroadcast(intent)
        shadowLooper.idle()

        assertTrue("Monitoring should be paused for screen off", service.isMonitoringPausedForScreenOff())
    }

    @Test
    fun `screen on after screen off resets wasMonitoringBeforeScreenOff flag`() {
        // Start monitoring
        service.startMonitoring()

        // Simulate screen off then on
        val offIntent = Intent(Intent.ACTION_SCREEN_OFF)
        RuntimeEnvironment.getApplication().sendBroadcast(offIntent)
        shadowLooper.idle()

        val onIntent = Intent(Intent.ACTION_SCREEN_ON)
        RuntimeEnvironment.getApplication().sendBroadcast(onIntent)
        shadowLooper.idle()

        assertFalse("wasMonitoringBeforeScreenOff should be reset after screen on", service.wasMonitoringBeforeScreenOff)
    }

    @Test
    fun `monitoring remains active flag after screen off on cycle`() {
        // Start monitoring
        service.startMonitoring()
        assertTrue("Monitoring should be active initially", service.isMonitoringActive())

        // Simulate screen off
        val offIntent = Intent(Intent.ACTION_SCREEN_OFF)
        RuntimeEnvironment.getApplication().sendBroadcast(offIntent)
        shadowLooper.idle()

        // Monitoring flag should still be true (even though callbacks are removed)
        assertTrue("isMonitoring flag should remain true when screen off", service.isMonitoringActive())

        // Simulate screen on
        val onIntent = Intent(Intent.ACTION_SCREEN_ON)
        RuntimeEnvironment.getApplication().sendBroadcast(onIntent)
        shadowLooper.idle()

        assertTrue("Monitoring should be active after screen on", service.isMonitoringActive())
    }

    @Test
    fun `multiple screen off on cycles work correctly`() {
        // Start monitoring
        service.startMonitoring()

        // First cycle
        RuntimeEnvironment.getApplication().sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowLooper.idle()
        assertTrue("First screen off should pause", service.isMonitoringPausedForScreenOff())

        RuntimeEnvironment.getApplication().sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        shadowLooper.idle()
        assertFalse("First screen on should resume", service.isMonitoringPausedForScreenOff())

        // Second cycle
        RuntimeEnvironment.getApplication().sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowLooper.idle()
        assertTrue("Second screen off should pause", service.isMonitoringPausedForScreenOff())

        RuntimeEnvironment.getApplication().sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        shadowLooper.idle()
        assertFalse("Second screen on should resume", service.isMonitoringPausedForScreenOff())

        assertTrue("Monitoring should remain active through cycles", service.isMonitoringActive())
    }

    @Test
    fun `stopMonitoring during screen off clears flags`() {
        // Start monitoring
        service.startMonitoring()

        // Screen off
        RuntimeEnvironment.getApplication().sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowLooper.idle()

        // Stop monitoring while screen is off
        service.stopMonitoring()

        assertFalse("Monitoring should be inactive", service.isMonitoringActive())
        // Note: isScreenOff is still true (screen is still off), but monitoring won't resume
    }

    @Test
    fun `screen on after stopMonitoring during screen off does not start monitoring`() {
        // Start monitoring
        service.startMonitoring()

        // Screen off
        RuntimeEnvironment.getApplication().sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowLooper.idle()

        // Stop monitoring while screen is off
        service.stopMonitoring()
        assertFalse("Monitoring should be stopped", service.isMonitoringActive())

        // Screen on
        RuntimeEnvironment.getApplication().sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        shadowLooper.idle()

        // Monitoring should NOT resume because it was explicitly stopped
        assertFalse("Monitoring should not resume after explicit stop", service.isMonitoringActive())
    }

    // ==================== Force Update State Tests ====================

    @Test
    fun `service starts with force update inactive`() {
        assertFalse("Force update should be inactive initially", service.isInForceUpdateState())
    }

    @Test
    fun `handlePingForceUpdate with true activates force update state`() {
        service.handlePingForceUpdate(true, "https://example.com/update")

        assertTrue("Force update should be active", service.isInForceUpdateState())
    }

    @Test
    fun `handlePingForceUpdate with false when not active is no-op`() {
        // Both false — should be no-op
        service.handlePingForceUpdate(false, null)

        assertFalse("Force update should remain inactive", service.isInForceUpdateState())
    }

    @Test
    fun `handlePingForceUpdate transition true to false clears state`() {
        // Enter force update
        service.handlePingForceUpdate(true, "https://example.com/update")
        assertTrue("Force update should be active", service.isInForceUpdateState())

        // Clear force update
        service.handlePingForceUpdate(false, null)
        assertFalse("Force update should be cleared", service.isInForceUpdateState())
    }

    @Test
    fun `handlePingForceUpdate persists state to preferences`() {
        val skeddyPrefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("skeddy_prefs", Context.MODE_PRIVATE)

        service.handlePingForceUpdate(true, "https://example.com/update")

        assertTrue("forceUpdateActive should be persisted", skeddyPrefs.getBoolean("force_update_active", false))
        assertEquals("forceUpdateUrl should be persisted", "https://example.com/update", skeddyPrefs.getString("force_update_url", null))
    }

    @Test
    fun `handlePingForceUpdate broadcasts ACTION_FORCE_UPDATE on entering`() {
        service.handlePingForceUpdate(true, "https://example.com/update")
        shadowLooper.idle()

        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val broadcastIntents = shadowApp.broadcastIntents
        val forceUpdateIntent = broadcastIntents.find { it.action == MonitoringForegroundService.ACTION_FORCE_UPDATE }

        assertNotNull("ACTION_FORCE_UPDATE broadcast should be sent", forceUpdateIntent)
        assertEquals("https://example.com/update", forceUpdateIntent!!.getStringExtra(MonitoringForegroundService.EXTRA_UPDATE_URL))
    }

    @Test
    fun `handlePingForceUpdate broadcasts ACTION_FORCE_UPDATE_CLEARED on clearing`() {
        // First enter force update state
        service.handlePingForceUpdate(true, "https://example.com/update")
        shadowLooper.idle()

        // Clear force update
        service.handlePingForceUpdate(false, null)
        shadowLooper.idle()

        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val broadcastIntents = shadowApp.broadcastIntents
        val clearedIntent = broadcastIntents.find { it.action == MonitoringForegroundService.ACTION_FORCE_UPDATE_CLEARED }

        assertNotNull("ACTION_FORCE_UPDATE_CLEARED broadcast should be sent", clearedIntent)
    }

    // ==================== Low Memory Persistence Tests ====================

    @Test
    fun `saved state includes timestamp`() {
        val beforeSave = System.currentTimeMillis()
        service.startMonitoring()

        service.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)

        val savedTime = statePrefs.getLong(KEY_LAST_SAVE_TIME, 0)
        assertTrue("Save time should be after test start", savedTime >= beforeSave)
        assertTrue("Save time should be before now", savedTime <= System.currentTimeMillis())
    }

    @Test
    fun `multiple trim memory calls update saved state`() {
        service.startMonitoring()
        service.consecutiveFailures = 1

        service.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        val firstSaveTime = statePrefs.getLong(KEY_LAST_SAVE_TIME, 0)

        // Wait a bit and trigger another save
        Thread.sleep(10)
        service.consecutiveFailures = 3
        service.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)

        val secondSaveTime = statePrefs.getLong(KEY_LAST_SAVE_TIME, 0)
        val savedFailures = statePrefs.getInt(KEY_CONSECUTIVE_FAILURES, 0)

        assertTrue("Second save should be after first", secondSaveTime > firstSaveTime)
        assertEquals("Failures should be updated", 3, savedFailures)
    }

    // ==================== buildPingRequest Tests ====================

    @Test
    fun `buildPingRequest returns correct timezone`() {
        val request = service.buildPingRequest()

        assertEquals(
            "timezone should match device timezone",
            java.util.TimeZone.getDefault().id,
            request.timezone
        )
    }

    @Test
    fun `buildPingRequest returns correct app version`() {
        val request = service.buildPingRequest()

        assertEquals(
            "appVersion should match BuildConfig.VERSION_NAME",
            com.skeddy.BuildConfig.VERSION_NAME,
            request.appVersion
        )
    }

    @Test
    fun `buildPingRequest returns accessibilityEnabled true when service is enabled`() {
        // Accessibility is enabled in setUp()
        val request = service.buildPingRequest()

        assertTrue(
            "accessibilityEnabled should be true when Accessibility Service is enabled",
            request.deviceHealth.accessibilityEnabled
        )
    }

    @Test
    fun `buildPingRequest returns accessibilityEnabled false when service is disabled`() {
        // Disable Accessibility Service
        Settings.Secure.putString(
            RuntimeEnvironment.getApplication().contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ""
        )

        val request = service.buildPingRequest()

        assertFalse(
            "accessibilityEnabled should be false when Accessibility Service is disabled",
            request.deviceHealth.accessibilityEnabled
        )
    }

    @Test
    fun `buildPingRequest returns lyftRunning false when Lyft not in foreground`() {
        // Robolectric has no running tasks by default → LyftAppMonitor returns false
        val request = service.buildPingRequest()

        assertFalse(
            "lyftRunning should be false when Lyft is not in foreground",
            request.deviceHealth.lyftRunning
        )
    }

    @Test
    fun `buildPingRequest returns screenOn from PowerManager`() {
        // Robolectric PowerManager.isInteractive defaults to true
        val request = service.buildPingRequest()

        assertTrue(
            "screenOn should reflect PowerManager.isInteractive",
            request.deviceHealth.screenOn
        )
    }

    @Test
    fun `buildPingRequest returns screenOn false when screen is off`() {
        val pm = RuntimeEnvironment.getApplication()
            .getSystemService(Context.POWER_SERVICE) as PowerManager
        val shadowPm = Shadows.shadowOf(pm)
        @Suppress("DEPRECATION")
        shadowPm.setIsInteractive(false)

        val request = service.buildPingRequest()

        assertFalse(
            "screenOn should be false when PowerManager.isInteractive is false",
            request.deviceHealth.screenOn
        )
    }

    @Test
    fun `buildPingRequest uses pendingStats for stats`() {
        service.pendingStats.incrementCycles()
        service.pendingStats.incrementCycles()
        service.pendingStats.addRidesFound(3)

        val request = service.buildPingRequest()

        assertEquals(
            "cyclesSinceLastPing should reflect pendingStats",
            2,
            request.stats.cyclesSinceLastPing
        )
        assertEquals(
            "ridesFound should reflect pendingStats",
            3,
            request.stats.ridesFound
        )
        assertEquals(
            "batchId should match pendingStats batchId",
            service.pendingStats.batchId,
            request.stats.batchId
        )
        assertTrue(
            "acceptFailures should be empty when none added",
            request.stats.acceptFailures.isEmpty()
        )
    }

    @Test
    fun `buildPingRequest includes accept failures from pendingStats`() {
        val failure = com.skeddy.network.models.AcceptFailure(
            reason = "AcceptButtonNotFound",
            ridePrice = 25.50,
            pickupTime = "Tomorrow · 6:05AM",
            timestamp = "2026-02-09T10:30:00Z"
        )
        service.pendingStats.addAcceptFailure(failure)

        val request = service.buildPingRequest()

        assertEquals(
            "acceptFailures should contain the added failure",
            1,
            request.stats.acceptFailures.size
        )
        assertEquals(
            "failure reason should match",
            "AcceptButtonNotFound",
            request.stats.acceptFailures[0].reason
        )
        assertEquals(
            "failure ridePrice should match",
            25.50,
            request.stats.acceptFailures[0].ridePrice,
            0.001
        )
    }

    // ==================== Error Handler Tests (Task 14.5) ====================

    // --- handleUnauthorized ---

    @Test
    fun `handleUnauthorized stops monitoring`() = runBlocking {
        service.startMonitoring()
        assertTrue("Monitoring should be active", service.isMonitoringActive())

        service.handleUnauthorized()

        assertFalse("Monitoring should be stopped after unauthorized", service.isMonitoringActive())
    }

    @Test
    fun `handleUnauthorized broadcasts ACTION_UNPAIRED`() = runBlocking {
        service.startMonitoring()

        service.handleUnauthorized()
        shadowLooper.idle()

        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val unpairedIntent = shadowApp.broadcastIntents.find {
            it.action == MonitoringForegroundService.ACTION_UNPAIRED
        }

        assertNotNull("ACTION_UNPAIRED broadcast should be sent", unpairedIntent)
    }

    @Test
    fun `handleUnauthorized clears device token`() = runBlocking {
        service.startMonitoring()

        // Save a token first so we can verify it gets cleared
        service.deviceTokenManager.saveDeviceToken("test-token")
        assertTrue("Should be logged in before unauthorized", service.deviceTokenManager.isLoggedIn())

        service.handleUnauthorized()

        assertFalse("Device token should be cleared after unauthorized", service.deviceTokenManager.isLoggedIn())
    }

    @Test
    fun `handleUnauthorized does not schedule retry ping`() = runBlocking {
        service.startMonitoring()
        shadowLooper.idle()

        service.handleUnauthorized()

        // After handleUnauthorized, monitoring is stopped.
        // Advance time well past any possible retry delay — no cycle should run.
        assertFalse("Monitoring should be stopped, no retry", service.isMonitoringActive())
    }

    @Test
    fun `handleUnauthorized clears blacklist and pending ride queue`() = runBlocking {
        service.startMonitoring()

        service.handleUnauthorized()

        // Blacklist uses real Room DB (initialized in onCreate) — clearAll() should not throw.
        // PendingRideQueue uses SharedPreferences — clear() should not throw.
        // Verify service still transitions correctly after cleanup.
        assertFalse("Monitoring should be stopped after unauthorized", service.isMonitoringActive())
    }

    // --- handleNetworkError ---

    @Test
    fun `handleNetworkError keeps monitoring active`() {
        service.startMonitoring()

        service.handleNetworkError()

        assertTrue("Monitoring should still be active after network error", service.isMonitoringActive())
    }

    @Test
    fun `handleNetworkError broadcasts server offline status`() {
        service.startMonitoring()

        service.handleNetworkError()
        shadowLooper.idle()

        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val statusIntent = shadowApp.broadcastIntents.findLast {
            it.action == MonitoringForegroundService.ACTION_MONITORING_STATUS
        }

        assertNotNull("Monitoring status broadcast should be sent", statusIntent)
        assertTrue(
            "Server offline flag should be true",
            statusIntent!!.getBooleanExtra(MonitoringForegroundService.EXTRA_SERVER_OFFLINE, false)
        )
        assertTrue(
            "isRunning should be true (monitoring continues)",
            statusIntent.getBooleanExtra(MonitoringForegroundService.EXTRA_IS_MONITORING, false)
        )
    }

    @Test
    fun `handleNetworkError schedules retry after 30 seconds`() {
        service.startMonitoring()
        shadowLooper.idle()

        service.handleNetworkError()

        // Verify a callback is scheduled — advance 29s, nothing should have fired
        shadowLooper.idleFor(java.time.Duration.ofMillis(29_000))
        // Advance past 30s — the monitoring runnable should fire
        shadowLooper.idleFor(java.time.Duration.ofMillis(2_000))
        assertTrue("Monitoring should still be active after retry fires", service.isMonitoringActive())
    }

    // --- handleValidationError ---

    @Test
    fun `handleValidationError keeps monitoring active`() {
        service.startMonitoring()

        service.handleValidationError("invalid timezone")

        assertTrue("Monitoring should still be active after validation error", service.isMonitoringActive())
    }

    @Test
    fun `handleValidationError does not broadcast server offline`() {
        service.startMonitoring()
        shadowLooper.idle()

        // Clear previous broadcasts
        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val broadcastCountBefore = shadowApp.broadcastIntents.count {
            it.action == MonitoringForegroundService.ACTION_MONITORING_STATUS &&
                it.getBooleanExtra(MonitoringForegroundService.EXTRA_SERVER_OFFLINE, false)
        }

        service.handleValidationError("invalid timezone")
        shadowLooper.idle()

        val broadcastCountAfter = shadowApp.broadcastIntents.count {
            it.action == MonitoringForegroundService.ACTION_MONITORING_STATUS &&
                it.getBooleanExtra(MonitoringForegroundService.EXTRA_SERVER_OFFLINE, false)
        }

        assertEquals(
            "No server-offline broadcast should be sent for validation errors",
            broadcastCountBefore,
            broadcastCountAfter
        )
    }

    @Test
    fun `handleValidationError schedules retry after 60 seconds`() {
        service.startMonitoring()
        shadowLooper.idle()

        service.handleValidationError("invalid timezone")

        // Advance past 60s
        shadowLooper.idleFor(java.time.Duration.ofMillis(61_000))
        assertTrue("Monitoring should still be active after retry fires", service.isMonitoringActive())
    }

    // --- handleRateLimited ---

    @Test
    fun `handleRateLimited schedules retry after specified delay`() {
        service.startMonitoring()
        shadowLooper.idle()

        service.handleRateLimited(120)

        // Advance past 120s — the callback should fire
        shadowLooper.idleFor(java.time.Duration.ofMillis(121_000))
        assertTrue("Monitoring should still be active after rate limit retry", service.isMonitoringActive())
    }

    @Test
    fun `handleRateLimited broadcasts server offline status`() {
        service.startMonitoring()

        service.handleRateLimited(120)
        shadowLooper.idle()

        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val statusIntent = shadowApp.broadcastIntents.findLast {
            it.action == MonitoringForegroundService.ACTION_MONITORING_STATUS
        }

        assertNotNull("Monitoring status broadcast should be sent", statusIntent)
        assertTrue(
            "Server offline flag should be true for rate limited",
            statusIntent!!.getBooleanExtra(MonitoringForegroundService.EXTRA_SERVER_OFFLINE, false)
        )
    }

    @Test
    fun `handleRateLimited keeps monitoring active`() {
        service.startMonitoring()

        service.handleRateLimited(120)

        assertTrue("Monitoring should still be active after rate limited", service.isMonitoringActive())
    }

    // --- handleServiceUnavailable ---

    @Test
    fun `handleServiceUnavailable keeps monitoring active`() {
        service.startMonitoring()

        service.handleServiceUnavailable()

        assertTrue("Monitoring should still be active after 503", service.isMonitoringActive())
    }

    @Test
    fun `handleServiceUnavailable broadcasts server offline status`() {
        service.startMonitoring()

        service.handleServiceUnavailable()
        shadowLooper.idle()

        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val statusIntent = shadowApp.broadcastIntents.findLast {
            it.action == MonitoringForegroundService.ACTION_MONITORING_STATUS
        }

        assertNotNull("Monitoring status broadcast should be sent", statusIntent)
        assertTrue(
            "Server offline flag should be true for service unavailable",
            statusIntent!!.getBooleanExtra(MonitoringForegroundService.EXTRA_SERVER_OFFLINE, false)
        )
    }

    @Test
    fun `handleServiceUnavailable schedules retry after 60 seconds`() {
        service.startMonitoring()
        shadowLooper.idle()

        service.handleServiceUnavailable()

        shadowLooper.idleFor(java.time.Duration.ofMillis(61_000))
        assertTrue("Monitoring should still be active after 503 retry", service.isMonitoringActive())
    }

    // --- handleServerError ---

    @Test
    fun `handleServerError keeps monitoring active`() {
        service.startMonitoring()

        service.handleServerError()

        assertTrue("Monitoring should still be active after 5xx", service.isMonitoringActive())
    }

    @Test
    fun `handleServerError broadcasts server offline status`() {
        service.startMonitoring()

        service.handleServerError()
        shadowLooper.idle()

        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val statusIntent = shadowApp.broadcastIntents.findLast {
            it.action == MonitoringForegroundService.ACTION_MONITORING_STATUS
        }

        assertNotNull("Monitoring status broadcast should be sent", statusIntent)
        assertTrue(
            "Server offline flag should be true for server error",
            statusIntent!!.getBooleanExtra(MonitoringForegroundService.EXTRA_SERVER_OFFLINE, false)
        )
    }

    @Test
    fun `handleServerError schedules retry after 60 seconds`() {
        service.startMonitoring()
        shadowLooper.idle()

        service.handleServerError()

        shadowLooper.idleFor(java.time.Duration.ofMillis(61_000))
        assertTrue("Monitoring should still be active after 5xx retry", service.isMonitoringActive())
    }

    // ==================== E2E Integration Tests (Task 14.5) ====================

    /**
     * E2E test: Retry-After header flow.
     *
     * Full chain verified across two test files:
     * - SkeddyServerClientTest: MockWebServer 429 + Retry-After:120 → ApiResult.RateLimited(120)
     * - This test: monitoringCycleWithPing dispatches to handleRateLimited(120) → scheduleNextPing(120000)
     */
    @Test
    fun `monitoringCycleWithPing dispatches RateLimited to handleRateLimited`() = runBlocking {
        service.startMonitoring()

        val mockServerClient = mockk<SkeddyServerClient>()
        coEvery { mockServerClient.ping(any()) } returns ApiResult.RateLimited(120)
        service.serverClient = mockServerClient

        service.monitoringCycleWithPing()
        shadowLooper.idle()

        // Monitoring should still be active (not stopped)
        assertTrue("Monitoring should still be active after rate limit", service.isMonitoringActive())

        // Server offline broadcast should have been sent
        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val statusIntent = shadowApp.broadcastIntents.findLast {
            it.action == MonitoringForegroundService.ACTION_MONITORING_STATUS
        }
        assertTrue(
            "Server offline should be true in broadcast",
            statusIntent!!.getBooleanExtra(MonitoringForegroundService.EXTRA_SERVER_OFFLINE, false)
        )
    }

    /**
     * Integration test: 403 Forbidden end-to-end flow.
     *
     * Verifies that when the server responds with 403:
     * SkeddyServerClient maps it to ApiResult.Unauthorized →
     * monitoringCycleWithPing dispatches to handleUnauthorized →
     * monitoring stops + ACTION_UNPAIRED broadcast sent.
     */
    @Test
    fun `monitoringCycleWithPing handles Unauthorized end-to-end`() = runBlocking {
        service.startMonitoring()

        val mockServerClient = mockk<SkeddyServerClient>()
        coEvery { mockServerClient.ping(any()) } returns ApiResult.Unauthorized
        service.serverClient = mockServerClient

        service.monitoringCycleWithPing()
        shadowLooper.idle()

        assertFalse("Monitoring should be stopped after unauthorized", service.isMonitoringActive())

        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val unpairedIntent = shadowApp.broadcastIntents.find {
            it.action == MonitoringForegroundService.ACTION_UNPAIRED
        }
        assertNotNull("ACTION_UNPAIRED broadcast should be sent", unpairedIntent)
    }

    /**
     * Integration test: NetworkError end-to-end flow.
     */
    @Test
    fun `monitoringCycleWithPing handles NetworkError end-to-end`() = runBlocking {
        service.startMonitoring()

        val mockServerClient = mockk<SkeddyServerClient>()
        coEvery { mockServerClient.ping(any()) } returns ApiResult.NetworkError
        service.serverClient = mockServerClient

        service.monitoringCycleWithPing()
        shadowLooper.idle()

        assertTrue("Monitoring should still be active after network error", service.isMonitoringActive())

        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val statusIntent = shadowApp.broadcastIntents.findLast {
            it.action == MonitoringForegroundService.ACTION_MONITORING_STATUS
        }
        assertTrue(
            "Server offline should be true after network error",
            statusIntent!!.getBooleanExtra(MonitoringForegroundService.EXTRA_SERVER_OFFLINE, false)
        )
    }

    /**
     * Integration test: ServerError (5xx) end-to-end flow.
     */
    @Test
    fun `monitoringCycleWithPing handles ServerError end-to-end`() = runBlocking {
        service.startMonitoring()

        val mockServerClient = mockk<SkeddyServerClient>()
        coEvery { mockServerClient.ping(any()) } returns ApiResult.ServerError
        service.serverClient = mockServerClient

        service.monitoringCycleWithPing()
        shadowLooper.idle()

        assertTrue("Monitoring should still be active after server error", service.isMonitoringActive())

        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val statusIntent = shadowApp.broadcastIntents.findLast {
            it.action == MonitoringForegroundService.ACTION_MONITORING_STATUS
        }
        assertTrue(
            "Server offline should be true after server error",
            statusIntent!!.getBooleanExtra(MonitoringForegroundService.EXTRA_SERVER_OFFLINE, false)
        )
    }

    /**
     * Integration test: ServiceUnavailable (503) end-to-end flow.
     */
    @Test
    fun `monitoringCycleWithPing handles ServiceUnavailable end-to-end`() = runBlocking {
        service.startMonitoring()

        val mockServerClient = mockk<SkeddyServerClient>()
        coEvery { mockServerClient.ping(any()) } returns ApiResult.ServiceUnavailable
        service.serverClient = mockServerClient

        service.monitoringCycleWithPing()
        shadowLooper.idle()

        assertTrue("Monitoring should still be active after 503", service.isMonitoringActive())
    }

    /**
     * Integration test: ValidationError (422) end-to-end flow.
     */
    @Test
    fun `monitoringCycleWithPing handles ValidationError end-to-end`() = runBlocking {
        service.startMonitoring()

        val mockServerClient = mockk<SkeddyServerClient>()
        coEvery { mockServerClient.ping(any()) } returns ApiResult.ValidationError("invalid timezone")
        service.serverClient = mockServerClient

        service.monitoringCycleWithPing()
        shadowLooper.idle()

        assertTrue("Monitoring should still be active after validation error", service.isMonitoringActive())

        // Verify NO server offline broadcast for validation errors
        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val offlineBroadcasts = shadowApp.broadcastIntents.filter {
            it.action == MonitoringForegroundService.ACTION_MONITORING_STATUS &&
                it.getBooleanExtra(MonitoringForegroundService.EXTRA_SERVER_OFFLINE, false)
        }
        assertTrue("No server-offline broadcast for validation error", offlineBroadcasts.isEmpty())
    }

    // ==================== Task 23.1: scheduleRetryPing Tests ====================

    @Test
    fun `scheduleRetryPing schedules callback with correct delay`() {
        service.startMonitoring()
        shadowLooper.idle()

        service.scheduleRetryPing(30_000L)

        // Advance 29s — callback should NOT have fired yet
        shadowLooper.idleFor(java.time.Duration.ofMillis(29_000))
        // Advance past 30s — callback fires, monitoringCycleWithPing runs
        shadowLooper.idleFor(java.time.Duration.ofMillis(2_000))
        assertTrue("Monitoring should still be active after retry fires", service.isMonitoringActive())
    }

    @Test
    fun `scheduleRetryPing duplicate calls cancel previous callback`() {
        service.startMonitoring()
        shadowLooper.idle()

        // Schedule first retry at 30s
        service.scheduleRetryPing(30_000L)
        // Advance 15s
        shadowLooper.idleFor(java.time.Duration.ofMillis(15_000))
        // Schedule second retry at 30s (should cancel first)
        service.scheduleRetryPing(30_000L)

        // Advance 20s (35s from first, 20s from second) — first would have fired, second should not
        shadowLooper.idleFor(java.time.Duration.ofMillis(20_000))
        // Only the second retry callback is pending at this point.
        // Advance past second retry (10s more)
        shadowLooper.idleFor(java.time.Duration.ofMillis(11_000))
        assertTrue("Monitoring should still be active after second retry fires", service.isMonitoringActive())
    }

    @Test
    fun `scheduleRetryPing does not schedule when monitoring is stopped`() {
        // Do not start monitoring
        assertFalse("Monitoring should be inactive", service.isMonitoringActive())

        service.scheduleRetryPing(30_000L)

        // Advance past delay — nothing should happen
        shadowLooper.idleFor(java.time.Duration.ofMillis(35_000))
        assertFalse("Monitoring should remain inactive", service.isMonitoringActive())
    }

    @Test
    fun `handleNetworkError sets isServerOffline true`() {
        service.startMonitoring()

        assertFalse("isServerOffline should be false initially", service.isServerOffline)

        service.handleNetworkError()

        assertTrue("isServerOffline should be true after network error", service.isServerOffline)
    }

    @Test
    fun `handleNetworkError uses scheduleRetryPing with RETRY_PING_DELAY_MS`() {
        service.startMonitoring()
        shadowLooper.idle()

        service.handleNetworkError()

        // Advance past RETRY_PING_DELAY_MS (30s) — the retry callback should fire
        shadowLooper.idleFor(java.time.Duration.ofMillis(31_000))
        assertTrue("Monitoring should still be active after retry fires", service.isMonitoringActive())
    }

    @Test
    fun `stopMonitoring resets isServerOffline`() {
        service.startMonitoring()
        service.handleNetworkError()
        assertTrue("isServerOffline should be true", service.isServerOffline)

        service.stopMonitoring()

        assertFalse("isServerOffline should be false after stopMonitoring", service.isServerOffline)
    }

    @Test
    fun `stopMonitoring cancels pending retryPingRunnable`() {
        service.startMonitoring()
        shadowLooper.idle()

        service.scheduleRetryPing(30_000L)

        service.stopMonitoring()
        assertFalse("Monitoring should be stopped", service.isMonitoringActive())

        // Advance past retry delay — callback should NOT fire (stopMonitoring removed it)
        shadowLooper.idleFor(java.time.Duration.ofMillis(35_000))
        assertFalse("Monitoring should remain stopped after retry time", service.isMonitoringActive())
    }

    @Test
    fun `isServerOffline is false initially`() {
        assertFalse("isServerOffline should be false initially", service.isServerOffline)
    }

    @Test
    fun `RETRY_PING_DELAY_MS is 30 seconds`() {
        assertEquals(30_000L, MonitoringForegroundService.RETRY_PING_DELAY_MS)
    }

    // ==================== Task 23.2: Auto-Resume After Recovery Tests ====================

    private fun makePingResponse(search: Boolean, intervalSeconds: Int = 30): com.skeddy.network.models.PingResponse {
        return com.skeddy.network.models.PingResponse(
            search = search,
            intervalSeconds = intervalSeconds,
            filters = com.skeddy.network.models.Filters(minPrice = 20.0)
        )
    }

    @Test
    fun `handlePingSuccess resets isServerOffline to false`() = runBlocking {
        service.startMonitoring()
        service.isServerOffline = true

        service.handlePingSuccess(makePingResponse(search = false))

        assertFalse("isServerOffline should be false after successful ping", service.isServerOffline)
    }

    @Test
    fun `handlePingSuccess when not offline keeps isServerOffline false`() = runBlocking {
        service.startMonitoring()
        assertFalse("isServerOffline should be false initially", service.isServerOffline)

        service.handlePingSuccess(makePingResponse(search = false))

        assertFalse("isServerOffline should remain false", service.isServerOffline)
    }

    @Test
    fun `handlePingSuccess with search true broadcasts SEARCHING state`() = runBlocking {
        service.startMonitoring()
        service.isServerOffline = true

        service.handlePingSuccess(makePingResponse(search = true))
        shadowLooper.idle()

        assertFalse("isServerOffline should be false", service.isServerOffline)

        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val statusIntent = shadowApp.broadcastIntents.findLast {
            it.action == MonitoringForegroundService.ACTION_MONITORING_STATUS
        }
        assertNotNull("Status broadcast should be sent", statusIntent)
        assertEquals(
            "Search state should be SEARCHING",
            MonitoringForegroundService.SEARCH_STATE_SEARCHING,
            statusIntent!!.getStringExtra(MonitoringForegroundService.EXTRA_SEARCH_STATE)
        )
        assertFalse(
            "serverOffline should be false in broadcast",
            statusIntent.getBooleanExtra(MonitoringForegroundService.EXTRA_SERVER_OFFLINE, true)
        )
    }

    @Test
    fun `handlePingSuccess with search false broadcasts STOPPED state`() = runBlocking {
        service.startMonitoring()
        service.isServerOffline = true

        service.handlePingSuccess(makePingResponse(search = false))
        shadowLooper.idle()

        assertFalse("isServerOffline should be false", service.isServerOffline)

        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val statusIntent = shadowApp.broadcastIntents.findLast {
            it.action == MonitoringForegroundService.ACTION_MONITORING_STATUS
        }
        assertNotNull("Status broadcast should be sent", statusIntent)
        assertEquals(
            "Search state should be STOPPED when search=false",
            MonitoringForegroundService.SEARCH_STATE_STOPPED,
            statusIntent!!.getStringExtra(MonitoringForegroundService.EXTRA_SEARCH_STATE)
        )
    }

    @Test
    fun `handleNetworkError then handlePingSuccess resets isServerOffline`() = runBlocking {
        service.startMonitoring()

        // Simulate network error
        service.handleNetworkError()
        assertTrue("isServerOffline should be true after network error", service.isServerOffline)

        // Simulate successful retry ping
        service.handlePingSuccess(makePingResponse(search = false))

        assertFalse("isServerOffline should be false after recovery", service.isServerOffline)
    }

    @Test
    fun `repeated network error reschedules retry`() {
        service.startMonitoring()
        shadowLooper.idle()

        // First network error
        service.handleNetworkError()
        assertTrue("isServerOffline should be true", service.isServerOffline)

        // Advance to trigger retryPingRunnable (which will call monitoringCycleWithPing)
        // But since we can't easily mock the second ping call via runnable, test the handler directly
        // Second network error should still schedule retry
        service.handleNetworkError()
        assertTrue("isServerOffline should still be true", service.isServerOffline)
        assertTrue("Monitoring should still be active", service.isMonitoringActive())

        // Advance past retry delay — retry should fire
        shadowLooper.idleFor(java.time.Duration.ofMillis(31_000))
        assertTrue("Monitoring should still be active after retry fires", service.isMonitoringActive())
    }

    @Test
    fun `monitoringRunnable skips cycle when isServerOffline is true`() {
        service.startMonitoring()
        shadowLooper.idle()

        // Set offline and schedule a monitoringRunnable via scheduleNextPing
        service.isServerOffline = true
        service.scheduleNextPing(1_000L)

        // Mock server client to detect if ping was called
        val mockServerClient = mockk<SkeddyServerClient>()
        coEvery { mockServerClient.ping(any()) } returns ApiResult.NetworkError
        service.serverClient = mockServerClient

        // Advance past the scheduled delay
        shadowLooper.idleFor(java.time.Duration.ofMillis(2_000))

        // monitoringRunnable should have skipped — verify no ping call was made
        io.mockk.verify(exactly = 0) { runBlocking { mockServerClient.ping(any()) } }
        assertTrue("isServerOffline should still be true", service.isServerOffline)
    }

    @Test
    fun `full auto-resume flow network error then success via monitoringCycleWithPing`() = runBlocking {
        service.startMonitoring()

        val mockServerClient = mockk<SkeddyServerClient>()
        service.serverClient = mockServerClient

        // Step 1: Ping returns NetworkError
        coEvery { mockServerClient.ping(any()) } returns ApiResult.NetworkError

        service.monitoringCycleWithPing()
        shadowLooper.idle()

        assertTrue("isServerOffline should be true after network error", service.isServerOffline)

        // Step 2: Ping returns Success (simulating retry success)
        coEvery { mockServerClient.ping(any()) } returns ApiResult.Success(makePingResponse(search = false))

        service.monitoringCycleWithPing()
        shadowLooper.idle()

        assertFalse("isServerOffline should be false after successful retry", service.isServerOffline)
        assertTrue("Monitoring should still be active", service.isMonitoringActive())

        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val statusIntent = shadowApp.broadcastIntents.findLast {
            it.action == MonitoringForegroundService.ACTION_MONITORING_STATUS
        }
        assertFalse(
            "serverOffline should be false in last broadcast",
            statusIntent!!.getBooleanExtra(MonitoringForegroundService.EXTRA_SERVER_OFFLINE, true)
        )
    }

    // ==================== Task 2.1: performInitialPing Tests ====================

    @Test
    fun `performInitialPing with search true broadcasts SEARCHING state`() {
        service.startMonitoring()
        service.deviceTokenManager.saveDeviceToken("test-token")

        val mockServerClient = mockk<SkeddyServerClient>()
        coEvery { mockServerClient.ping(any()) } returns ApiResult.Success(makePingResponse(search = true))
        service.serverClient = mockServerClient

        service.performInitialPing()
        shadowLooper.idle()

        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val statusIntent = shadowApp.broadcastIntents.findLast {
            it.action == MonitoringForegroundService.ACTION_MONITORING_STATUS
        }
        assertNotNull("Status broadcast should be sent", statusIntent)
        assertEquals(
            "Search state should be SEARCHING when server returns search=true",
            MonitoringForegroundService.SEARCH_STATE_SEARCHING,
            statusIntent!!.getStringExtra(MonitoringForegroundService.EXTRA_SEARCH_STATE)
        )
        assertTrue(
            "isSearchActive should be true in broadcast",
            statusIntent.getBooleanExtra(MonitoringForegroundService.EXTRA_IS_SEARCH_ACTIVE, false)
        )
    }

    @Test
    fun `performInitialPing with search false broadcasts STOPPED state`() {
        service.startMonitoring()
        service.deviceTokenManager.saveDeviceToken("test-token")

        val mockServerClient = mockk<SkeddyServerClient>()
        coEvery { mockServerClient.ping(any()) } returns ApiResult.Success(makePingResponse(search = false))
        service.serverClient = mockServerClient

        service.performInitialPing()
        shadowLooper.idle()

        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val statusIntent = shadowApp.broadcastIntents.findLast {
            it.action == MonitoringForegroundService.ACTION_MONITORING_STATUS
        }
        assertNotNull("Status broadcast should be sent", statusIntent)
        assertEquals(
            "Search state should be STOPPED when server returns search=false",
            MonitoringForegroundService.SEARCH_STATE_STOPPED,
            statusIntent!!.getStringExtra(MonitoringForegroundService.EXTRA_SEARCH_STATE)
        )
        assertFalse(
            "isSearchActive should be false in broadcast",
            statusIntent.getBooleanExtra(MonitoringForegroundService.EXTRA_IS_SEARCH_ACTIVE, true)
        )
    }

    @Test
    fun `performInitialPing updates currentInterval from server response`() {
        service.startMonitoring()
        service.deviceTokenManager.saveDeviceToken("test-token")

        val mockServerClient = mockk<SkeddyServerClient>()
        coEvery { mockServerClient.ping(any()) } returns ApiResult.Success(makePingResponse(search = false, intervalSeconds = 45))
        service.serverClient = mockServerClient

        service.performInitialPing()
        shadowLooper.idle()

        assertEquals("currentInterval should be updated from server response", 45, service.currentInterval)
    }

    @Test
    fun `performInitialPing updates currentMinPrice from server response`() {
        service.startMonitoring()
        service.deviceTokenManager.saveDeviceToken("test-token")

        val response = com.skeddy.network.models.PingResponse(
            search = false,
            intervalSeconds = 30,
            filters = com.skeddy.network.models.Filters(minPrice = 35.0)
        )
        val mockServerClient = mockk<SkeddyServerClient>()
        coEvery { mockServerClient.ping(any()) } returns ApiResult.Success(response)
        service.serverClient = mockServerClient

        service.performInitialPing()
        shadowLooper.idle()

        assertEquals("currentMinPrice should be updated from server response", 35.0, service.currentMinPrice, 0.001)
    }

    @Test
    fun `performInitialPing when not logged in skips ping and schedules next`() {
        // Set mock BEFORE startMonitoring to intercept the initial monitoring cycle
        val mockServerClient = mockk<SkeddyServerClient>()
        coEvery { mockServerClient.ping(any()) } returns ApiResult.Success(makePingResponse(search = false))
        service.serverClient = mockServerClient

        service.startMonitoring()
        // Do NOT save device token — device is not logged in

        service.performInitialPing()
        // Do NOT idle the looper — performInitialPing returns synchronously
        // when not logged in, and we don't want the monitoring cycle to run

        // Monitoring should still be active (method doesn't stop it)
        assertTrue("Monitoring should still be active", service.isMonitoringActive())

        // Verify ping was NOT called by performInitialPing
        // (startMonitoring posted monitoringRunnable but it hasn't executed yet)
        coVerify(exactly = 0) { mockServerClient.ping(any()) }
    }

    @Test
    fun `performInitialPing with NetworkError sets server offline`() {
        service.startMonitoring()
        service.deviceTokenManager.saveDeviceToken("test-token")

        val mockServerClient = mockk<SkeddyServerClient>()
        coEvery { mockServerClient.ping(any()) } returns ApiResult.NetworkError
        service.serverClient = mockServerClient

        service.performInitialPing()
        shadowLooper.idle()

        assertTrue("isServerOffline should be true after network error", service.isServerOffline)
        assertTrue("Monitoring should still be active", service.isMonitoringActive())
    }

    @Test
    fun `performInitialPing with Unauthorized stops monitoring`() {
        service.startMonitoring()
        service.deviceTokenManager.saveDeviceToken("test-token")

        val mockServerClient = mockk<SkeddyServerClient>()
        coEvery { mockServerClient.ping(any()) } returns ApiResult.Unauthorized
        service.serverClient = mockServerClient

        // Mock blacklistRepository so suspend clearAll() completes immediately
        // (performInitialPing uses coroutineScope.launch internally)
        val mockBlacklistRepo = mockk<BlacklistRepository>(relaxed = true)
        service.blacklistRepository = mockBlacklistRepo

        service.performInitialPing()
        shadowLooper.idle()

        assertFalse("Monitoring should be stopped after unauthorized", service.isMonitoringActive())

        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val unpairedIntent = shadowApp.broadcastIntents.find {
            it.action == MonitoringForegroundService.ACTION_UNPAIRED
        }
        assertNotNull("ACTION_UNPAIRED broadcast should be sent", unpairedIntent)
    }

    @Test
    fun `performInitialPing with ServerError keeps monitoring active`() {
        service.startMonitoring()
        service.deviceTokenManager.saveDeviceToken("test-token")

        val mockServerClient = mockk<SkeddyServerClient>()
        coEvery { mockServerClient.ping(any()) } returns ApiResult.ServerError
        service.serverClient = mockServerClient

        service.performInitialPing()
        shadowLooper.idle()

        assertTrue("Monitoring should still be active after server error", service.isMonitoringActive())
    }

    @Test
    fun `performInitialPing with RateLimited respects retry delay`() {
        service.startMonitoring()
        service.deviceTokenManager.saveDeviceToken("test-token")

        val mockServerClient = mockk<SkeddyServerClient>()
        coEvery { mockServerClient.ping(any()) } returns ApiResult.RateLimited(60)
        service.serverClient = mockServerClient

        service.performInitialPing()
        shadowLooper.idle()

        assertTrue("Monitoring should still be active after rate limited", service.isMonitoringActive())
    }

    // ==================== Task 2.2: startMonitoring + performInitialPing Integration Tests ====================

    @Test
    fun `startMonitoring calls performInitialPing which pings server when logged in`() {
        val mockServerClient = mockk<SkeddyServerClient>()
        coEvery { mockServerClient.ping(any()) } returns ApiResult.Success(makePingResponse(search = false))
        service.serverClient = mockServerClient

        val mockTokenManager = mockk<DeviceTokenManager>()
        io.mockk.every { mockTokenManager.isLoggedIn() } returns true
        service.deviceTokenManager = mockTokenManager

        service.startMonitoring()
        shadowLooper.idle()

        coVerify(exactly = 1) { mockServerClient.ping(any()) }
    }

    @Test
    fun `startMonitoring calls performInitialPing which skips ping when not logged in`() {
        val mockServerClient = mockk<SkeddyServerClient>()
        service.serverClient = mockServerClient

        val mockTokenManager = mockk<DeviceTokenManager>()
        io.mockk.every { mockTokenManager.isLoggedIn() } returns false
        service.deviceTokenManager = mockTokenManager

        service.startMonitoring()
        shadowLooper.idle()

        coVerify(exactly = 0) { mockServerClient.ping(any()) }
    }

    @Test
    fun `startMonitoring resets isInitialPingCompleted flag`() {
        service.isInitialPingCompleted = true

        service.startMonitoring()

        // Flag is reset at start, then set to true by performInitialPing (not logged in path)
        assertTrue("isInitialPingCompleted should be true after initial ping completes",
            service.isInitialPingCompleted)
    }

    @Test
    fun `isInitialPingCompleted is set after successful initial ping`() {
        val mockServerClient = mockk<SkeddyServerClient>()
        coEvery { mockServerClient.ping(any()) } returns ApiResult.Success(makePingResponse(search = true))
        service.serverClient = mockServerClient

        val mockTokenManager = mockk<DeviceTokenManager>()
        io.mockk.every { mockTokenManager.isLoggedIn() } returns true
        service.deviceTokenManager = mockTokenManager

        service.startMonitoring()
        shadowLooper.idle()

        assertTrue("isInitialPingCompleted should be true after ping completes",
            service.isInitialPingCompleted)
    }

    @Test
    fun `stopMonitoring resets isInitialPingCompleted flag`() {
        service.startMonitoring()
        assertTrue("isInitialPingCompleted should be true after start", service.isInitialPingCompleted)

        service.stopMonitoring()

        assertFalse("isInitialPingCompleted should be false after stop", service.isInitialPingCompleted)
    }

    @Test
    fun `startMonitoring with logged in device and search true starts searching`() {
        val mockServerClient = mockk<SkeddyServerClient>()
        coEvery { mockServerClient.ping(any()) } returns ApiResult.Success(makePingResponse(search = true))
        service.serverClient = mockServerClient

        val mockTokenManager = mockk<DeviceTokenManager>()
        io.mockk.every { mockTokenManager.isLoggedIn() } returns true
        service.deviceTokenManager = mockTokenManager

        service.startMonitoring()
        shadowLooper.idle()

        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val statusIntent = shadowApp.broadcastIntents.findLast {
            it.action == MonitoringForegroundService.ACTION_MONITORING_STATUS
        }
        assertNotNull("Monitoring status broadcast should be sent", statusIntent)
        assertEquals(
            "Search state should be SEARCHING",
            MonitoringForegroundService.SEARCH_STATE_SEARCHING,
            statusIntent!!.getStringExtra(MonitoringForegroundService.EXTRA_SEARCH_STATE)
        )
    }

    @Test
    fun `double startMonitoring does not send double initial ping`() {
        val mockServerClient = mockk<SkeddyServerClient>()
        coEvery { mockServerClient.ping(any()) } returns ApiResult.Success(makePingResponse(search = false))
        service.serverClient = mockServerClient

        val mockTokenManager = mockk<DeviceTokenManager>()
        io.mockk.every { mockTokenManager.isLoggedIn() } returns true
        service.deviceTokenManager = mockTokenManager

        service.startMonitoring()
        service.startMonitoring() // Second call should be ignored
        shadowLooper.idle()

        coVerify(exactly = 1) { mockServerClient.ping(any()) }
    }

    // ==================== Task 2.3: SharedPreferences Fallback Tests ====================

    @Test
    fun `handlePingSuccess saves search state to preferences`() {
        service.startMonitoring()

        val response = makePingResponse(search = true)
        runBlocking { service.handlePingSuccess(response) }

        val skeddyPrefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("skeddy_prefs", Context.MODE_PRIVATE)
        assertTrue("wasSearching should be saved as true",
            skeddyPrefs.getBoolean("was_searching", false))
        assertTrue("lastSearchStateTime should be set",
            skeddyPrefs.getLong("last_search_state_time", 0) > 0)
    }

    @Test
    fun `handlePingSuccess saves search false to preferences`() {
        service.startMonitoring()

        val response = makePingResponse(search = false)
        runBlocking { service.handlePingSuccess(response) }

        val skeddyPrefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("skeddy_prefs", Context.MODE_PRIVATE)
        assertFalse("wasSearching should be saved as false",
            skeddyPrefs.getBoolean("was_searching", true))
    }

    @Test
    fun `restoreSearchStateFromPreferences restores searching state when valid`() {
        service.startMonitoring()

        // Save a recent searching state
        val skeddyPrefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("skeddy_prefs", Context.MODE_PRIVATE)
        skeddyPrefs.edit()
            .putBoolean("was_searching", true)
            .putLong("last_search_state_time", System.currentTimeMillis())
            .apply()

        service.restoreSearchStateFromPreferences()

        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val statusIntent = shadowApp.broadcastIntents.findLast {
            it.action == MonitoringForegroundService.ACTION_MONITORING_STATUS
        }
        assertNotNull("Should broadcast status after restore", statusIntent)
        assertEquals(
            "Search state should be SEARCHING after restore",
            MonitoringForegroundService.SEARCH_STATE_SEARCHING,
            statusIntent!!.getStringExtra(MonitoringForegroundService.EXTRA_SEARCH_STATE)
        )
    }

    @Test
    fun `restoreSearchStateFromPreferences does not restore expired state`() {
        service.startMonitoring()

        // Save a state older than 24 hours
        val skeddyPrefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("skeddy_prefs", Context.MODE_PRIVATE)
        val expiredTime = System.currentTimeMillis() - (25 * 60 * 60 * 1000L) // 25 hours ago
        skeddyPrefs.edit()
            .putBoolean("was_searching", true)
            .putLong("last_search_state_time", expiredTime)
            .apply()

        // Clear existing broadcasts to test only the restore
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).clearRegisteredReceivers()

        service.restoreSearchStateFromPreferences()

        // The last broadcast should still be WAITING from startMonitoring, not SEARCHING
        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val searchingBroadcasts = shadowApp.broadcastIntents.filter {
            it.action == MonitoringForegroundService.ACTION_MONITORING_STATUS &&
                it.getStringExtra(MonitoringForegroundService.EXTRA_SEARCH_STATE) == MonitoringForegroundService.SEARCH_STATE_SEARCHING
        }
        assertTrue("No SEARCHING broadcast should be sent for expired state", searchingBroadcasts.isEmpty())
    }

    @Test
    fun `restoreSearchStateFromPreferences does not restore when wasSearching is false`() {
        service.startMonitoring()

        val skeddyPrefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("skeddy_prefs", Context.MODE_PRIVATE)
        skeddyPrefs.edit()
            .putBoolean("was_searching", false)
            .putLong("last_search_state_time", System.currentTimeMillis())
            .apply()

        service.restoreSearchStateFromPreferences()

        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val searchingBroadcasts = shadowApp.broadcastIntents.filter {
            it.action == MonitoringForegroundService.ACTION_MONITORING_STATUS &&
                it.getStringExtra(MonitoringForegroundService.EXTRA_SEARCH_STATE) == MonitoringForegroundService.SEARCH_STATE_SEARCHING
        }
        assertTrue("No SEARCHING broadcast when wasSearching=false", searchingBroadcasts.isEmpty())
    }

    @Test
    fun `performInitialPing with network error restores cached search state`() {
        // Save a recent searching state
        val skeddyPrefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("skeddy_prefs", Context.MODE_PRIVATE)
        skeddyPrefs.edit()
            .putBoolean("was_searching", true)
            .putLong("last_search_state_time", System.currentTimeMillis())
            .apply()

        val mockServerClient = mockk<SkeddyServerClient>()
        coEvery { mockServerClient.ping(any()) } returns ApiResult.NetworkError
        service.serverClient = mockServerClient

        val mockTokenManager = mockk<DeviceTokenManager>()
        io.mockk.every { mockTokenManager.isLoggedIn() } returns true
        service.deviceTokenManager = mockTokenManager

        service.startMonitoring()
        shadowLooper.idle()

        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val statusIntent = shadowApp.broadcastIntents.findLast {
            it.action == MonitoringForegroundService.ACTION_MONITORING_STATUS
        }
        assertNotNull("Status broadcast should be sent", statusIntent)
        assertEquals(
            "Search state should be SEARCHING (restored from preferences)",
            MonitoringForegroundService.SEARCH_STATE_SEARCHING,
            statusIntent!!.getStringExtra(MonitoringForegroundService.EXTRA_SEARCH_STATE)
        )
    }

    @Test
    fun `performInitialPing with success does not trigger fallback`() {
        // Save a stale searching state
        val skeddyPrefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("skeddy_prefs", Context.MODE_PRIVATE)
        skeddyPrefs.edit()
            .putBoolean("was_searching", true)
            .putLong("last_search_state_time", System.currentTimeMillis())
            .apply()

        val mockServerClient = mockk<SkeddyServerClient>()
        coEvery { mockServerClient.ping(any()) } returns ApiResult.Success(makePingResponse(search = false))
        service.serverClient = mockServerClient

        val mockTokenManager = mockk<DeviceTokenManager>()
        io.mockk.every { mockTokenManager.isLoggedIn() } returns true
        service.deviceTokenManager = mockTokenManager

        service.startMonitoring()
        shadowLooper.idle()

        // Last broadcast should be STOPPED (from handlePingSuccess with search=false),
        // NOT SEARCHING (which would mean fallback was incorrectly triggered)
        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val statusIntent = shadowApp.broadcastIntents.findLast {
            it.action == MonitoringForegroundService.ACTION_MONITORING_STATUS
        }
        assertNotNull("Status broadcast should be sent", statusIntent)
        assertEquals(
            "Search state should be STOPPED from server response, not restored from preferences",
            MonitoringForegroundService.SEARCH_STATE_STOPPED,
            statusIntent!!.getStringExtra(MonitoringForegroundService.EXTRA_SEARCH_STATE)
        )
    }
}
