package com.skeddy.ui.error

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.google.android.material.snackbar.Snackbar
import com.skeddy.R
import com.skeddy.error.SkeddyError

/**
 * Helper class для відображення user-friendly error messages в UI.
 *
 * Підтримує три типи відображення помилок:
 * 1. Snackbar - для transient помилок (ParseTimeout, тощо) з можливістю retry
 * 2. AlertDialog - для critical помилок (LyftAppNotFound, тощо) з action buttons
 * 3. Status indicator - для відображення останньої помилки в UI
 *
 * Забезпечує:
 * - Dismissable поведінку
 * - Захист від duplicate errors (не показує одну й ту саму помилку двічі поспіль)
 */
object ErrorDisplayHelper {

    private var lastShownErrorCode: String? = null
    private var lastShownTimestamp: Long = 0
    private const val DUPLICATE_THRESHOLD_MS = 2000L

    private var currentDialog: AlertDialog? = null
    private var currentSnackbar: Snackbar? = null

    /**
     * Callback для retry дії.
     */
    fun interface OnRetryCallback {
        fun onRetry()
    }

    /**
     * Callback для дії відкриття Lyft.
     */
    fun interface OnOpenLyftCallback {
        fun onOpenLyft()
    }

    /**
     * Показує помилку відповідним способом залежно від її типу.
     *
     * @param context Activity context для показу UI
     * @param rootView Root view для Snackbar
     * @param error Помилка для відображення
     * @param onRetry Callback для retry дії (опціонально)
     * @param onOpenLyft Callback для відкриття Lyft (опціонально)
     * @return true якщо помилка була показана, false якщо duplicate
     */
    fun showError(
        context: Context,
        rootView: View,
        error: SkeddyError,
        onRetry: OnRetryCallback? = null,
        onOpenLyft: OnOpenLyftCallback? = null
    ): Boolean {
        if (isDuplicateError(error)) {
            return false
        }

        markErrorAsShown(error)

        return when (getErrorDisplayType(error)) {
            ErrorDisplayType.SNACKBAR -> {
                showSnackbar(rootView, error, onRetry)
                true
            }
            ErrorDisplayType.DIALOG -> {
                showDialog(context, error, onOpenLyft)
                true
            }
        }
    }

    /**
     * Показує Snackbar для transient помилок.
     */
    fun showSnackbar(
        rootView: View,
        error: SkeddyError,
        onRetry: OnRetryCallback? = null
    ) {
        dismissCurrentSnackbar()

        val snackbar = Snackbar.make(
            rootView,
            error.getUserMessage(rootView.context),
            Snackbar.LENGTH_LONG
        )

        if (onRetry != null) {
            snackbar.setAction(R.string.error_action_retry) {
                onRetry.onRetry()
            }
        }

        currentSnackbar = snackbar
        snackbar.show()
    }

    /**
     * Показує AlertDialog для critical помилок.
     */
    fun showDialog(
        context: Context,
        error: SkeddyError,
        onOpenLyft: OnOpenLyftCallback? = null
    ) {
        dismissCurrentDialog()

        val builder = AlertDialog.Builder(context)
            .setTitle(getDialogTitle(context, error))
            .setMessage(error.getUserMessage(context))
            .setCancelable(true)

        when (error) {
            is SkeddyError.LyftAppNotFound -> {
                builder.setPositiveButton(R.string.error_action_open_lyft) { dialog, _ ->
                    dialog.dismiss()
                    onOpenLyft?.onOpenLyft() ?: openLyftInPlayStore(context)
                }
                builder.setNegativeButton(R.string.error_action_ignore) { dialog, _ ->
                    dialog.dismiss()
                }
            }
            is SkeddyError.AccessibilityNotEnabled -> {
                builder.setPositiveButton(R.string.btn_open_settings) { dialog, _ ->
                    dialog.dismiss()
                    openAccessibilitySettings(context)
                }
                builder.setNegativeButton(R.string.error_action_ignore) { dialog, _ ->
                    dialog.dismiss()
                }
            }
            is SkeddyError.ServiceKilled -> {
                builder.setPositiveButton(R.string.error_action_restart) { dialog, _ ->
                    dialog.dismiss()
                }
                builder.setNegativeButton(R.string.error_action_ignore) { dialog, _ ->
                    dialog.dismiss()
                }
            }
            else -> {
                builder.setPositiveButton(R.string.dialog_confirm) { dialog, _ ->
                    dialog.dismiss()
                }
            }
        }

        currentDialog = builder.create()
        currentDialog?.show()
    }

    /**
     * Визначає тип відображення для помилки.
     */
    fun getErrorDisplayType(error: SkeddyError): ErrorDisplayType {
        return when (error) {
            // Critical errors - показуємо діалог
            is SkeddyError.LyftAppNotFound,
            is SkeddyError.AccessibilityNotEnabled,
            is SkeddyError.ServiceKilled -> ErrorDisplayType.DIALOG

            // Transient/recoverable errors - показуємо Snackbar
            is SkeddyError.ParseTimeout,
            is SkeddyError.ScreenTimeout,
            is SkeddyError.MenuButtonNotFound,
            is SkeddyError.ScheduledRidesNotFound,
            is SkeddyError.DatabaseError,
            is SkeddyError.DatabaseOperationError,
            is SkeddyError.AccessibilityActionFailed,
            is SkeddyError.UnknownScreen,
            is SkeddyError.NavigationFailed,
            is SkeddyError.SystemDialogBlocking,
            is SkeddyError.Custom,
            is SkeddyError.YourRidesTabNotFound,
            is SkeddyError.ServerUnreachable,
            is SkeddyError.ServerUnauthorized,
            is SkeddyError.ServerRateLimited,
            is SkeddyError.ServerValidationError,
            is SkeddyError.ServerServiceUnavailable,
            is SkeddyError.ServerInternalError,
            is SkeddyError.LoginInvalidCredentials -> ErrorDisplayType.SNACKBAR
        }
    }

    /**
     * Перевіряє чи це duplicate помилка (та сама помилка показана нещодавно).
     */
    private fun isDuplicateError(error: SkeddyError): Boolean {
        val now = System.currentTimeMillis()
        return error.code == lastShownErrorCode &&
            (now - lastShownTimestamp) < DUPLICATE_THRESHOLD_MS
    }

    /**
     * Відмічає помилку як показану.
     */
    private fun markErrorAsShown(error: SkeddyError) {
        lastShownErrorCode = error.code
        lastShownTimestamp = System.currentTimeMillis()
    }

    /**
     * Скидає стан duplicate detection.
     */
    fun resetDuplicateDetection() {
        lastShownErrorCode = null
        lastShownTimestamp = 0
    }

    /**
     * Закриває поточний діалог якщо він відкритий.
     */
    fun dismissCurrentDialog() {
        currentDialog?.let {
            if (it.isShowing) {
                it.dismiss()
            }
        }
        currentDialog = null
    }

    /**
     * Закриває поточний Snackbar якщо він показується.
     */
    fun dismissCurrentSnackbar() {
        currentSnackbar?.dismiss()
        currentSnackbar = null
    }

    /**
     * Закриває всі поточні error UI елементи.
     */
    fun dismissAll() {
        dismissCurrentDialog()
        dismissCurrentSnackbar()
    }

    /**
     * Повертає заголовок діалогу для типу помилки.
     */
    private fun getDialogTitle(context: Context, error: SkeddyError): String {
        return when (error) {
            is SkeddyError.LyftAppNotFound -> context.getString(R.string.error_title_lyft_not_found)
            is SkeddyError.AccessibilityNotEnabled -> context.getString(R.string.error_title_accessibility)
            is SkeddyError.ServiceKilled -> context.getString(R.string.error_title_service_stopped)
            else -> context.getString(R.string.error_title_generic)
        }
    }

    /**
     * Відкриває Lyft в Play Store.
     */
    private fun openLyftInPlayStore(context: Context) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = android.net.Uri.parse("market://details?id=com.lyft.android.driver")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // Fallback to browser
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = android.net.Uri.parse("https://play.google.com/store/apps/details?id=com.lyft.android.driver")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    /**
     * Відкриває налаштування Accessibility.
     */
    private fun openAccessibilitySettings(context: Context) {
        val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

/**
 * Тип відображення помилки.
 */
enum class ErrorDisplayType {
    SNACKBAR,
    DIALOG
}
