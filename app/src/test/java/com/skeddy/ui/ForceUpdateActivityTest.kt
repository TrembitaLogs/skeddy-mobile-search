package com.skeddy.ui

import android.content.Intent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.skeddy.R
import com.skeddy.data.SkeddyPreferences
import com.skeddy.service.MonitoringForegroundService
import com.google.android.material.button.MaterialButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class ForceUpdateActivityTest {

    @Before
    fun setUp() {
        // ForceUpdateActivity is only launched when force update is active.
        // Set the precondition so onResume() does not immediately finish().
        SkeddyPreferences(RuntimeEnvironment.getApplication()).forceUpdateActive = true
    }

    // ==================== UI Elements Display ====================

    @Test
    fun `warning icon is visible`() {
        val activity = createActivity()
        val icon = activity.findViewById<ImageView>(R.id.warningIcon)
        assertNotNull(icon)
        assertEquals(View.VISIBLE, icon.visibility)
    }

    @Test
    fun `title text displays correct string`() {
        val activity = createActivity()
        val title = activity.findViewById<TextView>(R.id.titleText)
        assertNotNull(title)
        assertEquals(
            activity.getString(R.string.force_update_title),
            title.text.toString()
        )
    }

    @Test
    fun `message text displays correct string`() {
        val activity = createActivity()
        val message = activity.findViewById<TextView>(R.id.messageText)
        assertNotNull(message)
        assertEquals(
            activity.getString(R.string.force_update_message),
            message.text.toString()
        )
    }

    @Test
    fun `update button displays correct text`() {
        val activity = createActivity()
        val button = activity.findViewById<MaterialButton>(R.id.updateButton)
        assertNotNull(button)
        assertEquals(
            activity.getString(R.string.force_update_button),
            button.text.toString()
        )
    }

    @Test
    fun `update button is enabled`() {
        val activity = createActivity()
        val button = activity.findViewById<MaterialButton>(R.id.updateButton)
        assertTrue(button.isEnabled)
    }

    // ==================== Button Click — ACTION_VIEW Intent ====================

    @Test
    fun `update button launches ACTION_VIEW intent with correct url`() {
        val url = "https://play.google.com/store/apps/details?id=com.skeddy"
        val activity = createActivityWithUrl(url)
        val button = activity.findViewById<MaterialButton>(R.id.updateButton)

        button.performClick()

        val nextIntent = shadowOf(activity).nextStartedActivity
        assertNotNull(nextIntent)
        assertEquals(Intent.ACTION_VIEW, nextIntent.action)
        assertEquals(url, nextIntent.data.toString())
    }

    @Test
    fun `update button does nothing when url is null`() {
        val activity = createActivityWithUrl(null)
        val button = activity.findViewById<MaterialButton>(R.id.updateButton)

        button.performClick()

        val nextIntent = shadowOf(activity).nextStartedActivity
        assertNull(nextIntent)
    }

    @Test
    fun `update button does nothing when url is empty`() {
        val activity = createActivityWithUrl("")
        val button = activity.findViewById<MaterialButton>(R.id.updateButton)

        button.performClick()

        val nextIntent = shadowOf(activity).nextStartedActivity
        assertNull(nextIntent)
    }

    // ==================== Back Navigation Blocked ====================

    @Test
    fun `back press does not finish activity`() {
        val activity = createActivity()

        activity.onBackPressedDispatcher.onBackPressed()

        assertFalse(activity.isFinishing)
    }

    // ==================== Localization (English) ====================

    @Test
    fun `english title string is correct`() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(
            "Update Required",
            context.getString(R.string.force_update_title)
        )
    }

    @Test
    fun `english message string is correct`() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(
            "Your app version is outdated. Please update to continue searching.",
            context.getString(R.string.force_update_message)
        )
    }

    @Test
    fun `english button string is correct`() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(
            "Update",
            context.getString(R.string.force_update_button)
        )
    }

    // ==================== Localization (Spanish) ====================

    @Test
    @Config(qualifiers = "es")
    fun `spanish title string is correct`() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(
            "Actualización Requerida",
            context.getString(R.string.force_update_title)
        )
    }

    @Test
    @Config(qualifiers = "es")
    fun `spanish message string is correct`() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(
            "Su versión de la aplicación está desactualizada. Por favor actualice para continuar buscando.",
            context.getString(R.string.force_update_message)
        )
    }

    @Test
    @Config(qualifiers = "es")
    fun `spanish button string is correct`() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(
            "Actualizar",
            context.getString(R.string.force_update_button)
        )
    }

    // ==================== Auto-Close on Force Update Cleared ====================

    @Test
    fun `activity finishes when ACTION_FORCE_UPDATE_CLEARED broadcast received`() {
        val activity = createActivity()
        assertFalse("Activity should not be finishing initially", activity.isFinishing)

        // Send FORCE_UPDATE_CLEARED broadcast through activity's context
        val clearedIntent = Intent(MonitoringForegroundService.ACTION_FORCE_UPDATE_CLEARED)
        activity.sendBroadcast(clearedIntent)
        ShadowLooper.idleMainLooper()

        assertTrue("Activity should be finishing after FORCE_UPDATE_CLEARED", activity.isFinishing)
    }

    // ==================== Preference-based Auto-Close (DEF-22) ====================

    @Test
    fun `activity finishes on resume when forceUpdateActive is false`() {
        // Create activity with force update active (normal state)
        val intent = ForceUpdateActivity.createIntent(
            RuntimeEnvironment.getApplication(),
            "https://example.com/update"
        )
        val controller = Robolectric.buildActivity(ForceUpdateActivity::class.java, intent)
            .create().resume()
        val activity = controller.get()
        assertFalse("Activity should not be finishing initially", activity.isFinishing)

        // Simulate: user taps Update → goes to Chrome → server clears force update
        controller.pause()
        SkeddyPreferences(RuntimeEnvironment.getApplication()).forceUpdateActive = false

        // User returns from Chrome → onResume triggers checkForceUpdateStatus
        controller.resume()
        assertTrue("Activity should finish when forceUpdateActive is false", activity.isFinishing)
    }

    @Test
    fun `activity stays open on resume when forceUpdateActive is true`() {
        val intent = ForceUpdateActivity.createIntent(
            RuntimeEnvironment.getApplication(),
            "https://example.com/update"
        )
        val controller = Robolectric.buildActivity(ForceUpdateActivity::class.java, intent)
            .create().resume()
        val activity = controller.get()
        assertFalse("Activity should not be finishing initially", activity.isFinishing)

        // Simulate: user goes to Chrome and returns, but force update is still active
        controller.pause()
        controller.resume()
        assertFalse("Activity should stay open when forceUpdateActive is true", activity.isFinishing)
    }

    // ==================== createIntent ====================

    @Test
    fun `createIntent returns intent targeting ForceUpdateActivity`() {
        val context = RuntimeEnvironment.getApplication()
        val intent = ForceUpdateActivity.createIntent(context, "https://example.com")
        assertEquals(ForceUpdateActivity::class.java.name, intent.component?.className)
    }

    @Test
    fun `createIntent includes update url as extra`() {
        val context = RuntimeEnvironment.getApplication()
        val url = "https://play.google.com/store/apps/details?id=com.skeddy"
        val intent = ForceUpdateActivity.createIntent(context, url)
        assertEquals(url, intent.getStringExtra(ForceUpdateActivity.EXTRA_UPDATE_URL))
    }

    @Test
    fun `createIntent handles null url`() {
        val context = RuntimeEnvironment.getApplication()
        val intent = ForceUpdateActivity.createIntent(context, null)
        assertNull(intent.getStringExtra(ForceUpdateActivity.EXTRA_UPDATE_URL))
    }

    // ==================== Helpers ====================

    private fun createActivity(): ForceUpdateActivity {
        return createActivityWithUrl("https://example.com/update")
    }

    private fun createActivityWithUrl(url: String?): ForceUpdateActivity {
        val intent = ForceUpdateActivity.createIntent(
            RuntimeEnvironment.getApplication(),
            url
        )
        return Robolectric.buildActivity(ForceUpdateActivity::class.java, intent)
            .create().resume().get()
    }
}
