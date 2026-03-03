package com.skeddy.util

import com.skeddy.error.SkeddyError
import com.skeddy.logging.SkeddyLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
@Config(manifest = Config.NONE)
class RetryHelperTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var logFile: File

    @Before
    fun setup() {
        SkeddyLogger.reset()
        logFile = tempFolder.newFile("test_logs.txt")
        SkeddyLogger.logToLogcat = false
        SkeddyLogger.initWithFile(logFile)
    }

    @After
    fun tearDown() {
        SkeddyLogger.reset()
    }

    // ==================== RetryConfig Tests ====================

    @Test
    fun `RetryConfig has correct default values`() {
        val config = RetryConfig()

        assertEquals(3, config.maxAttempts)
        assertEquals(1000L, config.initialDelayMs)
        assertEquals(4000L, config.maxDelayMs)
        assertEquals(2.0, config.factor, 0.01)
        assertEquals("RetryHelper", config.tag)
    }

    @Test
    fun `RetryConfig can be customized`() {
        val config = RetryConfig(
            maxAttempts = 5,
            initialDelayMs = 500L,
            maxDelayMs = 8000L,
            factor = 1.5,
            tag = "CustomTag"
        )

        assertEquals(5, config.maxAttempts)
        assertEquals(500L, config.initialDelayMs)
        assertEquals(8000L, config.maxDelayMs)
        assertEquals(1.5, config.factor, 0.01)
        assertEquals("CustomTag", config.tag)
    }

    // ==================== RetryResult Tests ====================

    @Test
    fun `RetryResult Success isSuccess returns true`() {
        val result: RetryResult<String> = RetryResult.Success("value")

        assertTrue(result.isSuccess)
        assertFalse(result.isFailure)
    }

    @Test
    fun `RetryResult Failure isFailure returns true`() {
        val result: RetryResult<String> = RetryResult.Failure(
            error = SkeddyError.MenuButtonNotFound,
            attempts = 3
        )

        assertFalse(result.isSuccess)
        assertTrue(result.isFailure)
    }

    @Test
    fun `RetryResult Success getOrNull returns value`() {
        val result: RetryResult<String> = RetryResult.Success("test")

        assertEquals("test", result.getOrNull())
    }

    @Test
    fun `RetryResult Failure getOrNull returns null`() {
        val result: RetryResult<String> = RetryResult.Failure(
            error = SkeddyError.MenuButtonNotFound,
            attempts = 3
        )

        assertNull(result.getOrNull())
    }

    @Test
    fun `RetryResult Success getOrDefault returns value`() {
        val result: RetryResult<String> = RetryResult.Success("test")

        assertEquals("test", result.getOrDefault("default"))
    }

    @Test
    fun `RetryResult Failure getOrDefault returns default`() {
        val result: RetryResult<String> = RetryResult.Failure(
            error = SkeddyError.MenuButtonNotFound,
            attempts = 3
        )

        assertEquals("default", result.getOrDefault("default"))
    }

    @Test
    fun `RetryResult Success map transforms value`() {
        val result: RetryResult<Int> = RetryResult.Success(5)
        val mapped = result.map { it * 2 }

        assertTrue(mapped.isSuccess)
        assertEquals(10, (mapped as RetryResult.Success).value)
    }

    @Test
    fun `RetryResult Failure map preserves failure`() {
        val result: RetryResult<Int> = RetryResult.Failure(
            error = SkeddyError.MenuButtonNotFound,
            attempts = 3
        )
        val mapped = result.map { it * 2 }

        assertTrue(mapped.isFailure)
    }

    @Test
    fun `RetryResult onSuccess executes for success`() {
        var executed = false
        val result: RetryResult<String> = RetryResult.Success("test")

        result.onSuccess { executed = true }

        assertTrue(executed)
    }

    @Test
    fun `RetryResult onSuccess does not execute for failure`() {
        var executed = false
        val result: RetryResult<String> = RetryResult.Failure(
            error = SkeddyError.MenuButtonNotFound,
            attempts = 3
        )

        result.onSuccess { executed = true }

        assertFalse(executed)
    }

    @Test
    fun `RetryResult onFailure executes for failure`() {
        var executed = false
        val result: RetryResult<String> = RetryResult.Failure(
            error = SkeddyError.MenuButtonNotFound,
            attempts = 3
        )

        result.onFailure { executed = true }

        assertTrue(executed)
    }

    @Test
    fun `RetryResult onFailure does not execute for success`() {
        var executed = false
        val result: RetryResult<String> = RetryResult.Success("test")

        result.onFailure { executed = true }

        assertFalse(executed)
    }

    @Test
    fun `RetryResult Failure contains error and attempts`() {
        val error = SkeddyError.ParseTimeout
        val result = RetryResult.Failure(error = error, attempts = 3)

        assertEquals(error, result.error)
        assertEquals(3, result.attempts)
    }

    @Test
    fun `RetryResult Failure can contain exception`() {
        val exception = RuntimeException("Test")
        val result = RetryResult.Failure(
            error = SkeddyError.DatabaseError,
            attempts = 2,
            lastException = exception
        )

        assertNotNull(result.lastException)
        assertEquals("Test", result.lastException?.message)
    }

    // ==================== retryWithBackoff Tests ====================

    @Test
    fun `retryWithBackoff succeeds on first attempt`() = runTest {
        var attemptCount = 0

        val result = RetryHelper.retryWithBackoff(
            config = RetryConfig(maxAttempts = 3, initialDelayMs = 10L),
            errorOnFailure = SkeddyError.MenuButtonNotFound
        ) { attempt ->
            attemptCount = attempt
            true
        }

        assertTrue(result.isSuccess)
        assertEquals(1, attemptCount)
    }

    @Test
    fun `retryWithBackoff succeeds on second attempt`() = runTest {
        var attemptCount = 0

        val result = RetryHelper.retryWithBackoff(
            config = RetryConfig(maxAttempts = 3, initialDelayMs = 10L),
            errorOnFailure = SkeddyError.MenuButtonNotFound
        ) { attempt ->
            attemptCount = attempt
            attempt >= 2 // Succeed on second attempt
        }

        assertTrue(result.isSuccess)
        assertEquals(2, attemptCount)
    }

    @Test
    fun `retryWithBackoff fails after max attempts`() = runTest {
        var attemptCount = 0

        val result = RetryHelper.retryWithBackoff(
            config = RetryConfig(maxAttempts = 3, initialDelayMs = 10L),
            errorOnFailure = SkeddyError.MenuButtonNotFound
        ) { attempt ->
            attemptCount = attempt
            false // Always fail
        }

        assertTrue(result.isFailure)
        assertEquals(3, attemptCount)

        val failure = result as RetryResult.Failure
        assertEquals(SkeddyError.MenuButtonNotFound, failure.error)
        assertEquals(3, failure.attempts)
    }

    @Test
    fun `retryWithBackoff handles exceptions`() = runTest {
        var attemptCount = 0

        val result = RetryHelper.retryWithBackoff(
            config = RetryConfig(maxAttempts = 3, initialDelayMs = 10L),
            errorOnFailure = SkeddyError.DatabaseError
        ) { attempt ->
            attemptCount = attempt
            if (attempt < 3) {
                throw RuntimeException("Attempt $attempt failed")
            }
            true
        }

        assertTrue(result.isSuccess)
        assertEquals(3, attemptCount)
    }

    @Test
    fun `retryWithBackoff captures last exception on failure`() = runTest {
        val result = RetryHelper.retryWithBackoff(
            config = RetryConfig(maxAttempts = 2, initialDelayMs = 10L),
            errorOnFailure = SkeddyError.DatabaseError
        ) {
            throw RuntimeException("Always fails")
        }

        assertTrue(result.isFailure)
        val failure = result as RetryResult.Failure
        assertNotNull(failure.lastException)
        assertTrue(failure.lastException is RuntimeException)
    }

    @Test
    fun `retryWithBackoff logs attempts`() = runTest {
        RetryHelper.retryWithBackoff(
            config = RetryConfig(maxAttempts = 2, initialDelayMs = 10L, tag = "TestRetry"),
            errorOnFailure = SkeddyError.MenuButtonNotFound
        ) { attempt ->
            attempt >= 2
        }

        val logs = SkeddyLogger.exportLogs()
        assertTrue(logs.contains("Attempt #1"))
        assertTrue(logs.contains("Attempt #2"))
    }

    // ==================== retryWithBackoffValue Tests ====================

    @Test
    fun `retryWithBackoffValue returns value on success`() = runTest {
        val result = RetryHelper.retryWithBackoffValue(
            config = RetryConfig(maxAttempts = 3, initialDelayMs = 10L),
            errorOnFailure = SkeddyError.ParseTimeout
        ) { attempt ->
            "Result from attempt $attempt"
        }

        assertTrue(result.isSuccess)
        assertEquals("Result from attempt 1", (result as RetryResult.Success).value)
    }

    @Test
    fun `retryWithBackoffValue retries on null`() = runTest {
        var attemptCount = 0

        val result = RetryHelper.retryWithBackoffValue(
            config = RetryConfig(maxAttempts = 3, initialDelayMs = 10L),
            errorOnFailure = SkeddyError.ParseTimeout
        ) { attempt ->
            attemptCount = attempt
            if (attempt < 2) null else "Success"
        }

        assertTrue(result.isSuccess)
        assertEquals("Success", (result as RetryResult.Success).value)
        assertEquals(2, attemptCount)
    }

    @Test
    fun `retryWithBackoffValue fails when all attempts return null`() = runTest {
        val result = RetryHelper.retryWithBackoffValue<String>(
            config = RetryConfig(maxAttempts = 2, initialDelayMs = 10L),
            errorOnFailure = SkeddyError.ScheduledRidesNotFound
        ) { null }

        assertTrue(result.isFailure)
        assertEquals(SkeddyError.ScheduledRidesNotFound, (result as RetryResult.Failure).error)
    }

    // ==================== calculateDelay Tests ====================

    @Test
    fun `calculateDelay returns initialDelay for first attempt`() {
        val config = RetryConfig(initialDelayMs = 1000L, factor = 2.0, maxDelayMs = 10000L)

        assertEquals(1000L, RetryHelper.calculateDelay(1, config))
    }

    @Test
    fun `calculateDelay applies exponential backoff`() {
        val config = RetryConfig(initialDelayMs = 1000L, factor = 2.0, maxDelayMs = 10000L)

        assertEquals(1000L, RetryHelper.calculateDelay(1, config)) // 1000
        assertEquals(2000L, RetryHelper.calculateDelay(2, config)) // 1000 * 2
        assertEquals(4000L, RetryHelper.calculateDelay(3, config)) // 1000 * 2 * 2
        assertEquals(8000L, RetryHelper.calculateDelay(4, config)) // 1000 * 2 * 2 * 2
    }

    @Test
    fun `calculateDelay respects maxDelay`() {
        val config = RetryConfig(initialDelayMs = 1000L, factor = 2.0, maxDelayMs = 3000L)

        assertEquals(1000L, RetryHelper.calculateDelay(1, config))
        assertEquals(2000L, RetryHelper.calculateDelay(2, config))
        assertEquals(3000L, RetryHelper.calculateDelay(3, config)) // Capped at maxDelay
        assertEquals(3000L, RetryHelper.calculateDelay(4, config)) // Still capped
    }

    @Test
    fun `calculateDelay works with custom factor`() {
        val config = RetryConfig(initialDelayMs = 100L, factor = 3.0, maxDelayMs = 10000L)

        assertEquals(100L, RetryHelper.calculateDelay(1, config))  // 100
        assertEquals(300L, RetryHelper.calculateDelay(2, config))  // 100 * 3
        assertEquals(900L, RetryHelper.calculateDelay(3, config))  // 100 * 3 * 3
        assertEquals(2700L, RetryHelper.calculateDelay(4, config)) // 100 * 3 * 3 * 3
    }

    // ==================== Extension Function Tests ====================

    @Test
    fun `Boolean toRetryResult returns Success for true`() {
        val result = true.toRetryResult(SkeddyError.MenuButtonNotFound)

        assertTrue(result.isSuccess)
    }

    @Test
    fun `Boolean toRetryResult returns Failure for false`() {
        val result = false.toRetryResult(SkeddyError.MenuButtonNotFound)

        assertTrue(result.isFailure)
        assertEquals(SkeddyError.MenuButtonNotFound, (result as RetryResult.Failure).error)
    }

    @Test
    fun `Nullable toRetryResult returns Success for non-null`() {
        val value: String? = "test"
        val result = value.toRetryResult(SkeddyError.ParseTimeout)

        assertTrue(result.isSuccess)
        assertEquals("test", (result as RetryResult.Success).value)
    }

    @Test
    fun `Nullable toRetryResult returns Failure for null`() {
        val value: String? = null
        val result = value.toRetryResult(SkeddyError.ParseTimeout)

        assertTrue(result.isFailure)
        assertEquals(SkeddyError.ParseTimeout, (result as RetryResult.Failure).error)
    }

    // ==================== Integration Tests ====================

    @Test
    fun `retryWithBackoff with fallback strategy`() = runTest {
        var usedFallback = false

        val result = RetryHelper.retryWithBackoff(
            config = RetryConfig(maxAttempts = 3, initialDelayMs = 10L),
            errorOnFailure = SkeddyError.MenuButtonNotFound
        ) { attempt ->
            if (attempt == 1) {
                // Primary method fails
                false
            } else {
                // Fallback succeeds
                usedFallback = true
                true
            }
        }

        assertTrue(result.isSuccess)
        assertTrue(usedFallback)
    }

    @Test
    fun `chained RetryResult operations`() {
        val result: RetryResult<Int> = RetryResult.Success(10)

        val finalValue = result
            .map { it * 2 }
            .map { it + 5 }
            .getOrDefault(0)

        assertEquals(25, finalValue)
    }

    @Test
    fun `getOrElse provides custom handling`() {
        val result: RetryResult<String> = RetryResult.Failure(
            error = SkeddyError.DatabaseError,
            attempts = 3
        )

        val value = result.getOrElse { failure ->
            "Failed after ${failure.attempts} attempts: ${failure.error.code}"
        }

        assertEquals("Failed after 3 attempts: E005", value)
    }
}
