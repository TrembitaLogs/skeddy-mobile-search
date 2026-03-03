package com.skeddy.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiResultTest {

    // ==================== Success ====================

    @Test
    fun `Success holds data correctly`() {
        val result: ApiResult<String> = ApiResult.Success("hello")

        assertTrue(result is ApiResult.Success)
        assertEquals("hello", (result as ApiResult.Success).data)
    }

    @Test
    fun `Success with complex type`() {
        data class User(val id: Int, val name: String)

        val user = User(1, "Alice")
        val result: ApiResult<User> = ApiResult.Success(user)

        assertEquals(user, (result as ApiResult.Success).data)
    }

    // ==================== Error Types Creation ====================

    @Test
    fun `NetworkError can be created`() {
        val result: ApiResult<String> = ApiResult.NetworkError

        assertTrue(result is ApiResult.NetworkError)
    }

    @Test
    fun `Unauthorized can be created`() {
        val result: ApiResult<String> = ApiResult.Unauthorized

        assertTrue(result is ApiResult.Unauthorized)
    }

    @Test
    fun `ValidationError holds message`() {
        val result: ApiResult<String> = ApiResult.ValidationError("Invalid timezone")

        assertTrue(result is ApiResult.ValidationError)
        assertEquals("Invalid timezone", (result as ApiResult.ValidationError).message)
    }

    @Test
    fun `RateLimited holds retryAfterSeconds`() {
        val result: ApiResult<String> = ApiResult.RateLimited(120)

        assertTrue(result is ApiResult.RateLimited)
        assertEquals(120, (result as ApiResult.RateLimited).retryAfterSeconds)
    }

    @Test
    fun `ServiceUnavailable can be created`() {
        val result: ApiResult<String> = ApiResult.ServiceUnavailable

        assertTrue(result is ApiResult.ServiceUnavailable)
    }

    @Test
    fun `ServerError can be created`() {
        val result: ApiResult<String> = ApiResult.ServerError

        assertTrue(result is ApiResult.ServerError)
    }

    @Test
    fun `PairingError with INVALID_OR_EXPIRED`() {
        val result: ApiResult<String> = ApiResult.PairingError(PairingErrorReason.INVALID_OR_EXPIRED)

        assertTrue(result is ApiResult.PairingError)
        assertEquals(
            PairingErrorReason.INVALID_OR_EXPIRED,
            (result as ApiResult.PairingError).reason
        )
    }

    @Test
    fun `PairingError with ALREADY_USED`() {
        val result: ApiResult<String> = ApiResult.PairingError(PairingErrorReason.ALREADY_USED)

        assertTrue(result is ApiResult.PairingError)
        assertEquals(
            PairingErrorReason.ALREADY_USED,
            (result as ApiResult.PairingError).reason
        )
    }

    // ==================== PairingErrorReason ====================

    @Test
    fun `PairingErrorReason has exactly two values`() {
        assertEquals(2, PairingErrorReason.entries.size)
    }

    @Test
    fun `PairingErrorReason values are correct`() {
        val values = PairingErrorReason.entries.map { it.name }

        assertTrue(values.contains("INVALID_OR_EXPIRED"))
        assertTrue(values.contains("ALREADY_USED"))
    }

    // ==================== Pattern Matching (when expression) ====================

    @Test
    fun `when expression covers all ApiResult types`() {
        val results: List<ApiResult<String>> = listOf(
            ApiResult.Success("data"),
            ApiResult.NetworkError,
            ApiResult.Unauthorized,
            ApiResult.ValidationError("msg"),
            ApiResult.RateLimited(60),
            ApiResult.ServiceUnavailable,
            ApiResult.ServerError,
            ApiResult.PairingError(PairingErrorReason.INVALID_OR_EXPIRED),
            ApiResult.PairingError(PairingErrorReason.ALREADY_USED)
        )

        results.forEach { result ->
            val label = when (result) {
                is ApiResult.Success -> "success"
                is ApiResult.NetworkError -> "network"
                is ApiResult.Unauthorized -> "unauthorized"
                is ApiResult.ValidationError -> "validation"
                is ApiResult.RateLimited -> "rate_limited"
                is ApiResult.ServiceUnavailable -> "unavailable"
                is ApiResult.ServerError -> "server"
                is ApiResult.PairingError -> "pairing"
            }
            assertTrue(label.isNotBlank())
        }
    }

    // ==================== isSuccess() ====================

    @Test
    fun `isSuccess returns true for Success`() {
        val result: ApiResult<String> = ApiResult.Success("data")

        assertTrue(result.isSuccess())
    }

    @Test
    fun `isSuccess returns false for NetworkError`() {
        assertFalse(ApiResult.NetworkError.isSuccess())
    }

    @Test
    fun `isSuccess returns false for Unauthorized`() {
        assertFalse(ApiResult.Unauthorized.isSuccess())
    }

    @Test
    fun `isSuccess returns false for ValidationError`() {
        assertFalse(ApiResult.ValidationError("msg").isSuccess())
    }

    @Test
    fun `isSuccess returns false for RateLimited`() {
        assertFalse(ApiResult.RateLimited(60).isSuccess())
    }

    @Test
    fun `isSuccess returns false for ServiceUnavailable`() {
        assertFalse(ApiResult.ServiceUnavailable.isSuccess())
    }

    @Test
    fun `isSuccess returns false for ServerError`() {
        assertFalse(ApiResult.ServerError.isSuccess())
    }

    @Test
    fun `isSuccess returns false for PairingError`() {
        assertFalse(ApiResult.PairingError(PairingErrorReason.INVALID_OR_EXPIRED).isSuccess())
    }

    // ==================== getOrNull() ====================

    @Test
    fun `getOrNull returns data for Success`() {
        val result: ApiResult<String> = ApiResult.Success("hello")

        assertEquals("hello", result.getOrNull())
    }

    @Test
    fun `getOrNull returns null for NetworkError`() {
        val result: ApiResult<String> = ApiResult.NetworkError

        assertNull(result.getOrNull())
    }

    @Test
    fun `getOrNull returns null for Unauthorized`() {
        val result: ApiResult<String> = ApiResult.Unauthorized

        assertNull(result.getOrNull())
    }

    @Test
    fun `getOrNull returns null for ValidationError`() {
        val result: ApiResult<String> = ApiResult.ValidationError("msg")

        assertNull(result.getOrNull())
    }

    @Test
    fun `getOrNull returns null for RateLimited`() {
        val result: ApiResult<String> = ApiResult.RateLimited(60)

        assertNull(result.getOrNull())
    }

    @Test
    fun `getOrNull returns null for ServiceUnavailable`() {
        val result: ApiResult<String> = ApiResult.ServiceUnavailable

        assertNull(result.getOrNull())
    }

    @Test
    fun `getOrNull returns null for ServerError`() {
        val result: ApiResult<String> = ApiResult.ServerError

        assertNull(result.getOrNull())
    }

    @Test
    fun `getOrNull returns null for PairingError`() {
        val result: ApiResult<String> = ApiResult.PairingError(PairingErrorReason.ALREADY_USED)

        assertNull(result.getOrNull())
    }

    // ==================== fold() ====================

    @Test
    fun `fold calls onSuccess for Success`() {
        val result: ApiResult<Int> = ApiResult.Success(42)

        val folded = result.fold(
            onSuccess = { "value=$it" },
            onFailure = { "error" }
        )

        assertEquals("value=42", folded)
    }

    @Test
    fun `fold calls onFailure for NetworkError`() {
        val result: ApiResult<Int> = ApiResult.NetworkError

        val folded = result.fold(
            onSuccess = { "value=$it" },
            onFailure = { "error:${it::class.simpleName}" }
        )

        assertEquals("error:NetworkError", folded)
    }

    @Test
    fun `fold calls onFailure for Unauthorized`() {
        val result: ApiResult<Int> = ApiResult.Unauthorized

        val folded = result.fold(
            onSuccess = { "value=$it" },
            onFailure = { "error:${it::class.simpleName}" }
        )

        assertEquals("error:Unauthorized", folded)
    }

    @Test
    fun `fold calls onFailure for ValidationError`() {
        val result: ApiResult<Int> = ApiResult.ValidationError("bad data")

        val folded = result.fold(
            onSuccess = { "value=$it" },
            onFailure = { error ->
                when (error) {
                    is ApiResult.ValidationError -> "validation:${error.message}"
                    else -> "other"
                }
            }
        )

        assertEquals("validation:bad data", folded)
    }

    @Test
    fun `fold calls onFailure for RateLimited`() {
        val result: ApiResult<Int> = ApiResult.RateLimited(30)

        val folded = result.fold(
            onSuccess = { "value=$it" },
            onFailure = { error ->
                when (error) {
                    is ApiResult.RateLimited -> "retry:${error.retryAfterSeconds}"
                    else -> "other"
                }
            }
        )

        assertEquals("retry:30", folded)
    }

    @Test
    fun `fold calls onFailure for ServiceUnavailable`() {
        val result: ApiResult<Int> = ApiResult.ServiceUnavailable

        val folded = result.fold(
            onSuccess = { "value=$it" },
            onFailure = { "error:${it::class.simpleName}" }
        )

        assertEquals("error:ServiceUnavailable", folded)
    }

    @Test
    fun `fold calls onFailure for ServerError`() {
        val result: ApiResult<Int> = ApiResult.ServerError

        val folded = result.fold(
            onSuccess = { "value=$it" },
            onFailure = { "error:${it::class.simpleName}" }
        )

        assertEquals("error:ServerError", folded)
    }

    @Test
    fun `fold calls onFailure for PairingError`() {
        val result: ApiResult<Int> = ApiResult.PairingError(PairingErrorReason.INVALID_OR_EXPIRED)

        val folded = result.fold(
            onSuccess = { "value=$it" },
            onFailure = { error ->
                when (error) {
                    is ApiResult.PairingError -> "pairing:${error.reason}"
                    else -> "other"
                }
            }
        )

        assertEquals("pairing:INVALID_OR_EXPIRED", folded)
    }

    // ==================== Covariance (out T) ====================

    @Test
    fun `error types are assignable to any ApiResult generic`() {
        val stringResult: ApiResult<String> = ApiResult.NetworkError
        val intResult: ApiResult<Int> = ApiResult.NetworkError
        val listResult: ApiResult<List<Double>> = ApiResult.Unauthorized

        assertFalse(stringResult.isSuccess())
        assertFalse(intResult.isSuccess())
        assertFalse(listResult.isSuccess())
    }

    // ==================== Data Class Equality ====================

    @Test
    fun `Success equality works correctly`() {
        val a = ApiResult.Success("x")
        val b = ApiResult.Success("x")
        val c = ApiResult.Success("y")

        assertEquals(a, b)
        assertTrue(a != c)
    }

    @Test
    fun `ValidationError equality works correctly`() {
        val a = ApiResult.ValidationError("msg1")
        val b = ApiResult.ValidationError("msg1")
        val c = ApiResult.ValidationError("msg2")

        assertEquals(a, b)
        assertTrue(a != c)
    }

    @Test
    fun `RateLimited equality works correctly`() {
        val a = ApiResult.RateLimited(60)
        val b = ApiResult.RateLimited(60)
        val c = ApiResult.RateLimited(120)

        assertEquals(a, b)
        assertTrue(a != c)
    }

    @Test
    fun `PairingError equality works correctly`() {
        val a = ApiResult.PairingError(PairingErrorReason.INVALID_OR_EXPIRED)
        val b = ApiResult.PairingError(PairingErrorReason.INVALID_OR_EXPIRED)
        val c = ApiResult.PairingError(PairingErrorReason.ALREADY_USED)

        assertEquals(a, b)
        assertTrue(a != c)
    }

    @Test
    fun `Object error types are singletons`() {
        assertTrue(ApiResult.NetworkError === ApiResult.NetworkError)
        assertTrue(ApiResult.Unauthorized === ApiResult.Unauthorized)
        assertTrue(ApiResult.ServiceUnavailable === ApiResult.ServiceUnavailable)
        assertTrue(ApiResult.ServerError === ApiResult.ServerError)
    }
}
