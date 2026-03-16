package com.skeddy.ui

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import com.skeddy.accessibility.SkeddyAccessibilityService
import com.skeddy.data.SkeddyPreferences
import com.skeddy.network.DeviceTokenManager
import com.skeddy.service.MonitoringForegroundService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper

/**
 * Tests for state routing logic in [MainActivity.onResume].
 *
 * Verifies that MainActivity correctly routes to the appropriate screen
 * based on [AppState] determined by [AppStateDeterminer]:
 * - NOT_LOGGED_IN   -> LoginActivity
 * - NOT_CONFIGURED -> SetupRequiredActivity
 * - LOGGED_IN       -> stays on MainActivity
 * - FORCE_UPDATE -> ForceUpdateActivity
 *
 * Sets [com.google.android.material.R.style.Theme_Material3_DayNight]
 * before onCreate because activity_main.xml references Material3 attributes
 * (colorOutlineVariant) not available in the app's MaterialComponents
 * parent theme under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityStateRoutingTest {

    private lateinit var deviceTokenManager: DeviceTokenManager
    private lateinit var preferences: SkeddyPreferences

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        deviceTokenManager = DeviceTokenManager(context)
        preferences = SkeddyPreferences(context)
        deviceTokenManager.clearDeviceToken()
        preferences.forceUpdateActive = false
        preferences.forceUpdateUrl = null
        clearAccessibility()
    }

    @After
    fun tearDown() {
        deviceTokenManager.clearDeviceToken()
        preferences.forceUpdateActive = false
        preferences.forceUpdateUrl = null
        clearAccessibility()
    }

    // ==================== NOT_LOGGED_IN State ====================

    @Test
    fun `NOT_LOGGED_IN navigates to LoginActivity`() {
        val activity = createActivity()

        val nextIntent = shadowOf(activity).nextStartedActivity
        assertNotNull("Should navigate to LoginActivity", nextIntent)
        assertEquals(LoginActivity::class.java.name, nextIntent.component?.className)
    }

    @Test
    fun `NOT_LOGGED_IN finishes MainActivity`() {
        val activity = createActivity()

        assertTrue("MainActivity should finish when NOT_LOGGED_IN", activity.isFinishing)
    }

    @Test
    fun `NOT_LOGGED_IN sets NEW_TASK and CLEAR_TASK flags`() {
        val activity = createActivity()

        val nextIntent = shadowOf(activity).nextStartedActivity
        val expectedFlags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        assertEquals(
            "LoginActivity intent should have NEW_TASK | CLEAR_TASK flags",
            expectedFlags,
            nextIntent.flags and expectedFlags
        )
    }

    // ==================== NOT_CONFIGURED State ====================

    @Test
    fun `NOT_CONFIGURED navigates to SetupRequiredActivity`() {
        deviceTokenManager.saveDeviceToken("test-token")
        // Accessibility NOT enabled (default after clearAccessibility)

        val activity = createActivity()

        val nextIntent = shadowOf(activity).nextStartedActivity
        assertNotNull("Should navigate to SetupRequiredActivity", nextIntent)
        assertEquals(SetupRequiredActivity::class.java.name, nextIntent.component?.className)
    }

    @Test
    fun `NOT_CONFIGURED finishes MainActivity`() {
        deviceTokenManager.saveDeviceToken("test-token")

        val activity = createActivity()

        assertTrue("MainActivity should finish when NOT_CONFIGURED", activity.isFinishing)
    }

    // ==================== LOGGED_IN State ====================

    @Test
    fun `LOGGED_IN stays on MainActivity`() {
        deviceTokenManager.saveDeviceToken("test-token")
        enableAccessibility()

        val activity = createActivity()

        val nextIntent = shadowOf(activity).nextStartedActivity
        // In LOGGED_IN state, no routing should occur.
        // nextStartedActivity may be non-null if bindService or other code starts an activity,
        // so we check that it's NOT LoginActivity, SetupRequiredActivity, or ForceUpdateActivity.
        if (nextIntent != null) {
            val targetClass = nextIntent.component?.className
            assertFalse(
                "Should NOT navigate to LoginActivity when LOGGED_IN",
                targetClass == LoginActivity::class.java.name
            )
            assertFalse(
                "Should NOT navigate to SetupRequiredActivity when LOGGED_IN",
                targetClass == SetupRequiredActivity::class.java.name
            )
            assertFalse(
                "Should NOT navigate to ForceUpdateActivity when LOGGED_IN",
                targetClass == ForceUpdateActivity::class.java.name
            )
        }
        assertFalse("MainActivity should NOT finish when LOGGED_IN", activity.isFinishing)
    }

    // ==================== FORCE_UPDATE State ====================

    @Test
    fun `FORCE_UPDATE navigates to ForceUpdateActivity`() {
        deviceTokenManager.saveDeviceToken("test-token")
        enableAccessibility()
        preferences.forceUpdateActive = true
        preferences.forceUpdateUrl = "https://play.google.com/store/apps/details?id=com.skeddy"

        val activity = createActivity()

        val nextIntent = shadowOf(activity).nextStartedActivity
        assertNotNull("Should navigate to ForceUpdateActivity", nextIntent)
        assertEquals(ForceUpdateActivity::class.java.name, nextIntent.component?.className)
    }

    @Test
    fun `FORCE_UPDATE passes update URL in intent`() {
        deviceTokenManager.saveDeviceToken("test-token")
        enableAccessibility()
        preferences.forceUpdateActive = true
        val expectedUrl = "https://play.google.com/store/apps/details?id=com.skeddy"
        preferences.forceUpdateUrl = expectedUrl

        val activity = createActivity()

        val nextIntent = shadowOf(activity).nextStartedActivity
        assertEquals(
            expectedUrl,
            nextIntent.getStringExtra(ForceUpdateActivity.EXTRA_UPDATE_URL)
        )
    }

    @Test
    fun `FORCE_UPDATE does NOT finish MainActivity`() {
        deviceTokenManager.saveDeviceToken("test-token")
        enableAccessibility()
        preferences.forceUpdateActive = true

        val activity = createActivity()

        assertFalse(
            "MainActivity should NOT finish for FORCE_UPDATE (back stack preserved)",
            activity.isFinishing
        )
    }

    // ==================== onResume Re-Evaluation ====================

    @Test
    fun `onResume re-evaluates state when token is cleared`() {
        // Start as LOGGED_IN
        deviceTokenManager.saveDeviceToken("test-token")
        enableAccessibility()

        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.get().setTheme(com.google.android.material.R.style.Theme_Material3_DayNight)
        controller.create().resume()
        val activity = controller.get()

        assertFalse("Should NOT be finishing initially", activity.isFinishing)

        // Clear token -> should become NOT_LOGGED_IN on next resume
        deviceTokenManager.clearDeviceToken()
        controller.pause().resume()

        val nextIntent = shadowOf(activity).nextStartedActivity
        assertNotNull("Should navigate to LoginActivity after token cleared", nextIntent)
        assertEquals(LoginActivity::class.java.name, nextIntent.component?.className)
    }

    // ==================== ACTION_UNPAIRED Broadcast (Task 24.3) ====================

    @Test
    fun `ACTION_UNPAIRED broadcast navigates to LoginActivity`() {
        // Start as LOGGED_IN
        deviceTokenManager.saveDeviceToken("test-token")
        enableAccessibility()

        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.get().setTheme(com.google.android.material.R.style.Theme_Material3_DayNight)
        controller.create().resume()
        val activity = controller.get()

        assertFalse("Should NOT be finishing initially", activity.isFinishing)

        // Drain any activities started during creation/onResume
        val shadow = shadowOf(activity)
        while (shadow.nextStartedActivity != null) { /* drain */ }

        // Send ACTION_UNPAIRED broadcast (simulates service receiving 401/403)
        val unpairedIntent = Intent(MonitoringForegroundService.ACTION_UNPAIRED).apply {
            setPackage(activity.packageName)
        }
        activity.sendBroadcast(unpairedIntent)
        ShadowLooper.idleMainLooper()

        val nextIntent = shadow.nextStartedActivity
        assertNotNull("Should navigate to LoginActivity after UNPAIRED broadcast", nextIntent)
        assertEquals(LoginActivity::class.java.name, nextIntent.component?.className)
    }

    @Test
    fun `ACTION_UNPAIRED broadcast finishes MainActivity`() {
        deviceTokenManager.saveDeviceToken("test-token")
        enableAccessibility()

        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.get().setTheme(com.google.android.material.R.style.Theme_Material3_DayNight)
        controller.create().resume()
        val activity = controller.get()

        assertFalse("Should NOT be finishing initially", activity.isFinishing)

        val unpairedIntent = Intent(MonitoringForegroundService.ACTION_UNPAIRED).apply {
            setPackage(activity.packageName)
        }
        activity.sendBroadcast(unpairedIntent)
        ShadowLooper.idleMainLooper()

        assertTrue("MainActivity should finish after UNPAIRED broadcast", activity.isFinishing)
    }

    @Test
    fun `ACTION_UNPAIRED broadcast sets NEW_TASK and CLEAR_TASK flags`() {
        deviceTokenManager.saveDeviceToken("test-token")
        enableAccessibility()

        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.get().setTheme(com.google.android.material.R.style.Theme_Material3_DayNight)
        controller.create().resume()
        val activity = controller.get()

        // Drain initial intents
        val shadow = shadowOf(activity)
        while (shadow.nextStartedActivity != null) { /* drain */ }

        val unpairedIntent = Intent(MonitoringForegroundService.ACTION_UNPAIRED).apply {
            setPackage(activity.packageName)
        }
        activity.sendBroadcast(unpairedIntent)
        ShadowLooper.idleMainLooper()

        val nextIntent = shadow.nextStartedActivity
        assertNotNull("LoginActivity intent should be started", nextIntent)
        val expectedFlags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        assertEquals(
            "LoginActivity intent should have NEW_TASK | CLEAR_TASK flags",
            expectedFlags,
            nextIntent.flags and expectedFlags
        )
    }

    // ==================== Helpers ====================

    /**
     * Creates a [MainActivity] with Material3 theme applied before inflation.
     * This is needed because activity_main.xml uses ?attr/colorOutlineVariant
     * which is not defined in Theme.MaterialComponents under Robolectric.
     */
    private fun createActivity(): MainActivity {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.get().setTheme(com.google.android.material.R.style.Theme_Material3_DayNight)
        return controller.create().resume().get()
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

    private fun clearAccessibility() {
        Settings.Secure.putString(
            RuntimeEnvironment.getApplication().contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ""
        )
    }
}
