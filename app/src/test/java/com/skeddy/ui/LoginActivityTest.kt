package com.skeddy.ui

import android.content.Intent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.skeddy.R
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class LoginActivityTest {

    // ==================== Form Validation (Pure Logic) ====================

    @Test
    fun `isFormValid returns true for valid email and non-empty password`() {
        assertTrue(LoginActivity.isFormValid("user@example.com", "password123"))
    }

    @Test
    fun `isFormValid returns false for invalid email`() {
        assertFalse(LoginActivity.isFormValid("not-an-email", "password123"))
    }

    @Test
    fun `isFormValid returns false for empty email`() {
        assertFalse(LoginActivity.isFormValid("", "password123"))
    }

    @Test
    fun `isFormValid returns false for null email`() {
        assertFalse(LoginActivity.isFormValid(null, "password123"))
    }

    @Test
    fun `isFormValid returns false for empty password`() {
        assertFalse(LoginActivity.isFormValid("user@example.com", ""))
    }

    @Test
    fun `isFormValid returns false for null password`() {
        assertFalse(LoginActivity.isFormValid("user@example.com", null))
    }

    @Test
    fun `isFormValid returns false for blank email`() {
        assertFalse(LoginActivity.isFormValid("   ", "password"))
    }

    @Test
    fun `isFormValid returns false for blank password`() {
        assertFalse(LoginActivity.isFormValid("user@example.com", "   "))
    }

    // ==================== Button State via TextWatcher ====================

    @Test
    fun `login button is disabled on initial create`() {
        val activity = createActivity()
        assertFalse(activity.findViewById<View>(R.id.loginButton).isEnabled)
    }

    @Test
    fun `login button is enabled when valid email and password entered`() {
        val activity = createActivity()
        activity.findViewById<EditText>(R.id.emailInput).setText("user@example.com")
        activity.findViewById<EditText>(R.id.passwordInput).setText("password123")
        assertTrue(activity.findViewById<View>(R.id.loginButton).isEnabled)
    }

    @Test
    fun `login button is disabled when only email entered`() {
        val activity = createActivity()
        activity.findViewById<EditText>(R.id.emailInput).setText("user@example.com")
        assertFalse(activity.findViewById<View>(R.id.loginButton).isEnabled)
    }

    @Test
    fun `login button is disabled when only password entered`() {
        val activity = createActivity()
        activity.findViewById<EditText>(R.id.passwordInput).setText("password123")
        assertFalse(activity.findViewById<View>(R.id.loginButton).isEnabled)
    }

    @Test
    fun `login button becomes disabled when valid form is cleared`() {
        val activity = createActivity()
        activity.findViewById<EditText>(R.id.emailInput).setText("user@example.com")
        activity.findViewById<EditText>(R.id.passwordInput).setText("password123")
        assertTrue(activity.findViewById<View>(R.id.loginButton).isEnabled)

        activity.findViewById<EditText>(R.id.emailInput).setText("")
        assertFalse(activity.findViewById<View>(R.id.loginButton).isEnabled)
    }

    // ==================== Error Clearing via TextWatcher ====================

    @Test
    fun `error text hidden when user types after showError`() {
        val activity = createActivity()
        activity.showError("Test error")

        val errorText = activity.findViewById<View>(R.id.errorText)
        assertEquals(View.VISIBLE, errorText.visibility)

        activity.findViewById<EditText>(R.id.emailInput).setText("a")
        assertEquals(View.INVISIBLE, errorText.visibility)
    }

    @Test
    fun `error text stays invisible when user types without prior error`() {
        val activity = createActivity()
        val errorText = activity.findViewById<View>(R.id.errorText)

        assertEquals(View.INVISIBLE, errorText.visibility)

        activity.findViewById<EditText>(R.id.emailInput).setText("a")
        assertEquals(View.INVISIBLE, errorText.visibility)
    }

    // ==================== showLoading ====================

    @Test
    fun `showLoading disables and hides login button`() {
        val activity = createActivity()
        activity.showLoading()

        val button = activity.findViewById<View>(R.id.loginButton)
        assertFalse(button.isEnabled)
        assertEquals(View.INVISIBLE, button.visibility)
    }

    @Test
    fun `showLoading shows progress bar`() {
        val activity = createActivity()
        activity.showLoading()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.progressBar).visibility)
    }

    @Test
    fun `showLoading hides error text`() {
        val activity = createActivity()
        activity.showError("Error")
        activity.showLoading()
        assertEquals(View.INVISIBLE, activity.findViewById<View>(R.id.errorText).visibility)
    }

    @Test
    fun `showLoading disables email and password inputs`() {
        val activity = createActivity()
        activity.showLoading()
        assertFalse(activity.findViewById<View>(R.id.emailInput).isEnabled)
        assertFalse(activity.findViewById<View>(R.id.passwordInput).isEnabled)
    }

    // ==================== showError ====================

    @Test
    fun `showError hides progress bar`() {
        val activity = createActivity()
        activity.showLoading()
        activity.showError("Error message")
        assertEquals(View.INVISIBLE, activity.findViewById<View>(R.id.progressBar).visibility)
    }

    @Test
    fun `showError shows login button`() {
        val activity = createActivity()
        activity.showLoading()
        activity.showError("Error message")
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.loginButton).visibility)
    }

    @Test
    fun `showError enables button when form is valid`() {
        val activity = createActivity()
        activity.findViewById<EditText>(R.id.emailInput).setText("user@example.com")
        activity.findViewById<EditText>(R.id.passwordInput).setText("password123")
        activity.showLoading()
        activity.showError("Error message")
        assertTrue(activity.findViewById<View>(R.id.loginButton).isEnabled)
    }

    @Test
    fun `showError disables button when form is invalid`() {
        val activity = createActivity()
        activity.findViewById<EditText>(R.id.emailInput).setText("invalid")
        activity.showError("Error message")
        assertFalse(activity.findViewById<View>(R.id.loginButton).isEnabled)
    }

    @Test
    fun `showError displays error message text`() {
        val activity = createActivity()
        activity.showError("Invalid credentials")

        val errorText = activity.findViewById<TextView>(R.id.errorText)
        assertEquals("Invalid credentials", errorText.text.toString())
        assertEquals(View.VISIBLE, errorText.visibility)
    }

    @Test
    fun `showError re-enables email and password inputs`() {
        val activity = createActivity()
        activity.showLoading()
        activity.showError("Error")
        assertTrue(activity.findViewById<View>(R.id.emailInput).isEnabled)
        assertTrue(activity.findViewById<View>(R.id.passwordInput).isEnabled)
    }

    // ==================== showSuccess ====================

    @Test
    fun `showSuccess starts MainActivity`() {
        val activity = createActivity()
        activity.showSuccess()

        val nextIntent = shadowOf(activity).nextStartedActivity
        assertNotNull(nextIntent)
        assertEquals(MainActivity::class.java.name, nextIntent.component?.className)
    }

    @Test
    fun `showSuccess sets clear task flags`() {
        val activity = createActivity()
        activity.showSuccess()

        val nextIntent = shadowOf(activity).nextStartedActivity
        val expectedFlags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        assertEquals(expectedFlags, nextIntent.flags and expectedFlags)
    }

    @Test
    fun `showSuccess finishes activity`() {
        val activity = createActivity()
        activity.showSuccess()
        assertTrue(activity.isFinishing)
    }

    // ==================== createIntent ====================

    @Test
    fun `createIntent returns intent targeting LoginActivity`() {
        val context = RuntimeEnvironment.getApplication()
        val intent = LoginActivity.createIntent(context)
        assertEquals(LoginActivity::class.java.name, intent.component?.className)
    }

    // ==================== Lifecycle ====================

    @Test
    fun `onDestroy cancels login job`() {
        val controller = Robolectric.buildActivity(LoginActivity::class.java)
            .create().resume()
        val activity = controller.get()

        val job = Job()
        activity.loginJob = job
        assertFalse(job.isCancelled)

        controller.pause().stop().destroy()
        assertTrue(job.isCancelled)
    }

    @Test
    fun `onDestroy is safe when loginJob is null`() {
        val controller = Robolectric.buildActivity(LoginActivity::class.java)
            .create().resume()
        val activity = controller.get()

        assertNull(activity.loginJob)

        // Should not throw
        controller.pause().stop().destroy()
    }

    // ==================== Helper ====================

    private fun createActivity(): LoginActivity {
        return Robolectric.buildActivity(LoginActivity::class.java)
            .create().resume().get()
    }
}
