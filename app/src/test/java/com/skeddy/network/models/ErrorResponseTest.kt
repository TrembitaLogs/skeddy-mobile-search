package com.skeddy.network.models

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ErrorResponseTest {

    private val json = Json { ignoreUnknownKeys = true }

    // --- Test 1: Valid JSON with code and message deserializes correctly ---

    @Test
    fun `ErrorResponse deserializes valid JSON with code and message`() {
        val errorJson = """
            {
              "error": {
                "code": "INVALID_CREDENTIALS",
                "message": "Invalid email or password"
              }
            }
        """.trimIndent()

        val response = json.decodeFromString<ErrorResponse>(errorJson)

        assertEquals("INVALID_CREDENTIALS", response.error.code)
        assertEquals("Invalid email or password", response.error.message)
    }

    // --- Test 2: Deserialization with different error codes from API Contract ---

    @Test
    fun `ErrorResponse deserializes VALIDATION_ERROR`() {
        val errorJson = """{"error": {"code": "VALIDATION_ERROR", "message": "Invalid request data"}}"""
        val response = json.decodeFromString<ErrorResponse>(errorJson)
        assertEquals("VALIDATION_ERROR", response.error.code)
    }

    @Test
    fun `ErrorResponse deserializes TOKEN_EXPIRED`() {
        val errorJson = """{"error": {"code": "TOKEN_EXPIRED", "message": "JWT token expired"}}"""
        val response = json.decodeFromString<ErrorResponse>(errorJson)
        assertEquals("TOKEN_EXPIRED", response.error.code)
    }

    @Test
    fun `ErrorResponse deserializes INVALID_TOKEN`() {
        val errorJson = """{"error": {"code": "INVALID_TOKEN", "message": "Invalid JWT or device token"}}"""
        val response = json.decodeFromString<ErrorResponse>(errorJson)
        assertEquals("INVALID_TOKEN", response.error.code)
    }

    @Test
    fun `ErrorResponse deserializes DEVICE_NOT_PAIRED`() {
        val errorJson = """{"error": {"code": "DEVICE_NOT_PAIRED", "message": "Device not paired"}}"""
        val response = json.decodeFromString<ErrorResponse>(errorJson)
        assertEquals("DEVICE_NOT_PAIRED", response.error.code)
    }

    @Test
    fun `ErrorResponse deserializes INVALID_TIMEZONE`() {
        val errorJson = """{"error": {"code": "INVALID_TIMEZONE", "message": "Invalid IANA timezone identifier"}}"""
        val response = json.decodeFromString<ErrorResponse>(errorJson)
        assertEquals("INVALID_TIMEZONE", response.error.code)
    }

    @Test
    fun `ErrorResponse deserializes RATE_LIMIT_EXCEEDED`() {
        val errorJson = """{"error": {"code": "RATE_LIMIT_EXCEEDED", "message": "Rate limit exceeded"}}"""
        val response = json.decodeFromString<ErrorResponse>(errorJson)
        assertEquals("RATE_LIMIT_EXCEEDED", response.error.code)
    }

    @Test
    fun `ErrorResponse deserializes SERVICE_UNAVAILABLE`() {
        val errorJson = """{"error": {"code": "SERVICE_UNAVAILABLE", "message": "Service temporarily unavailable"}}"""
        val response = json.decodeFromString<ErrorResponse>(errorJson)
        assertEquals("SERVICE_UNAVAILABLE", response.error.code)
    }

    // --- Test 3: parseErrorBody with null input returns null ---

    @Test
    fun `parseErrorBody returns null for null input`() {
        val result = parseErrorBody(null)
        assertNull(result)
    }

    // --- Test 4: parseErrorBody with empty string returns null ---

    @Test
    fun `parseErrorBody returns null for empty string`() {
        val result = parseErrorBody("")
        assertNull(result)
    }

    @Test
    fun `parseErrorBody returns null for blank string`() {
        val result = parseErrorBody("   ")
        assertNull(result)
    }

    // --- Test 5: parseErrorBody with invalid JSON returns null (graceful fallback) ---

    @Test
    fun `parseErrorBody returns null for invalid JSON`() {
        val result = parseErrorBody("not a json string")
        assertNull(result)
    }

    @Test
    fun `parseErrorBody returns null for HTML error page`() {
        val result = parseErrorBody("<html><body>502 Bad Gateway</body></html>")
        assertNull(result)
    }

    @Test
    fun `parseErrorBody returns null for JSON missing error field`() {
        val result = parseErrorBody("""{"message": "something went wrong"}""")
        assertNull(result)
    }

    // --- Test 6: parseErrorBody with valid JSON returns ErrorResponse object ---

    @Test
    fun `parseErrorBody returns ErrorResponse for valid error JSON`() {
        val validJson = """
            {
              "error": {
                "code": "INVALID_CREDENTIALS",
                "message": "Invalid email or password"
              }
            }
        """.trimIndent()

        val result = parseErrorBody(validJson)

        assertNotNull(result)
        assertEquals("INVALID_CREDENTIALS", result!!.error.code)
        assertEquals("Invalid email or password", result.error.message)
    }
}
