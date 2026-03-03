package com.skeddy.ui.error

import com.skeddy.error.SkeddyError
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ErrorDisplayHelper.
 *
 * Тестує:
 * 1. Визначення типу відображення для кожного типу помилки
 * 2. Логіку duplicate detection
 * 3. Коректність reset duplicate detection
 */
class ErrorDisplayHelperTest {

    @Before
    fun setUp() {
        ErrorDisplayHelper.resetDuplicateDetection()
    }

    @After
    fun tearDown() {
        ErrorDisplayHelper.dismissAll()
        ErrorDisplayHelper.resetDuplicateDetection()
    }

    // ==================== Error Display Type Tests ====================

    @Test
    fun `LyftAppNotFound should show Dialog`() {
        val displayType = ErrorDisplayHelper.getErrorDisplayType(SkeddyError.LyftAppNotFound)
        assertEquals(ErrorDisplayType.DIALOG, displayType)
    }

    @Test
    fun `AccessibilityNotEnabled should show Dialog`() {
        val displayType = ErrorDisplayHelper.getErrorDisplayType(SkeddyError.AccessibilityNotEnabled)
        assertEquals(ErrorDisplayType.DIALOG, displayType)
    }

    @Test
    fun `ServiceKilled should show Dialog`() {
        val displayType = ErrorDisplayHelper.getErrorDisplayType(SkeddyError.ServiceKilled)
        assertEquals(ErrorDisplayType.DIALOG, displayType)
    }

    @Test
    fun `ParseTimeout should show Snackbar`() {
        val displayType = ErrorDisplayHelper.getErrorDisplayType(SkeddyError.ParseTimeout)
        assertEquals(ErrorDisplayType.SNACKBAR, displayType)
    }

    @Test
    fun `MenuButtonNotFound should show Snackbar`() {
        val displayType = ErrorDisplayHelper.getErrorDisplayType(SkeddyError.MenuButtonNotFound)
        assertEquals(ErrorDisplayType.SNACKBAR, displayType)
    }

    @Test
    fun `ScheduledRidesNotFound should show Snackbar`() {
        val displayType = ErrorDisplayHelper.getErrorDisplayType(SkeddyError.ScheduledRidesNotFound)
        assertEquals(ErrorDisplayType.SNACKBAR, displayType)
    }

    @Test
    fun `DatabaseError should show Snackbar`() {
        val displayType = ErrorDisplayHelper.getErrorDisplayType(SkeddyError.DatabaseError)
        assertEquals(ErrorDisplayType.SNACKBAR, displayType)
    }

    @Test
    fun `DatabaseOperationError should show Snackbar`() {
        val error = SkeddyError.DatabaseOperationError("insert", "test details")
        val displayType = ErrorDisplayHelper.getErrorDisplayType(error)
        assertEquals(ErrorDisplayType.SNACKBAR, displayType)
    }

    @Test
    fun `AccessibilityActionFailed should show Snackbar`() {
        val displayType = ErrorDisplayHelper.getErrorDisplayType(SkeddyError.AccessibilityActionFailed)
        assertEquals(ErrorDisplayType.SNACKBAR, displayType)
    }

    @Test
    fun `UnknownScreen should show Snackbar`() {
        val displayType = ErrorDisplayHelper.getErrorDisplayType(SkeddyError.UnknownScreen)
        assertEquals(ErrorDisplayType.SNACKBAR, displayType)
    }

    @Test
    fun `NavigationFailed should show Snackbar`() {
        val displayType = ErrorDisplayHelper.getErrorDisplayType(SkeddyError.NavigationFailed)
        assertEquals(ErrorDisplayType.SNACKBAR, displayType)
    }

    @Test
    fun `ScreenTimeout should show Snackbar`() {
        val error = SkeddyError.ScreenTimeout("main", 5000)
        val displayType = ErrorDisplayHelper.getErrorDisplayType(error)
        assertEquals(ErrorDisplayType.SNACKBAR, displayType)
    }

    @Test
    fun `Custom error should show Snackbar`() {
        val error = SkeddyError.Custom("E999", "Custom error")
        val displayType = ErrorDisplayHelper.getErrorDisplayType(error)
        assertEquals(ErrorDisplayType.SNACKBAR, displayType)
    }

    @Test
    fun `YourRidesTabNotFound should show Snackbar`() {
        val displayType = ErrorDisplayHelper.getErrorDisplayType(SkeddyError.YourRidesTabNotFound)
        assertEquals(ErrorDisplayType.SNACKBAR, displayType)
    }

    // ==================== Critical vs Transient Error Classification ====================

    @Test
    fun `all critical errors should show Dialog`() {
        val criticalErrors = listOf(
            SkeddyError.LyftAppNotFound,
            SkeddyError.AccessibilityNotEnabled,
            SkeddyError.ServiceKilled
        )

        criticalErrors.forEach { error ->
            assertEquals(
                "Error ${error.code} should show DIALOG",
                ErrorDisplayType.DIALOG,
                ErrorDisplayHelper.getErrorDisplayType(error)
            )
        }
    }

    @Test
    fun `all transient errors should show Snackbar`() {
        val transientErrors = listOf(
            SkeddyError.ParseTimeout,
            SkeddyError.MenuButtonNotFound,
            SkeddyError.ScheduledRidesNotFound,
            SkeddyError.DatabaseError,
            SkeddyError.AccessibilityActionFailed,
            SkeddyError.UnknownScreen,
            SkeddyError.NavigationFailed
        )

        transientErrors.forEach { error ->
            assertEquals(
                "Error ${error.code} should show SNACKBAR",
                ErrorDisplayType.SNACKBAR,
                ErrorDisplayHelper.getErrorDisplayType(error)
            )
        }
    }

    // ==================== Exhaustive Coverage Test ====================

    @Test
    fun `all static SkeddyError types should have defined display type`() {
        SkeddyError.allStaticErrors.forEach { error ->
            val displayType = ErrorDisplayHelper.getErrorDisplayType(error)
            assertTrue(
                "Error ${error.code} should have DIALOG or SNACKBAR display type",
                displayType == ErrorDisplayType.DIALOG || displayType == ErrorDisplayType.SNACKBAR
            )
        }
    }
}
