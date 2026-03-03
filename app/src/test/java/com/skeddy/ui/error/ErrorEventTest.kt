package com.skeddy.ui.error

import com.skeddy.error.SkeddyError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for ErrorEvent.
 *
 * Тестує single-use event pattern для уникнення
 * повторного відображення помилок при configuration changes.
 */
class ErrorEventTest {

    @Test
    fun `getErrorIfNotHandled returns error on first call`() {
        val error = SkeddyError.ParseTimeout
        val event = ErrorEvent(error)

        val result = event.getErrorIfNotHandled()

        assertNotNull(result)
        assertEquals(error, result)
    }

    @Test
    fun `getErrorIfNotHandled returns null on second call`() {
        val error = SkeddyError.ParseTimeout
        val event = ErrorEvent(error)

        // First call - should return error
        event.getErrorIfNotHandled()

        // Second call - should return null
        val result = event.getErrorIfNotHandled()

        assertNull(result)
    }

    @Test
    fun `peekError always returns error regardless of handled state`() {
        val error = SkeddyError.ParseTimeout
        val event = ErrorEvent(error)

        // Mark as handled
        event.getErrorIfNotHandled()

        // peekError should still return the error
        val result = event.peekError()

        assertEquals(error, result)
    }

    @Test
    fun `peekError can be called multiple times`() {
        val error = SkeddyError.LyftAppNotFound
        val event = ErrorEvent(error)

        // Call peekError multiple times
        val result1 = event.peekError()
        val result2 = event.peekError()
        val result3 = event.peekError()

        assertEquals(error, result1)
        assertEquals(error, result2)
        assertEquals(error, result3)
    }

    @Test
    fun `timestamp is set on creation`() {
        val beforeCreation = System.currentTimeMillis()
        val event = ErrorEvent(SkeddyError.ParseTimeout)
        val afterCreation = System.currentTimeMillis()

        assertTrue(event.timestamp >= beforeCreation)
        assertTrue(event.timestamp <= afterCreation)
    }

    @Test
    fun `custom timestamp is preserved`() {
        val customTimestamp = 1234567890L
        val event = ErrorEvent(SkeddyError.ParseTimeout, customTimestamp)

        assertEquals(customTimestamp, event.timestamp)
    }

    @Test
    fun `different error types work correctly`() {
        val errors = listOf(
            SkeddyError.LyftAppNotFound,
            SkeddyError.MenuButtonNotFound,
            SkeddyError.ParseTimeout,
            SkeddyError.DatabaseError,
            SkeddyError.ServiceKilled,
            SkeddyError.DatabaseOperationError("insert", "details"),
            SkeddyError.ScreenTimeout("main", 5000),
            SkeddyError.Custom("E999", "custom")
        )

        errors.forEach { error ->
            val event = ErrorEvent(error)

            // First call should return the error
            val result = event.getErrorIfNotHandled()
            assertEquals(error, result)

            // Second call should return null
            assertNull(event.getErrorIfNotHandled())

            // Peek should still work
            assertEquals(error, event.peekError())
        }
    }

    private fun assertTrue(condition: Boolean) {
        org.junit.Assert.assertTrue(condition)
    }
}
