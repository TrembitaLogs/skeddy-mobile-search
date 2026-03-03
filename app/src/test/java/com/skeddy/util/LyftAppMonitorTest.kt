package com.skeddy.util

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.skeddy.accessibility.SkeddyAccessibilityService
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
class LyftAppMonitorTest {

    private lateinit var mockContext: Context
    private lateinit var mockPackageManager: PackageManager
    private lateinit var mockActivityManager: ActivityManager

    @Before
    fun setup() {
        mockContext = mockk(relaxed = true)
        mockPackageManager = mockk(relaxed = true)
        mockActivityManager = mockk(relaxed = true)

        every { mockContext.packageManager } returns mockPackageManager
        every { mockContext.getSystemService(Context.ACTIVITY_SERVICE) } returns mockActivityManager

        mockkObject(SkeddyAccessibilityService.Companion)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ==================== isLyftInstalled Tests ====================

    @Test
    fun `isLyftInstalled returns true when Lyft is installed`() {
        // Arrange
        val mockPackageInfo = mockk<PackageInfo>()
        every {
            mockPackageManager.getPackageInfo(
                LyftAppMonitor.LYFT_DRIVER_PACKAGE,
                any<PackageManager.PackageInfoFlags>()
            )
        } returns mockPackageInfo

        // Act
        val result = LyftAppMonitor.isLyftInstalled(mockContext)

        // Assert
        assertTrue(result)
    }

    @Test
    fun `isLyftInstalled returns false when Lyft not installed`() {
        // Arrange
        every {
            mockPackageManager.getPackageInfo(
                LyftAppMonitor.LYFT_DRIVER_PACKAGE,
                any<PackageManager.PackageInfoFlags>()
            )
        } throws PackageManager.NameNotFoundException()

        // Act
        val result = LyftAppMonitor.isLyftInstalled(mockContext)

        // Assert
        assertFalse(result)
    }

    @Test
    fun `isLyftInstalled returns false on exception`() {
        // Arrange
        every {
            mockPackageManager.getPackageInfo(
                LyftAppMonitor.LYFT_DRIVER_PACKAGE,
                any<PackageManager.PackageInfoFlags>()
            )
        } throws RuntimeException("Test exception")

        // Act
        val result = LyftAppMonitor.isLyftInstalled(mockContext)

        // Assert
        assertFalse(result)
    }

    // ==================== isLyftInForeground — Accessibility path ====================

    @Test
    fun `isLyftInForeground returns true when accessibility reports Lyft`() {
        every { SkeddyAccessibilityService.getLastForegroundPackage() } returns LyftAppMonitor.LYFT_DRIVER_PACKAGE

        val result = LyftAppMonitor.isLyftInForeground(mockContext)

        assertTrue(result)
    }

    @Test
    fun `isLyftInForeground returns false when accessibility reports other app`() {
        every { SkeddyAccessibilityService.getLastForegroundPackage() } returns "com.other.app"

        val result = LyftAppMonitor.isLyftInForeground(mockContext)

        assertFalse(result)
    }

    @Test
    fun `isLyftInForeground does not call getRunningTasks when accessibility has data`() {
        every { SkeddyAccessibilityService.getLastForegroundPackage() } returns "com.other.app"

        LyftAppMonitor.isLyftInForeground(mockContext)

        verify(exactly = 0) { mockActivityManager.getRunningTasks(any()) }
    }

    // ==================== isLyftInForeground — Legacy fallback ====================

    @Test
    fun `isLyftInForeground falls back to getRunningTasks when accessibility is null`() {
        every { SkeddyAccessibilityService.getLastForegroundPackage() } returns null

        val taskInfo = ActivityManager.RunningTaskInfo()
        taskInfo.topActivity = ComponentName(LyftAppMonitor.LYFT_DRIVER_PACKAGE, "MainActivity")
        @Suppress("DEPRECATION")
        every { mockActivityManager.getRunningTasks(1) } returns listOf(taskInfo)

        val result = LyftAppMonitor.isLyftInForeground(mockContext)

        assertTrue(result)
    }

    @Test
    fun `isLyftInForeground returns false via fallback when different app in foreground`() {
        every { SkeddyAccessibilityService.getLastForegroundPackage() } returns null

        val taskInfo = ActivityManager.RunningTaskInfo()
        taskInfo.topActivity = ComponentName("com.other.app", "MainActivity")
        @Suppress("DEPRECATION")
        every { mockActivityManager.getRunningTasks(1) } returns listOf(taskInfo)

        val result = LyftAppMonitor.isLyftInForeground(mockContext)

        assertFalse(result)
    }

    @Test
    fun `isLyftInForeground returns false via fallback when no running tasks`() {
        every { SkeddyAccessibilityService.getLastForegroundPackage() } returns null
        @Suppress("DEPRECATION")
        every { mockActivityManager.getRunningTasks(1) } returns emptyList()

        val result = LyftAppMonitor.isLyftInForeground(mockContext)

        assertFalse(result)
    }

    @Test
    fun `isLyftInForeground returns false via fallback when running tasks is null`() {
        every { SkeddyAccessibilityService.getLastForegroundPackage() } returns null
        @Suppress("DEPRECATION")
        every { mockActivityManager.getRunningTasks(1) } returns null

        val result = LyftAppMonitor.isLyftInForeground(mockContext)

        assertFalse(result)
    }

    @Test
    fun `isLyftInForeground returns false via fallback on SecurityException`() {
        every { SkeddyAccessibilityService.getLastForegroundPackage() } returns null
        @Suppress("DEPRECATION")
        every { mockActivityManager.getRunningTasks(1) } throws SecurityException("Permission denied")

        val result = LyftAppMonitor.isLyftInForeground(mockContext)

        assertFalse(result)
    }

    // ==================== launchLyftDriver Tests ====================

    @Test
    fun `launchLyftDriver returns Success when launch successful`() {
        // Arrange
        val mockPackageInfo = mockk<PackageInfo>()
        val mockLaunchIntent = mockk<Intent>(relaxed = true)

        every {
            mockPackageManager.getPackageInfo(
                LyftAppMonitor.LYFT_DRIVER_PACKAGE,
                any<PackageManager.PackageInfoFlags>()
            )
        } returns mockPackageInfo
        every {
            mockPackageManager.getLaunchIntentForPackage(LyftAppMonitor.LYFT_DRIVER_PACKAGE)
        } returns mockLaunchIntent

        // Act
        val result = LyftAppMonitor.launchLyftDriver(mockContext)

        // Assert
        assertTrue(result is LyftAppMonitor.LaunchResult.Success)
        verify { mockContext.startActivity(mockLaunchIntent) }
        verify { mockLaunchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        verify { mockLaunchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP) }
        verify { mockLaunchIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) }
    }

    @Test
    fun `launchLyftDriver returns AppNotInstalled when Lyft not installed`() {
        // Arrange
        every {
            mockPackageManager.getPackageInfo(
                LyftAppMonitor.LYFT_DRIVER_PACKAGE,
                any<PackageManager.PackageInfoFlags>()
            )
        } throws PackageManager.NameNotFoundException()

        // Act
        val result = LyftAppMonitor.launchLyftDriver(mockContext)

        // Assert
        assertTrue(result is LyftAppMonitor.LaunchResult.AppNotInstalled)
        verify(exactly = 0) { mockContext.startActivity(any()) }
    }

    @Test
    fun `launchLyftDriver returns NoLaunchIntent when intent is null`() {
        // Arrange
        val mockPackageInfo = mockk<PackageInfo>()

        every {
            mockPackageManager.getPackageInfo(
                LyftAppMonitor.LYFT_DRIVER_PACKAGE,
                any<PackageManager.PackageInfoFlags>()
            )
        } returns mockPackageInfo
        every {
            mockPackageManager.getLaunchIntentForPackage(LyftAppMonitor.LYFT_DRIVER_PACKAGE)
        } returns null

        // Act
        val result = LyftAppMonitor.launchLyftDriver(mockContext)

        // Assert
        assertTrue(result is LyftAppMonitor.LaunchResult.NoLaunchIntent)
        verify(exactly = 0) { mockContext.startActivity(any()) }
    }

    @Test
    fun `launchLyftDriver returns Error when startActivity throws`() {
        // Arrange
        val mockPackageInfo = mockk<PackageInfo>()
        val mockLaunchIntent = mockk<Intent>(relaxed = true)
        val testException = RuntimeException("Start activity failed")

        every {
            mockPackageManager.getPackageInfo(
                LyftAppMonitor.LYFT_DRIVER_PACKAGE,
                any<PackageManager.PackageInfoFlags>()
            )
        } returns mockPackageInfo
        every {
            mockPackageManager.getLaunchIntentForPackage(LyftAppMonitor.LYFT_DRIVER_PACKAGE)
        } returns mockLaunchIntent
        every { mockContext.startActivity(mockLaunchIntent) } throws testException

        // Act
        val result = LyftAppMonitor.launchLyftDriver(mockContext)

        // Assert
        assertTrue(result is LyftAppMonitor.LaunchResult.Error)
        assertEquals(testException, (result as LyftAppMonitor.LaunchResult.Error).exception)
    }

    // ==================== ensureLyftRunning Tests ====================

    @Test
    fun `ensureLyftRunning returns true when Lyft already in foreground`() {
        // Arrange: accessibility reports Lyft in foreground
        every { SkeddyAccessibilityService.getLastForegroundPackage() } returns LyftAppMonitor.LYFT_DRIVER_PACKAGE

        // Act
        val result = LyftAppMonitor.ensureLyftRunning(mockContext)

        // Assert
        assertTrue(result)
        verify(exactly = 0) { mockContext.startActivity(any()) }
    }

    @Test
    fun `ensureLyftRunning returns true when successfully launched`() {
        // Arrange: Lyft not in foreground but launch succeeds
        every { SkeddyAccessibilityService.getLastForegroundPackage() } returns "com.other.app"

        val mockPackageInfo = mockk<PackageInfo>()
        val mockLaunchIntent = mockk<Intent>(relaxed = true)

        every {
            mockPackageManager.getPackageInfo(
                LyftAppMonitor.LYFT_DRIVER_PACKAGE,
                any<PackageManager.PackageInfoFlags>()
            )
        } returns mockPackageInfo
        every {
            mockPackageManager.getLaunchIntentForPackage(LyftAppMonitor.LYFT_DRIVER_PACKAGE)
        } returns mockLaunchIntent

        // Act
        val result = LyftAppMonitor.ensureLyftRunning(mockContext)

        // Assert
        assertTrue(result)
        verify { mockContext.startActivity(mockLaunchIntent) }
    }

    @Test
    fun `ensureLyftRunning returns false when Lyft not installed`() {
        // Arrange: Lyft not in foreground and not installed
        every { SkeddyAccessibilityService.getLastForegroundPackage() } returns "com.other.app"

        every {
            mockPackageManager.getPackageInfo(
                LyftAppMonitor.LYFT_DRIVER_PACKAGE,
                any<PackageManager.PackageInfoFlags>()
            )
        } throws PackageManager.NameNotFoundException()

        // Act
        val result = LyftAppMonitor.ensureLyftRunning(mockContext)

        // Assert
        assertFalse(result)
    }

    // ==================== getLyftVersion Tests ====================

    @Test
    fun `getLyftVersion returns version when Lyft installed`() {
        // Arrange - use real PackageInfo and set public field
        val packageInfo = PackageInfo()
        packageInfo.versionName = "2024.1.5"

        every {
            mockPackageManager.getPackageInfo(
                LyftAppMonitor.LYFT_DRIVER_PACKAGE,
                any<PackageManager.PackageInfoFlags>()
            )
        } returns packageInfo

        // Act
        val result = LyftAppMonitor.getLyftVersion(mockContext)

        // Assert
        assertEquals("2024.1.5", result)
    }

    @Test
    fun `getLyftVersion returns null when Lyft not installed`() {
        // Arrange
        every {
            mockPackageManager.getPackageInfo(
                LyftAppMonitor.LYFT_DRIVER_PACKAGE,
                any<PackageManager.PackageInfoFlags>()
            )
        } throws PackageManager.NameNotFoundException()

        // Act
        val result = LyftAppMonitor.getLyftVersion(mockContext)

        // Assert
        assertNull(result)
    }

    // ==================== Package Constant Test ====================

    @Test
    fun `LYFT_DRIVER_PACKAGE has correct value`() {
        assertEquals("com.lyft.android.driver", LyftAppMonitor.LYFT_DRIVER_PACKAGE)
    }
}
