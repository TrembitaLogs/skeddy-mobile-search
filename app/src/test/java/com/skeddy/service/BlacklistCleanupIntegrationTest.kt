package com.skeddy.service

import android.content.ComponentName
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.skeddy.accessibility.SkeddyAccessibilityService
import com.skeddy.data.BlacklistRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
 * Tests for blacklist cleanup integration in MonitoringForegroundService.
 *
 * Test Strategy (from task 8.2):
 * 1. Verify cleanup is called on startMonitoring()
 * 2. Verify cleanup repeats every hour via Handler.postDelayed()
 * 3. Verify cleanup stops on stopMonitoring()
 * 4. Verify error handling — exception does not stop next cleanup cycles
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BlacklistCleanupIntegrationTest {

    private lateinit var service: MonitoringForegroundService
    private lateinit var shadowLooper: ShadowLooper
    private lateinit var mockBlacklistRepository: BlacklistRepository

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0

        // Enable Accessibility Service so that performMonitoringCycle() step 0 check passes.
        val componentName = ComponentName(
            RuntimeEnvironment.getApplication(),
            SkeddyAccessibilityService::class.java
        ).flattenToString()
        Settings.Secure.putString(
            RuntimeEnvironment.getApplication().contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            componentName
        )

        // Robolectric creates the service and calls onCreate() which initializes
        // the real BlacklistRepository. We replace it with a mock afterwards.
        service = Robolectric.setupService(MonitoringForegroundService::class.java)
        shadowLooper = Shadows.shadowOf(Looper.getMainLooper())

        mockBlacklistRepository = mockk(relaxed = true)
        coEvery { mockBlacklistRepository.cleanupExpiredRides() } returns 0
        service.blacklistRepository = mockBlacklistRepository
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
        Settings.Secure.putString(
            RuntimeEnvironment.getApplication().contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ""
        )
    }

    // ==================== 1. Cleanup called on startMonitoring ====================

    @Test
    fun `cleanup is called when monitoring starts`() {
        service.startMonitoring()
        shadowLooper.idle()

        coVerify(exactly = 1) { mockBlacklistRepository.cleanupExpiredRides() }
    }

    @Test
    fun `cleanup is not called before startMonitoring`() {
        // Just idle without starting — no cleanup should happen
        shadowLooper.idle()

        coVerify(exactly = 0) { mockBlacklistRepository.cleanupExpiredRides() }
    }

    // ==================== 2. Cleanup repeats every hour ====================

    @Test
    fun `cleanup repeats after one hour`() {
        service.startMonitoring()
        shadowLooper.idle() // Initial cleanup

        coVerify(exactly = 1) { mockBlacklistRepository.cleanupExpiredRides() }

        // Advance time by 1 hour
        shadowLooper.idleFor(java.time.Duration.ofMillis(
            MonitoringForegroundService.CLEANUP_INTERVAL_MS
        ))

        coVerify(exactly = 2) { mockBlacklistRepository.cleanupExpiredRides() }
    }

    @Test
    fun `cleanup runs multiple times over several hours`() {
        service.startMonitoring()
        shadowLooper.idle() // Initial cleanup

        // Advance 3 hours
        repeat(3) {
            shadowLooper.idleFor(java.time.Duration.ofMillis(
                MonitoringForegroundService.CLEANUP_INTERVAL_MS
            ))
        }

        // 1 initial + 3 hourly = 4 total
        coVerify(exactly = 4) { mockBlacklistRepository.cleanupExpiredRides() }
    }

    @Test
    fun `cleanup does not run before interval elapses`() {
        service.startMonitoring()
        shadowLooper.idle() // Initial cleanup

        // Advance 30 minutes (half the interval)
        shadowLooper.idleFor(java.time.Duration.ofMillis(
            MonitoringForegroundService.CLEANUP_INTERVAL_MS / 2
        ))

        // Should still be just the initial call
        coVerify(exactly = 1) { mockBlacklistRepository.cleanupExpiredRides() }
    }

    // ==================== 3. Cleanup stops on stopMonitoring ====================

    @Test
    fun `cleanup stops when monitoring stops`() {
        service.startMonitoring()
        shadowLooper.idle() // Initial cleanup

        service.stopMonitoring()

        // Advance time well past the cleanup interval
        shadowLooper.idleFor(java.time.Duration.ofMillis(
            MonitoringForegroundService.CLEANUP_INTERVAL_MS * 2
        ))

        // Should only have the initial call, no more after stop
        coVerify(exactly = 1) { mockBlacklistRepository.cleanupExpiredRides() }
    }

    @Test
    fun `cleanup resumes when monitoring restarts after stop`() {
        // First session
        service.startMonitoring()
        shadowLooper.idle()
        service.stopMonitoring()

        coVerify(exactly = 1) { mockBlacklistRepository.cleanupExpiredRides() }

        // Second session
        service.startMonitoring()
        shadowLooper.idle()

        coVerify(exactly = 2) { mockBlacklistRepository.cleanupExpiredRides() }
    }

    // ==================== 4. Error handling ====================

    @Test
    fun `exception in cleanup does not stop next cleanup cycle`() {
        // First call throws, subsequent calls succeed
        coEvery { mockBlacklistRepository.cleanupExpiredRides() } throws
            RuntimeException("DB error") andThen 0

        service.startMonitoring()
        shadowLooper.idle() // Initial cleanup — throws

        // Service should still be monitoring
        assertTrue("Monitoring should remain active after cleanup error",
            service.isMonitoringActive())

        // Advance to next cleanup cycle
        shadowLooper.idleFor(java.time.Duration.ofMillis(
            MonitoringForegroundService.CLEANUP_INTERVAL_MS
        ))

        // Second call should have been made despite first failure
        coVerify(exactly = 2) { mockBlacklistRepository.cleanupExpiredRides() }
    }

    @Test
    fun `repeated exceptions do not stop cleanup schedule`() {
        coEvery { mockBlacklistRepository.cleanupExpiredRides() } throws
            RuntimeException("DB error")

        service.startMonitoring()
        shadowLooper.idle()

        // Advance through 2 more cycles
        repeat(2) {
            shadowLooper.idleFor(java.time.Duration.ofMillis(
                MonitoringForegroundService.CLEANUP_INTERVAL_MS
            ))
        }

        // All 3 calls attempted despite all failing
        coVerify(exactly = 3) { mockBlacklistRepository.cleanupExpiredRides() }
        assertTrue("Monitoring should remain active", service.isMonitoringActive())
    }

    // ==================== Edge cases ====================

    @Test
    fun `cleanup interval constant is one hour`() {
        val oneHourMs = 60L * 60 * 1000
        assertTrue("CLEANUP_INTERVAL_MS should be 1 hour",
            MonitoringForegroundService.CLEANUP_INTERVAL_MS == oneHourMs)
    }

    @Test
    fun `onDestroy stops cleanup`() {
        service.startMonitoring()
        shadowLooper.idle()

        service.onDestroy()

        assertFalse("Monitoring should be inactive after onDestroy",
            service.isMonitoringActive())

        // Advance past interval — no more cleanup
        shadowLooper.idleFor(java.time.Duration.ofMillis(
            MonitoringForegroundService.CLEANUP_INTERVAL_MS * 2
        ))

        coVerify(exactly = 1) { mockBlacklistRepository.cleanupExpiredRides() }
    }
}
