package com.skeddy.ui

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.skeddy.R
import com.skeddy.accessibility.SkeddyAccessibilityService
import com.google.android.material.button.MaterialButton
import org.junit.After
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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class SetupRequiredActivityTest {

    @After
    fun tearDown() {
        // Clear accessibility settings to avoid leaking state between tests
        Settings.Secure.putString(
            RuntimeEnvironment.getApplication().contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ""
        )
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
            activity.getString(R.string.setup_required_title),
            title.text.toString()
        )
    }

    @Test
    fun `message text displays correct string`() {
        val activity = createActivity()
        val message = activity.findViewById<TextView>(R.id.messageText)
        assertNotNull(message)
        assertEquals(
            activity.getString(R.string.setup_required_message),
            message.text.toString()
        )
    }

    @Test
    fun `open settings button displays correct text`() {
        val activity = createActivity()
        val button = activity.findViewById<MaterialButton>(R.id.openSettingsButton)
        assertNotNull(button)
        assertEquals(
            activity.getString(R.string.setup_required_button),
            button.text.toString()
        )
    }

    @Test
    fun `open settings button is enabled`() {
        val activity = createActivity()
        val button = activity.findViewById<MaterialButton>(R.id.openSettingsButton)
        assertTrue(button.isEnabled)
    }

    // ==================== Button Click ====================

    @Test
    fun `open settings button launches accessibility settings`() {
        val activity = createActivity()
        val button = activity.findViewById<MaterialButton>(R.id.openSettingsButton)

        button.performClick()

        val nextIntent = shadowOf(activity).nextStartedActivity
        assertNotNull(nextIntent)
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, nextIntent.action)
    }

    // ==================== Localization (English) ====================

    @Test
    fun `english title string is correct`() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(
            "Accessibility Service Required",
            context.getString(R.string.setup_required_title)
        )
    }

    @Test
    fun `english message string is correct`() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(
            "Enable Skeddy Accessibility Service in Settings to start searching.",
            context.getString(R.string.setup_required_message)
        )
    }

    @Test
    fun `english button string is correct`() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(
            "Open Settings",
            context.getString(R.string.setup_required_button)
        )
    }

    // ==================== Localization (Spanish) ====================

    @Test
    @Config(qualifiers = "es")
    fun `spanish title string is correct`() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(
            "Se Requiere Servicio de Accesibilidad",
            context.getString(R.string.setup_required_title)
        )
    }

    @Test
    @Config(qualifiers = "es")
    fun `spanish message string is correct`() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(
            "Active el Servicio de Accesibilidad de Skeddy en Configuración para comenzar a buscar.",
            context.getString(R.string.setup_required_message)
        )
    }

    @Test
    @Config(qualifiers = "es")
    fun `spanish button string is correct`() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(
            "Abrir Configuración",
            context.getString(R.string.setup_required_button)
        )
    }

    // ==================== onResume Auto-Transition ====================

    @Test
    fun `onResume stays on screen when accessibility is disabled`() {
        val activity = createActivity()

        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(activity.isFinishing)
    }

    @Test
    fun `onResume redirects to MainActivity when accessibility is enabled`() {
        enableAccessibility()
        val activity = createActivity()

        val nextIntent = shadowOf(activity).nextStartedActivity
        assertNotNull(nextIntent)
        assertEquals(MainActivity::class.java.name, nextIntent.component?.className)
    }

    @Test
    fun `onResume finishes activity when accessibility is enabled`() {
        enableAccessibility()
        val activity = createActivity()

        assertTrue(activity.isFinishing)
    }

    @Test
    fun `onResume redirect intent has CLEAR_TOP and SINGLE_TOP flags`() {
        enableAccessibility()
        val activity = createActivity()

        val nextIntent = shadowOf(activity).nextStartedActivity
        val expectedFlags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        assertEquals(expectedFlags, nextIntent.flags and expectedFlags)
    }

    @Test
    fun `onResume re-checks on each resume`() {
        // First resume: accessibility disabled → stays
        val controller = Robolectric.buildActivity(SetupRequiredActivity::class.java)
            .create().resume()
        val activity = controller.get()

        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(activity.isFinishing)

        // Enable accessibility, then pause and resume
        enableAccessibility()
        controller.pause().resume()

        val nextIntent = shadowOf(activity).nextStartedActivity
        assertNotNull(nextIntent)
        assertEquals(MainActivity::class.java.name, nextIntent.component?.className)
    }

    // ==================== NOT_CONFIGURED State: Service Not Started ====================

    @Test
    fun `no MonitoringForegroundService started in NOT_CONFIGURED state`() {
        createActivity()

        val shadowApp = shadowOf(RuntimeEnvironment.getApplication())
        var serviceIntent = shadowApp.nextStartedService
        while (serviceIntent != null) {
            assertFalse(
                "MonitoringForegroundService must not be started in NOT_CONFIGURED state",
                serviceIntent.component?.className?.contains("MonitoringForegroundService") == true
            )
            serviceIntent = shadowApp.nextStartedService
        }
    }

    // ==================== createIntent ====================

    @Test
    fun `createIntent returns intent targeting SetupRequiredActivity`() {
        val context = RuntimeEnvironment.getApplication()
        val intent = SetupRequiredActivity.createIntent(context)
        assertEquals(SetupRequiredActivity::class.java.name, intent.component?.className)
    }

    // ==================== Helpers ====================

    private fun createActivity(): SetupRequiredActivity {
        return Robolectric.buildActivity(SetupRequiredActivity::class.java)
            .create().resume().get()
    }

    private fun enableAccessibility() {
        val componentName = ComponentName(
            RuntimeEnvironment.getApplication(),
            SkeddyAccessibilityService::class.java
        ).flattenToString()

        Settings.Secure.putString(
            RuntimeEnvironment.getApplication().contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            componentName
        )
    }
}
