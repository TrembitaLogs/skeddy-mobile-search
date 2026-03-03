package com.skeddy.recovery

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.skeddy.accessibility.SkeddyAccessibilityService
import com.skeddy.error.SkeddyError
import com.skeddy.logging.SkeddyLogger
import com.skeddy.navigation.LyftScreen
import com.skeddy.navigation.LyftUIElements
import com.skeddy.util.RetryResult
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AutoRecoveryManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mockContext: Context
    private lateinit var mockAccessibilityService: SkeddyAccessibilityService
    private lateinit var mockPackageManager: PackageManager
    private lateinit var mockRootNode: AccessibilityNodeInfo
    private lateinit var logFile: File
    private lateinit var recoveryManager: AutoRecoveryManager

    @Before
    fun setup() {
        SkeddyLogger.reset()
        logFile = tempFolder.newFile("test_logs.txt")
        SkeddyLogger.logToLogcat = false
        SkeddyLogger.initWithFile(logFile)

        mockContext = mockk(relaxed = true)
        mockAccessibilityService = mockk(relaxed = true)
        mockPackageManager = mockk(relaxed = true)
        mockRootNode = mockk(relaxed = true)

        every { mockContext.packageManager } returns mockPackageManager

        recoveryManager = AutoRecoveryManager(mockContext, mockAccessibilityService)
    }

    @After
    fun tearDown() {
        SkeddyLogger.reset()
        unmockkAll()
    }

    // ==================== ensureLyftActive Tests ====================

    @Test
    fun `ensureLyftActive returns success when Lyft already in foreground`() = runTest {
        // Arrange: Lyft is already active (captureLyftUIHierarchy returns non-null)
        every { mockAccessibilityService.captureLyftUIHierarchy() } returns mockRootNode

        // Act
        val result = recoveryManager.ensureLyftActive()

        // Assert
        assertTrue(result.isSuccess)
        verify(exactly = 0) { mockPackageManager.getLaunchIntentForPackage(any()) }
    }

    @Test
    fun `ensureLyftActive returns failure when Lyft not found`() = runTest {
        // Arrange: Lyft not active and not installed
        every { mockAccessibilityService.captureLyftUIHierarchy() } returns null
        every { mockAccessibilityService.windows } returns emptyList()
        every { mockPackageManager.getLaunchIntentForPackage(LyftUIElements.LYFT_DRIVER_PACKAGE) } returns null

        // Act
        val result = recoveryManager.ensureLyftActive()

        // Assert
        assertTrue(result.isFailure)
        val failure = result as RetryResult.Failure
        assertEquals(SkeddyError.LyftAppNotFound, failure.error)
    }

    @Test
    fun `ensureLyftActive launches Lyft when not in foreground`() = runTest {
        // Arrange: Lyft not active but installed
        val mockLaunchIntent = mockk<Intent>(relaxed = true)

        // First call: not active, subsequent calls: active
        every { mockAccessibilityService.captureLyftUIHierarchy() } returnsMany listOf(
            null,  // First check: not active
            mockRootNode  // After launch: active
        )
        every { mockAccessibilityService.windows } returns emptyList()
        every { mockPackageManager.getLaunchIntentForPackage(LyftUIElements.LYFT_DRIVER_PACKAGE) } returns mockLaunchIntent

        // Act
        val result = recoveryManager.ensureLyftActive()

        // Assert
        assertTrue(result.isSuccess)
        verify { mockContext.startActivity(mockLaunchIntent) }
    }

    @Test
    fun `ensureLyftActive logs operations`() = runTest {
        // Arrange
        every { mockAccessibilityService.captureLyftUIHierarchy() } returns mockRootNode

        // Act
        recoveryManager.ensureLyftActive()

        // Assert
        val logs = SkeddyLogger.exportLogs()
        assertTrue(logs.contains("ensureLyftActive"))
    }

    @Test
    fun `ensureLyftActive failure has correct error code`() = runTest {
        // Arrange
        every { mockAccessibilityService.captureLyftUIHierarchy() } returns null
        every { mockAccessibilityService.windows } returns emptyList()
        every { mockPackageManager.getLaunchIntentForPackage(LyftUIElements.LYFT_DRIVER_PACKAGE) } returns null

        // Act
        val result = recoveryManager.ensureLyftActive()

        // Assert
        assertTrue(result.isFailure)
        val failure = result as RetryResult.Failure
        assertEquals("E001", failure.error.code)
        assertEquals("Lyft Driver app not found or not installed", failure.error.message)
    }

    // ==================== ensureOnMainScreen Tests ====================

    @Test
    fun `ensureOnMainScreen returns success when already on main screen`() = runTest {
        // Arrange: Already on MAIN_SCREEN
        setupMockForScreen(LyftScreen.MAIN_SCREEN)

        // Act
        val result = recoveryManager.ensureOnMainScreen()

        // Assert
        assertTrue(result.isSuccess)
    }

    @Test
    fun `ensureOnMainScreen navigates back when not on main screen`() = runTest {
        // Arrange: Start on SIDE_MENU, then go to MAIN_SCREEN after back
        var backPressCount = 0

        // Default mocks - prevent any screen detection
        every { mockAccessibilityService.findLyftNodeByContentDesc(any(), any()) } returns null
        every { mockAccessibilityService.findLyftNodeByContentDescription(any(), any()) } returns null
        every { mockAccessibilityService.findLyftNodeById(any()) } returns null
        every { mockAccessibilityService.findLyftNodeByText(any(), any()) } returns null
        every { mockAccessibilityService.findAllLyftNodesById(any()) } returns emptyList()

        // Initially on SIDE_MENU: schedule menu item is visible
        every { mockAccessibilityService.findLyftNodeById("schedule") } returns mockRootNode
        every { mockRootNode.isClickable } returns true
        every { mockRootNode.parent } returns null

        // After back press, transition to MAIN_SCREEN
        every {
            mockAccessibilityService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        } answers {
            backPressCount++
            // After first back, switch to main screen:
            // - schedule item no longer visible
            // - Open menu button visible
            every { mockAccessibilityService.findLyftNodeById("schedule") } returns null
            every { mockAccessibilityService.findLyftNodeByContentDesc("Open menu", true) } returns mockRootNode
            true
        }

        // Act
        val result = recoveryManager.ensureOnMainScreen()

        // Assert
        assertTrue(result.isSuccess)
        assertTrue(backPressCount >= 1)
    }

    @Test
    fun `ensureOnMainScreen returns failure after max attempts`() = runTest {
        // Arrange: Always on RIDE_DETAILS, never reaches main
        setupMockForScreen(LyftScreen.RIDE_DETAILS)
        every {
            mockAccessibilityService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        } returns true

        // Act
        val result = recoveryManager.ensureOnMainScreen()

        // Assert
        assertTrue(result.isFailure)
        val failure = result as RetryResult.Failure
        assertEquals(SkeddyError.NavigationFailed, failure.error)
        assertEquals(3, failure.attempts)
    }

    @Test
    fun `ensureOnMainScreen logs progress`() = runTest {
        // Arrange
        setupMockForScreen(LyftScreen.MAIN_SCREEN)

        // Act
        recoveryManager.ensureOnMainScreen()

        // Assert
        val logs = SkeddyLogger.exportLogs()
        assertTrue(logs.contains("ensureOnMainScreen"))
    }

    @Test
    fun `ensureOnMainScreen failure has correct error code`() = runTest {
        // Arrange
        setupMockForScreen(LyftScreen.RIDE_DETAILS)
        every {
            mockAccessibilityService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        } returns true

        // Act
        val result = recoveryManager.ensureOnMainScreen()

        // Assert
        assertTrue(result.isFailure)
        val failure = result as RetryResult.Failure
        assertEquals("E010", failure.error.code)
        assertEquals("Failed to navigate back to main screen", failure.error.message)
    }

    // ==================== prepareForMonitoring Tests ====================

    @Test
    fun `prepareForMonitoring succeeds when Lyft active and on main screen`() = runTest {
        // Arrange: Lyft active and on main screen
        every { mockAccessibilityService.captureLyftUIHierarchy() } returns mockRootNode
        setupMockForScreen(LyftScreen.MAIN_SCREEN)

        // Act
        val result = recoveryManager.prepareForMonitoring()

        // Assert
        assertTrue(result.isSuccess)
    }

    @Test
    fun `prepareForMonitoring fails when Lyft not available`() = runTest {
        // Arrange: Lyft not installed
        every { mockAccessibilityService.captureLyftUIHierarchy() } returns null
        every { mockAccessibilityService.windows } returns emptyList()
        every { mockPackageManager.getLaunchIntentForPackage(LyftUIElements.LYFT_DRIVER_PACKAGE) } returns null

        // Act
        val result = recoveryManager.prepareForMonitoring()

        // Assert
        assertTrue(result.isFailure)
        val failure = result as RetryResult.Failure
        assertEquals(SkeddyError.LyftAppNotFound, failure.error)
    }

    @Test
    fun `prepareForMonitoring fails when cannot reach main screen`() = runTest {
        // Arrange: Lyft active but stuck on another screen
        every { mockAccessibilityService.captureLyftUIHierarchy() } returns mockRootNode
        setupMockForScreen(LyftScreen.RIDE_DETAILS)
        every {
            mockAccessibilityService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        } returns true

        // Act
        val result = recoveryManager.prepareForMonitoring()

        // Assert
        assertTrue(result.isFailure)
        val failure = result as RetryResult.Failure
        assertEquals(SkeddyError.NavigationFailed, failure.error)
    }

    @Test
    fun `prepareForMonitoring logs complete flow`() = runTest {
        // Arrange
        every { mockAccessibilityService.captureLyftUIHierarchy() } returns mockRootNode
        setupMockForScreen(LyftScreen.MAIN_SCREEN)

        // Act
        recoveryManager.prepareForMonitoring()

        // Assert
        val logs = SkeddyLogger.exportLogs()
        assertTrue(logs.contains("prepareForMonitoring"))
        assertTrue(logs.contains("Step 1") || logs.contains("Lyft"))
    }

    // ==================== Helper Methods ====================

    private fun setupMockForScreen(screen: LyftScreen) {
        // Reset all search methods to return null by default
        every { mockAccessibilityService.findLyftNodeByContentDesc(any(), any()) } returns null
        every { mockAccessibilityService.findLyftNodeByContentDescription(any(), any()) } returns null
        every { mockAccessibilityService.findLyftNodeById(any()) } returns null
        every { mockAccessibilityService.findLyftNodeByText(any(), any()) } returns null
        every { mockAccessibilityService.findAllLyftNodesById(any()) } returns emptyList()

        when (screen) {
            LyftScreen.MAIN_SCREEN -> {
                // Menu button visible (main screen indicator)
                every { mockAccessibilityService.findLyftNodeByContentDesc("Open menu", true) } returns mockRootNode
            }
            LyftScreen.SIDE_MENU -> {
                // Schedule menu item visible (side menu indicator)
                val mockScheduleItem = mockk<AccessibilityNodeInfo>(relaxed = true) {
                    every { isClickable } returns true
                    every { parent } returns null
                }
                every { mockAccessibilityService.findLyftNodeById("schedule") } returns mockScheduleItem
            }
            LyftScreen.RIDE_DETAILS -> {
                // Reserve button visible (ride details indicator)
                every { mockAccessibilityService.findLyftNodeByText("Reserve", true) } returns mockRootNode
            }
            LyftScreen.SCHEDULED_RIDES -> {
                // Your rides section visible
                every { mockAccessibilityService.findLyftNodeByText("Your rides", false) } returns mockRootNode
            }
            else -> {
                // UNKNOWN - nothing found, defaults already set
            }
        }
    }

    // ==================== Screen State Logging Tests ====================

    @Test
    fun `screen state is logged during recovery`() = runTest {
        // Arrange
        every { mockAccessibilityService.captureLyftUIHierarchy() } returns mockRootNode
        setupMockForScreen(LyftScreen.MAIN_SCREEN)

        // Act
        recoveryManager.ensureOnMainScreen()

        // Assert
        val logs = SkeddyLogger.exportLogs()
        assertTrue(logs.contains("MAIN_SCREEN") || logs.contains("screen"))
    }
}
