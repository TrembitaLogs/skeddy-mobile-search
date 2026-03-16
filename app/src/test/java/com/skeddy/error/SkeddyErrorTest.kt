package com.skeddy.error

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkeddyErrorTest {

    // ==================== Error Code Uniqueness ====================

    @Test
    fun `all static error codes are unique`() {
        val codes = SkeddyError.allStaticErrors.map { it.code }
        val uniqueCodes = codes.toSet()

        assertEquals(
            "Duplicate error codes found: ${codes.groupBy { it }.filter { it.value.size > 1 }.keys}",
            codes.size,
            uniqueCodes.size
        )
    }

    @Test
    fun `all error codes follow E-number format`() {
        SkeddyError.allStaticErrors.forEach { error ->
            assertTrue(
                "Error code '${error.code}' should match E### format",
                error.code.matches(Regex("E\\d{3}"))
            )
        }
    }

    // ==================== Message Validation ====================

    @Test
    fun `all static errors have non-empty message`() {
        SkeddyError.allStaticErrors.forEach { error ->
            assertTrue(
                "Error ${error.code} should have non-empty message",
                error.message.isNotBlank()
            )
        }
    }

    @Test
    fun `all static errors have non-zero userMessageResId`() {
        SkeddyError.allStaticErrors.forEach { error ->
            assertTrue(
                "Error ${error.code} should have non-zero userMessageResId",
                error.userMessageResId != 0
            )
        }
    }

    @Test
    fun `all static errors have non-empty code`() {
        SkeddyError.allStaticErrors.forEach { error ->
            assertTrue(
                "Error should have non-empty code",
                error.code.isNotBlank()
            )
        }
    }

    // ==================== Specific Error Codes ====================

    @Test
    fun `LyftAppNotFound has code E001`() {
        assertEquals("E001", SkeddyError.LyftAppNotFound.code)
    }

    @Test
    fun `MenuButtonNotFound has code E002`() {
        assertEquals("E002", SkeddyError.MenuButtonNotFound.code)
    }

    @Test
    fun `ScheduledRidesNotFound has code E003`() {
        assertEquals("E003", SkeddyError.ScheduledRidesNotFound.code)
    }

    @Test
    fun `ParseTimeout has code E004`() {
        assertEquals("E004", SkeddyError.ParseTimeout.code)
    }

    @Test
    fun `DatabaseError has code E005`() {
        assertEquals("E005", SkeddyError.DatabaseError.code)
    }

    @Test
    fun `ServiceKilled has code E006`() {
        assertEquals("E006", SkeddyError.ServiceKilled.code)
    }

    @Test
    fun `AccessibilityNotEnabled has code E007`() {
        assertEquals("E007", SkeddyError.AccessibilityNotEnabled.code)
    }

    @Test
    fun `AccessibilityActionFailed has code E008`() {
        assertEquals("E008", SkeddyError.AccessibilityActionFailed.code)
    }

    @Test
    fun `UnknownScreen has code E009`() {
        assertEquals("E009", SkeddyError.UnknownScreen.code)
    }

    @Test
    fun `NavigationFailed has code E010`() {
        assertEquals("E010", SkeddyError.NavigationFailed.code)
    }

    @Test
    fun `SystemDialogBlocking has code E011`() {
        assertEquals("E011", SkeddyError.SystemDialogBlocking.code)
    }

    @Test
    fun `YourRidesTabNotFound has code E016`() {
        assertEquals("E016", SkeddyError.YourRidesTabNotFound.code)
    }

    // ==================== Server Error Codes ====================

    @Test
    fun `ServerUnreachable has code E020`() {
        assertEquals("E020", SkeddyError.ServerUnreachable.code)
    }

    @Test
    fun `ServerUnauthorized has code E021`() {
        assertEquals("E021", SkeddyError.ServerUnauthorized.code)
    }

    @Test
    fun `ServerRateLimited has code E022`() {
        val error = SkeddyError.ServerRateLimited()
        assertEquals("E022", error.code)
    }

    @Test
    fun `ServerValidationError has code E023`() {
        assertEquals("E023", SkeddyError.ServerValidationError.code)
    }

    @Test
    fun `ServerServiceUnavailable has code E024`() {
        assertEquals("E024", SkeddyError.ServerServiceUnavailable.code)
    }

    @Test
    fun `ServerInternalError has code E025`() {
        assertEquals("E025", SkeddyError.ServerInternalError.code)
    }

    @Test
    fun `LoginInvalidCredentials has code E026`() {
        assertEquals("E026", SkeddyError.LoginInvalidCredentials.code)
    }

    // ==================== Sealed Class Exhaustive Checking ====================

    @Test
    fun `when expression covers all static error types`() {
        SkeddyError.allStaticErrors.forEach { error ->
            val result = when (error) {
                is SkeddyError.LyftAppNotFound -> "lyft"
                is SkeddyError.MenuButtonNotFound -> "menu"
                is SkeddyError.ScheduledRidesNotFound -> "scheduled"
                is SkeddyError.ParseTimeout -> "parse"
                is SkeddyError.DatabaseError -> "db"
                is SkeddyError.ServiceKilled -> "service"
                is SkeddyError.AccessibilityNotEnabled -> "accessibility"
                is SkeddyError.AccessibilityActionFailed -> "action"
                is SkeddyError.UnknownScreen -> "screen"
                is SkeddyError.NavigationFailed -> "nav"
                is SkeddyError.DatabaseOperationError -> "db_op"
                is SkeddyError.ScreenTimeout -> "timeout"
                is SkeddyError.SystemDialogBlocking -> "dialog"
                is SkeddyError.Custom -> "custom"
                is SkeddyError.YourRidesTabNotFound -> "your_rides_tab"
                is SkeddyError.ServerUnreachable -> "server_unreachable"
                is SkeddyError.ServerUnauthorized -> "server_unauthorized"
                is SkeddyError.ServerRateLimited -> "server_rate_limited"
                is SkeddyError.ServerValidationError -> "server_validation"
                is SkeddyError.ServerServiceUnavailable -> "server_unavailable"
                is SkeddyError.ServerInternalError -> "server_internal"
                is SkeddyError.LoginInvalidCredentials -> "login_invalid_credentials"
            }
            assertTrue(
                "When expression should return non-empty result for ${error.code}",
                result.isNotBlank()
            )
        }
    }

    // ==================== Dynamic Error Types ====================

    @Test
    fun `DatabaseOperationError contains operation details`() {
        val error = SkeddyError.DatabaseOperationError(
            operation = "insert",
            details = "unique constraint violated"
        )

        assertEquals("E005", error.code)
        assertTrue(error.message.contains("insert"))
        assertTrue(error.message.contains("unique constraint violated"))
        assertTrue(error.userMessageResId != 0)
    }

    @Test
    fun `ScreenTimeout contains screen and timeout info`() {
        val error = SkeddyError.ScreenTimeout(
            expectedScreen = "SCHEDULED_RIDES",
            timeoutMs = 5000L
        )

        assertEquals("E004", error.code)
        assertTrue(error.message.contains("SCHEDULED_RIDES"))
        assertTrue(error.message.contains("5000"))
        assertTrue(error.userMessageResId != 0)
    }

    @Test
    fun `Custom error accepts custom values`() {
        val error = SkeddyError.Custom(
            customCode = "E999",
            customMessage = "Custom technical message"
        )

        assertEquals("E999", error.code)
        assertEquals("Custom technical message", error.message)
        assertTrue(error.userMessageResId != 0)
    }

    @Test
    fun `ServerRateLimited stores retryAfterSeconds`() {
        val errorWithRetry = SkeddyError.ServerRateLimited(retryAfterSeconds = 60L)
        assertEquals(60L, errorWithRetry.retryAfterSeconds)
        assertTrue(errorWithRetry.message.contains("60"))

        val errorWithoutRetry = SkeddyError.ServerRateLimited()
        assertNull(errorWithoutRetry.retryAfterSeconds)
    }

    @Test
    fun `ServerRateLimited default retryAfterSeconds is null`() {
        val error = SkeddyError.ServerRateLimited()
        assertNull(error.retryAfterSeconds)
        assertEquals("E022", error.code)
    }

    // ==================== Companion Object ====================

    @Test
    fun `fromCode returns correct error for valid code`() {
        val error = SkeddyError.fromCode("E001")

        assertNotNull(error)
        assertEquals(SkeddyError.LyftAppNotFound, error)
    }

    @Test
    fun `fromCode returns correct error for server error codes`() {
        assertEquals(SkeddyError.ServerUnreachable, SkeddyError.fromCode("E020"))
        assertEquals(SkeddyError.ServerUnauthorized, SkeddyError.fromCode("E021"))
        assertEquals(SkeddyError.ServerValidationError, SkeddyError.fromCode("E023"))
        assertEquals(SkeddyError.ServerServiceUnavailable, SkeddyError.fromCode("E024"))
        assertEquals(SkeddyError.ServerInternalError, SkeddyError.fromCode("E025"))
        assertEquals(SkeddyError.LoginInvalidCredentials, SkeddyError.fromCode("E026"))
    }

    @Test
    fun `fromCode returns null for invalid code`() {
        val error = SkeddyError.fromCode("INVALID")

        assertNull(error)
    }

    @Test
    fun `fromCode returns null for empty code`() {
        val error = SkeddyError.fromCode("")

        assertNull(error)
    }

    @Test
    fun `allStaticErrors contains all object errors`() {
        val errors = SkeddyError.allStaticErrors

        assertTrue(errors.contains(SkeddyError.LyftAppNotFound))
        assertTrue(errors.contains(SkeddyError.MenuButtonNotFound))
        assertTrue(errors.contains(SkeddyError.ScheduledRidesNotFound))
        assertTrue(errors.contains(SkeddyError.ParseTimeout))
        assertTrue(errors.contains(SkeddyError.DatabaseError))
        assertTrue(errors.contains(SkeddyError.ServiceKilled))
        assertTrue(errors.contains(SkeddyError.AccessibilityNotEnabled))
        assertTrue(errors.contains(SkeddyError.AccessibilityActionFailed))
        assertTrue(errors.contains(SkeddyError.UnknownScreen))
        assertTrue(errors.contains(SkeddyError.NavigationFailed))
        assertTrue(errors.contains(SkeddyError.SystemDialogBlocking))
        assertTrue(errors.contains(SkeddyError.YourRidesTabNotFound))
        assertTrue(errors.contains(SkeddyError.ServerUnreachable))
        assertTrue(errors.contains(SkeddyError.ServerUnauthorized))
        assertTrue(errors.contains(SkeddyError.ServerValidationError))
        assertTrue(errors.contains(SkeddyError.ServerServiceUnavailable))
        assertTrue(errors.contains(SkeddyError.ServerInternalError))
        assertTrue(errors.contains(SkeddyError.LoginInvalidCredentials))
    }

    @Test
    fun `allStaticErrors has expected count`() {
        assertEquals(18, SkeddyError.allStaticErrors.size)
    }

    // ==================== Data Class Equality ====================

    @Test
    fun `DatabaseOperationError equality works correctly`() {
        val error1 = SkeddyError.DatabaseOperationError("insert", "failed")
        val error2 = SkeddyError.DatabaseOperationError("insert", "failed")
        val error3 = SkeddyError.DatabaseOperationError("update", "failed")

        assertEquals(error1, error2)
        assertTrue(error1 != error3)
    }

    @Test
    fun `ScreenTimeout equality works correctly`() {
        val error1 = SkeddyError.ScreenTimeout("MAIN", 5000L)
        val error2 = SkeddyError.ScreenTimeout("MAIN", 5000L)
        val error3 = SkeddyError.ScreenTimeout("SIDE_MENU", 5000L)

        assertEquals(error1, error2)
        assertTrue(error1 != error3)
    }

    @Test
    fun `Custom error equality works correctly`() {
        val error1 = SkeddyError.Custom("E100", "msg")
        val error2 = SkeddyError.Custom("E100", "msg")
        val error3 = SkeddyError.Custom("E101", "msg")

        assertEquals(error1, error2)
        assertTrue(error1 != error3)
    }

    @Test
    fun `ServerRateLimited equality works correctly`() {
        val error1 = SkeddyError.ServerRateLimited(60L)
        val error2 = SkeddyError.ServerRateLimited(60L)
        val error3 = SkeddyError.ServerRateLimited(120L)

        assertEquals(error1, error2)
        assertTrue(error1 != error3)
    }
}
