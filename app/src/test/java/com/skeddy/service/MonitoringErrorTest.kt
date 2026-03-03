package com.skeddy.service

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for MonitoringError sealed class.
 */
class MonitoringErrorTest {

    @Test
    fun `AccessibilityUnavailable has correct message`() {
        val error = MonitoringError.AccessibilityUnavailable

        assertEquals("Accessibility Service недоступний", error.message)
    }

    @Test
    fun `NavigationFailed has correct message`() {
        val error = MonitoringError.NavigationFailed

        assertEquals("Помилка навігації", error.message)
    }

    @Test
    fun `ParseFailed has correct message`() {
        val error = MonitoringError.ParseFailed

        assertEquals("Помилка парсингу", error.message)
    }

    @Test
    fun `UnexpectedError contains cause message`() {
        val cause = RuntimeException("Something went wrong")
        val error = MonitoringError.UnexpectedError(cause)

        assertEquals("Something went wrong", error.message)
        assertSame(cause, error.cause)
    }

    @Test
    fun `UnexpectedError handles null message`() {
        val cause = RuntimeException()
        val error = MonitoringError.UnexpectedError(cause)

        assertEquals("Невідома помилка", error.message)
    }

    @Test
    fun `MonitoringError types are distinguishable`() {
        val accessibility = MonitoringError.AccessibilityUnavailable
        val navigation = MonitoringError.NavigationFailed
        val parse = MonitoringError.ParseFailed
        val unexpected = MonitoringError.UnexpectedError(Exception())

        // Verify when expression can match all types
        val messages = listOf(accessibility, navigation, parse, unexpected).map { error ->
            when (error) {
                is MonitoringError.AccessibilityUnavailable -> "accessibility"
                is MonitoringError.NavigationFailed -> "navigation"
                is MonitoringError.ParseFailed -> "parse"
                is MonitoringError.UnexpectedError -> "unexpected"
            }
        }

        assertEquals(listOf("accessibility", "navigation", "parse", "unexpected"), messages)
    }
}
