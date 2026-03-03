package com.skeddy.util

import android.content.Context
import android.os.PowerManager
import com.skeddy.accessibility.SkeddyAccessibilityService
import com.skeddy.utils.PermissionUtils
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DeviceHealthCollectorTest {

    private lateinit var mockContext: Context
    private lateinit var mockPowerManager: PowerManager
    private lateinit var collector: DeviceHealthCollector

    @Before
    fun setup() {
        mockContext = mockk(relaxed = true)
        mockPowerManager = mockk(relaxed = true)

        every { mockContext.getSystemService(Context.POWER_SERVICE) } returns mockPowerManager

        mockkObject(PermissionUtils)
        mockkObject(LyftAppMonitor)

        collector = DeviceHealthCollector(mockContext)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ==================== All True Scenario ====================

    @Test
    fun `collect returns all true when all health checks pass`() {
        // Arrange
        every {
            PermissionUtils.isAccessibilityServiceEnabled(
                mockContext,
                SkeddyAccessibilityService::class.java
            )
        } returns true
        every { LyftAppMonitor.isLyftInForeground(mockContext) } returns true
        every { mockPowerManager.isInteractive } returns true

        // Act
        val result = collector.collect()

        // Assert
        assertTrue(result.accessibilityEnabled)
        assertTrue(result.lyftRunning)
        assertTrue(result.screenOn)
    }

    // ==================== All False Scenario ====================

    @Test
    fun `collect returns all false when all health checks fail`() {
        // Arrange
        every {
            PermissionUtils.isAccessibilityServiceEnabled(
                mockContext,
                SkeddyAccessibilityService::class.java
            )
        } returns false
        every { LyftAppMonitor.isLyftInForeground(mockContext) } returns false
        every { mockPowerManager.isInteractive } returns false

        // Act
        val result = collector.collect()

        // Assert
        assertFalse(result.accessibilityEnabled)
        assertFalse(result.lyftRunning)
        assertFalse(result.screenOn)
    }

    // ==================== Mixed Combinations ====================

    @Test
    fun `collect returns correct values when only accessibility enabled`() {
        // Arrange
        every {
            PermissionUtils.isAccessibilityServiceEnabled(
                mockContext,
                SkeddyAccessibilityService::class.java
            )
        } returns true
        every { LyftAppMonitor.isLyftInForeground(mockContext) } returns false
        every { mockPowerManager.isInteractive } returns false

        // Act
        val result = collector.collect()

        // Assert
        assertTrue(result.accessibilityEnabled)
        assertFalse(result.lyftRunning)
        assertFalse(result.screenOn)
    }

    @Test
    fun `collect returns correct values when only lyft running`() {
        // Arrange
        every {
            PermissionUtils.isAccessibilityServiceEnabled(
                mockContext,
                SkeddyAccessibilityService::class.java
            )
        } returns false
        every { LyftAppMonitor.isLyftInForeground(mockContext) } returns true
        every { mockPowerManager.isInteractive } returns false

        // Act
        val result = collector.collect()

        // Assert
        assertFalse(result.accessibilityEnabled)
        assertTrue(result.lyftRunning)
        assertFalse(result.screenOn)
    }

    @Test
    fun `collect returns correct values when only screen on`() {
        // Arrange
        every {
            PermissionUtils.isAccessibilityServiceEnabled(
                mockContext,
                SkeddyAccessibilityService::class.java
            )
        } returns false
        every { LyftAppMonitor.isLyftInForeground(mockContext) } returns false
        every { mockPowerManager.isInteractive } returns true

        // Act
        val result = collector.collect()

        // Assert
        assertFalse(result.accessibilityEnabled)
        assertFalse(result.lyftRunning)
        assertTrue(result.screenOn)
    }

    @Test
    fun `collect returns correct values when accessibility and screen on but lyft not running`() {
        // Arrange
        every {
            PermissionUtils.isAccessibilityServiceEnabled(
                mockContext,
                SkeddyAccessibilityService::class.java
            )
        } returns true
        every { LyftAppMonitor.isLyftInForeground(mockContext) } returns false
        every { mockPowerManager.isInteractive } returns true

        // Act
        val result = collector.collect()

        // Assert
        assertTrue(result.accessibilityEnabled)
        assertFalse(result.lyftRunning)
        assertTrue(result.screenOn)
    }

    // ==================== PowerManager Edge Cases ====================

    @Test
    fun `collect returns screenOn false when PowerManager is null`() {
        // Arrange
        every { mockContext.getSystemService(Context.POWER_SERVICE) } returns null
        every {
            PermissionUtils.isAccessibilityServiceEnabled(
                mockContext,
                SkeddyAccessibilityService::class.java
            )
        } returns true
        every { LyftAppMonitor.isLyftInForeground(mockContext) } returns true

        // Act
        val result = collector.collect()

        // Assert
        assertTrue(result.accessibilityEnabled)
        assertTrue(result.lyftRunning)
        assertFalse(result.screenOn)
    }

    // ==================== Correct Method Invocations ====================

    @Test
    fun `collect calls PermissionUtils with correct parameters`() {
        // Arrange
        every {
            PermissionUtils.isAccessibilityServiceEnabled(any(), any())
        } returns true
        every { LyftAppMonitor.isLyftInForeground(any()) } returns true
        every { mockPowerManager.isInteractive } returns true

        // Act
        collector.collect()

        // Assert
        verify(exactly = 1) {
            PermissionUtils.isAccessibilityServiceEnabled(
                mockContext,
                SkeddyAccessibilityService::class.java
            )
        }
    }

    @Test
    fun `collect calls LyftAppMonitor with correct context`() {
        // Arrange
        every {
            PermissionUtils.isAccessibilityServiceEnabled(any(), any())
        } returns true
        every { LyftAppMonitor.isLyftInForeground(any()) } returns true
        every { mockPowerManager.isInteractive } returns true

        // Act
        collector.collect()

        // Assert
        verify(exactly = 1) { LyftAppMonitor.isLyftInForeground(mockContext) }
    }

    @Test
    fun `collect calls PowerManager isInteractive`() {
        // Arrange
        every {
            PermissionUtils.isAccessibilityServiceEnabled(any(), any())
        } returns true
        every { LyftAppMonitor.isLyftInForeground(any()) } returns true
        every { mockPowerManager.isInteractive } returns true

        // Act
        collector.collect()

        // Assert
        verify(exactly = 1) { mockPowerManager.isInteractive }
    }
}
