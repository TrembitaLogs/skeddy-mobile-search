package com.skeddy.util

import com.skeddy.error.SkeddyError
import com.skeddy.logging.SkeddyLogger
import kotlinx.coroutines.delay

/**
 * Результат операції з retry логікою.
 *
 * @param T тип успішного результату
 */
sealed class RetryResult<out T> {
    /**
     * Успішний результат.
     */
    data class Success<T>(val value: T) : RetryResult<T>()

    /**
     * Невдача після всіх спроб.
     */
    data class Failure(
        val error: SkeddyError,
        val attempts: Int,
        val lastException: Throwable? = null
    ) : RetryResult<Nothing>()

    /**
     * Перевіряє чи результат успішний.
     */
    val isSuccess: Boolean get() = this is Success

    /**
     * Перевіряє чи результат невдалий.
     */
    val isFailure: Boolean get() = this is Failure

    /**
     * Повертає значення або null.
     */
    fun getOrNull(): T? = when (this) {
        is Success -> value
        is Failure -> null
    }

    /**
     * Повертає значення або default.
     */
    fun getOrDefault(default: @UnsafeVariance T): T = when (this) {
        is Success -> value
        is Failure -> default
    }

    /**
     * Повертає значення або викликає block для отримання default.
     */
    inline fun getOrElse(block: (Failure) -> @UnsafeVariance T): T = when (this) {
        is Success -> value
        is Failure -> block(this)
    }

    /**
     * Трансформує успішне значення.
     */
    inline fun <R> map(transform: (T) -> R): RetryResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    /**
     * Виконує дію при успіху.
     */
    inline fun onSuccess(action: (T) -> Unit): RetryResult<T> {
        if (this is Success) action(value)
        return this
    }

    /**
     * Виконує дію при невдачі.
     */
    inline fun onFailure(action: (Failure) -> Unit): RetryResult<T> {
        if (this is Failure) action(this)
        return this
    }
}

/**
 * Конфігурація для retry логіки.
 */
data class RetryConfig(
    val maxAttempts: Int = 3,
    val initialDelayMs: Long = 1000L,
    val maxDelayMs: Long = 4000L,
    val factor: Double = 2.0,
    val tag: String = "RetryHelper"
)

/**
 * Стратегія для fallback при retry.
 */
interface RetryStrategy<T> {
    /**
     * Виконує спробу з заданим номером.
     *
     * @param attempt номер спроби (починаючи з 1)
     * @return результат спроби або null якщо не вдалося
     */
    suspend fun attempt(attempt: Int): T?

    /**
     * Повертає SkeddyError для невдачі.
     */
    fun getErrorOnFailure(): SkeddyError
}

/**
 * Помічник для виконання операцій з retry логікою та exponential backoff.
 */
object RetryHelper {
    private const val TAG = "RetryHelper"

    /**
     * Виконує дію з retry та exponential backoff.
     *
     * @param config конфігурація retry
     * @param errorOnFailure помилка при вичерпанні спроб
     * @param action дія для виконання (повертає true при успіху)
     * @return RetryResult з результатом
     */
    suspend fun retryWithBackoff(
        config: RetryConfig = RetryConfig(),
        errorOnFailure: SkeddyError,
        action: suspend (attempt: Int) -> Boolean
    ): RetryResult<Unit> {
        var currentDelay = config.initialDelayMs
        var lastException: Throwable? = null

        repeat(config.maxAttempts) { attemptIndex ->
            val attempt = attemptIndex + 1

            SkeddyLogger.d(
                config.tag,
                "Attempt #$attempt of ${config.maxAttempts}"
            )

            try {
                val result = action(attempt)
                if (result) {
                    SkeddyLogger.i(
                        config.tag,
                        "Success on attempt #$attempt"
                    )
                    return RetryResult.Success(Unit)
                }
                SkeddyLogger.w(
                    config.tag,
                    "Attempt #$attempt returned false"
                )
            } catch (e: Exception) {
                lastException = e
                SkeddyLogger.w(
                    config.tag,
                    "Attempt #$attempt threw exception: ${e.message}"
                )
            }

            // Чекаємо перед наступною спробою (окрім останньої)
            if (attempt < config.maxAttempts) {
                val delayToUse = currentDelay.coerceAtMost(config.maxDelayMs)
                SkeddyLogger.d(
                    config.tag,
                    "Waiting ${delayToUse}ms before next attempt..."
                )
                delay(delayToUse)
                currentDelay = (currentDelay * config.factor).toLong()
            }
        }

        SkeddyLogger.e(
            config.tag,
            "Failed after ${config.maxAttempts} attempts",
            lastException
        )

        return RetryResult.Failure(
            error = errorOnFailure,
            attempts = config.maxAttempts,
            lastException = lastException
        )
    }

    /**
     * Виконує дію з retry та повертає значення.
     *
     * @param config конфігурація retry
     * @param errorOnFailure помилка при вичерпанні спроб
     * @param action дія для виконання (повертає значення або null при невдачі)
     * @return RetryResult з результатом
     */
    suspend fun <T> retryWithBackoffValue(
        config: RetryConfig = RetryConfig(),
        errorOnFailure: SkeddyError,
        action: suspend (attempt: Int) -> T?
    ): RetryResult<T> {
        var currentDelay = config.initialDelayMs
        var lastException: Throwable? = null

        repeat(config.maxAttempts) { attemptIndex ->
            val attempt = attemptIndex + 1

            SkeddyLogger.d(
                config.tag,
                "Attempt #$attempt of ${config.maxAttempts}"
            )

            try {
                val result = action(attempt)
                if (result != null) {
                    SkeddyLogger.i(
                        config.tag,
                        "Success on attempt #$attempt"
                    )
                    return RetryResult.Success(result)
                }
                SkeddyLogger.w(
                    config.tag,
                    "Attempt #$attempt returned null"
                )
            } catch (e: Exception) {
                lastException = e
                SkeddyLogger.w(
                    config.tag,
                    "Attempt #$attempt threw exception: ${e.message}"
                )
            }

            // Чекаємо перед наступною спробою (окрім останньої)
            if (attempt < config.maxAttempts) {
                val delayToUse = currentDelay.coerceAtMost(config.maxDelayMs)
                SkeddyLogger.d(
                    config.tag,
                    "Waiting ${delayToUse}ms before next attempt..."
                )
                delay(delayToUse)
                currentDelay = (currentDelay * config.factor).toLong()
            }
        }

        SkeddyLogger.e(
            config.tag,
            "Failed after ${config.maxAttempts} attempts",
            lastException
        )

        return RetryResult.Failure(
            error = errorOnFailure,
            attempts = config.maxAttempts,
            lastException = lastException
        )
    }

    /**
     * Виконує стратегію з retry логікою.
     *
     * @param strategy стратегія для виконання
     * @param config конфігурація retry
     * @return RetryResult з результатом
     */
    suspend fun <T> executeStrategy(
        strategy: RetryStrategy<T>,
        config: RetryConfig = RetryConfig()
    ): RetryResult<T> {
        return retryWithBackoffValue(
            config = config,
            errorOnFailure = strategy.getErrorOnFailure()
        ) { attempt ->
            strategy.attempt(attempt)
        }
    }

    /**
     * Обчислює затримку для конкретної спроби.
     *
     * @param attempt номер спроби (починаючи з 1)
     * @param config конфігурація retry
     * @return затримка в мілісекундах
     */
    fun calculateDelay(attempt: Int, config: RetryConfig): Long {
        if (attempt <= 1) return config.initialDelayMs

        var delay = config.initialDelayMs
        repeat(attempt - 1) {
            delay = (delay * config.factor).toLong()
        }
        return delay.coerceAtMost(config.maxDelayMs)
    }
}

/**
 * Extension функція для конвертації Boolean в RetryResult.
 */
fun Boolean.toRetryResult(errorOnFalse: SkeddyError): RetryResult<Unit> {
    return if (this) {
        RetryResult.Success(Unit)
    } else {
        RetryResult.Failure(errorOnFalse, attempts = 1)
    }
}

/**
 * Extension функція для конвертації nullable значення в RetryResult.
 */
fun <T> T?.toRetryResult(errorOnNull: SkeddyError): RetryResult<T> {
    return if (this != null) {
        RetryResult.Success(this)
    } else {
        RetryResult.Failure(errorOnNull, attempts = 1)
    }
}
