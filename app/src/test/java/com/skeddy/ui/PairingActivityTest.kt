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
class PairingActivityTest {

    // ==================== Code Validation (Pure Logic) ====================

    @Test
    fun `isCodeValid returns true for exactly 6 digits`() {
        assertTrue(PairingActivity.isCodeValid("123456"))
    }

    @Test
    fun `isCodeValid returns true for all zeros`() {
        assertTrue(PairingActivity.isCodeValid("000000"))
    }

    @Test
    fun `isCodeValid returns true for all nines`() {
        assertTrue(PairingActivity.isCodeValid("999999"))
    }

    @Test
    fun `isCodeValid returns false for 5 digits`() {
        assertFalse(PairingActivity.isCodeValid("12345"))
    }

    @Test
    fun `isCodeValid returns false for 7 digits`() {
        assertFalse(PairingActivity.isCodeValid("1234567"))
    }

    @Test
    fun `isCodeValid returns false for empty string`() {
        assertFalse(PairingActivity.isCodeValid(""))
    }

    @Test
    fun `isCodeValid returns false for null`() {
        assertFalse(PairingActivity.isCodeValid(null))
    }

    @Test
    fun `isCodeValid returns false when contains letters`() {
        assertFalse(PairingActivity.isCodeValid("12345a"))
    }

    @Test
    fun `isCodeValid returns false when contains spaces`() {
        assertFalse(PairingActivity.isCodeValid("123 56"))
    }

    @Test
    fun `isCodeValid returns false when contains special characters`() {
        assertFalse(PairingActivity.isCodeValid("12-456"))
    }

    // ==================== Button State via TextWatcher ====================

    @Test
    fun `pair button is disabled on initial create`() {
        val activity = createActivity()
        assertFalse(activity.findViewById<View>(R.id.pairButton).isEnabled)
    }

    @Test
    fun `pair button is enabled when 6 digits entered`() {
        val activity = createActivity()
        activity.findViewById<EditText>(R.id.codeInput).setText("123456")
        assertTrue(activity.findViewById<View>(R.id.pairButton).isEnabled)
    }

    @Test
    fun `pair button is disabled when less than 6 digits`() {
        val activity = createActivity()
        activity.findViewById<EditText>(R.id.codeInput).setText("12345")
        assertFalse(activity.findViewById<View>(R.id.pairButton).isEnabled)
    }

    @Test
    fun `pair button becomes disabled when valid code is cleared`() {
        val activity = createActivity()
        val input = activity.findViewById<EditText>(R.id.codeInput)

        input.setText("123456")
        assertTrue(activity.findViewById<View>(R.id.pairButton).isEnabled)

        input.setText("")
        assertFalse(activity.findViewById<View>(R.id.pairButton).isEnabled)
    }

    // ==================== Error Clearing via TextWatcher ====================

    @Test
    fun `error text hidden when user types after showError`() {
        val activity = createActivity()
        activity.showError("Test error")

        val errorText = activity.findViewById<View>(R.id.errorText)
        assertEquals(View.VISIBLE, errorText.visibility)

        activity.findViewById<EditText>(R.id.codeInput).setText("1")
        assertEquals(View.INVISIBLE, errorText.visibility)
    }

    @Test
    fun `error text stays invisible when user types without prior error`() {
        val activity = createActivity()
        val errorText = activity.findViewById<View>(R.id.errorText)

        assertEquals(View.INVISIBLE, errorText.visibility)

        activity.findViewById<EditText>(R.id.codeInput).setText("1")
        assertEquals(View.INVISIBLE, errorText.visibility)
    }

    // ==================== showLoading ====================

    @Test
    fun `showLoading disables and hides pair button`() {
        val activity = createActivity()
        activity.showLoading()

        val button = activity.findViewById<View>(R.id.pairButton)
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
    fun `showLoading disables code input`() {
        val activity = createActivity()
        activity.showLoading()
        assertFalse(activity.findViewById<View>(R.id.codeInput).isEnabled)
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
    fun `showError shows pair button`() {
        val activity = createActivity()
        activity.showLoading()
        activity.showError("Error message")
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pairButton).visibility)
    }

    @Test
    fun `showError enables button when code is valid`() {
        val activity = createActivity()
        activity.findViewById<EditText>(R.id.codeInput).setText("123456")
        activity.showLoading()
        activity.showError("Error message")
        assertTrue(activity.findViewById<View>(R.id.pairButton).isEnabled)
    }

    @Test
    fun `showError disables button when code is invalid`() {
        val activity = createActivity()
        activity.findViewById<EditText>(R.id.codeInput).setText("123")
        activity.showError("Error message")
        assertFalse(activity.findViewById<View>(R.id.pairButton).isEnabled)
    }

    @Test
    fun `showError displays error message text`() {
        val activity = createActivity()
        activity.showError("Invalid code")

        val errorText = activity.findViewById<TextView>(R.id.errorText)
        assertEquals("Invalid code", errorText.text.toString())
        assertEquals(View.VISIBLE, errorText.visibility)
    }

    @Test
    fun `showError re-enables code input`() {
        val activity = createActivity()
        activity.showLoading()
        activity.showError("Error")
        assertTrue(activity.findViewById<View>(R.id.codeInput).isEnabled)
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
    fun `createIntent returns intent targeting PairingActivity`() {
        val context = RuntimeEnvironment.getApplication()
        val intent = PairingActivity.createIntent(context)
        assertEquals(PairingActivity::class.java.name, intent.component?.className)
    }

    // ==================== Lifecycle ====================

    @Test
    fun `onDestroy cancels pairing job`() {
        val controller = Robolectric.buildActivity(PairingActivity::class.java)
            .create().resume()
        val activity = controller.get()

        val job = Job()
        activity.pairingJob = job
        assertFalse(job.isCancelled)

        controller.pause().stop().destroy()
        assertTrue(job.isCancelled)
    }

    @Test
    fun `onDestroy is safe when pairingJob is null`() {
        val controller = Robolectric.buildActivity(PairingActivity::class.java)
            .create().resume()
        val activity = controller.get()

        assertNull(activity.pairingJob)

        // Should not throw
        controller.pause().stop().destroy()
    }

    // ==================== Helper ====================

    private fun createActivity(): PairingActivity {
        return Robolectric.buildActivity(PairingActivity::class.java)
            .create().resume().get()
    }
}
