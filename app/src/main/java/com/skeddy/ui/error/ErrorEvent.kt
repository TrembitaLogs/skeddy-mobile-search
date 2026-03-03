package com.skeddy.ui.error

import com.skeddy.error.SkeddyError

/**
 * Представляє подію помилки для відображення в UI.
 *
 * Використовує патерн "single-use event" щоб уникнути повторного
 * відображення помилки при зміні конфігурації (rotation, тощо).
 *
 * @property error Помилка для відображення
 * @property timestamp Час коли помилка сталася
 */
data class ErrorEvent(
    val error: SkeddyError,
    val timestamp: Long = System.currentTimeMillis()
) {
    private var hasBeenHandled = false

    /**
     * Повертає помилку якщо вона ще не була оброблена.
     * Після першого виклику повертає null.
     */
    fun getErrorIfNotHandled(): SkeddyError? {
        return if (hasBeenHandled) {
            null
        } else {
            hasBeenHandled = true
            error
        }
    }

    /**
     * Повертає помилку навіть якщо вона вже була оброблена.
     * Використовується для відображення в status indicator.
     */
    fun peekError(): SkeddyError = error
}
