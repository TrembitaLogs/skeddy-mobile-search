package com.skeddy.notification

import android.Manifest
import android.app.Activity
import android.app.Application
import android.os.Build
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Unit tests for NotificationPermissionHelper.
 *
 * Tests permission checking and rationale logic for POST_NOTIFICATIONS
 * on different Android API levels.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationPermissionHelperTest {

    private lateinit var application: Application
    private lateinit var helper: NotificationPermissionHelper

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        helper = NotificationPermissionHelper(application)
    }

    // ==================== hasNotificationPermission Tests ====================

    @Test
    @Config(sdk = [Build.VERSION_CODES.S]) // API 31 - Android 12
    fun `hasNotificationPermission returns true on Android 12 without explicit permission`() {
        // On Android 12 (API 31), POST_NOTIFICATIONS is not required
        assertTrue(
            "Should return true on Android 12 without permission",
            helper.hasNotificationPermission()
        )
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.S_V2]) // API 32 - Android 12L
    fun `hasNotificationPermission returns true on Android 12L without explicit permission`() {
        // On Android 12L (API 32), POST_NOTIFICATIONS is not required
        assertTrue(
            "Should return true on Android 12L without permission",
            helper.hasNotificationPermission()
        )
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU]) // API 33 - Android 13
    fun `hasNotificationPermission returns false on Android 13 when permission not granted`() {
        assertFalse(
            "Should return false on Android 13 when permission not granted",
            helper.hasNotificationPermission()
        )
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU]) // API 33 - Android 13
    fun `hasNotificationPermission returns true on Android 13 when permission granted`() {
        val shadowApp = Shadows.shadowOf(application)
        shadowApp.grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertTrue(
            "Should return true on Android 13 when permission granted",
            helper.hasNotificationPermission()
        )
    }

    @Test
    @Config(sdk = [34]) // API 34 - Android 14
    fun `hasNotificationPermission returns false on Android 14 when permission not granted`() {
        assertFalse(
            "Should return false on Android 14 when permission not granted",
            helper.hasNotificationPermission()
        )
    }

    @Test
    @Config(sdk = [34]) // API 34 - Android 14
    fun `hasNotificationPermission returns true on Android 14 when permission granted`() {
        val shadowApp = Shadows.shadowOf(application)
        shadowApp.grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertTrue(
            "Should return true on Android 14 when permission granted",
            helper.hasNotificationPermission()
        )
    }

    // ==================== shouldRequestPermission Tests ====================

    @Test
    @Config(sdk = [Build.VERSION_CODES.S]) // API 31 - Android 12
    fun `shouldRequestPermission returns false on Android 12`() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()

        assertFalse(
            "Should not request permission on Android 12",
            helper.shouldRequestPermission(activity)
        )
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU]) // API 33 - Android 13
    fun `shouldRequestPermission returns true on Android 13 when permission not granted`() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()

        assertTrue(
            "Should request permission on Android 13 when not granted",
            helper.shouldRequestPermission(activity)
        )
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU]) // API 33 - Android 13
    fun `shouldRequestPermission returns false on Android 13 when permission already granted`() {
        val shadowApp = Shadows.shadowOf(application)
        shadowApp.grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val activity = Robolectric.buildActivity(Activity::class.java).create().get()

        assertFalse(
            "Should not request permission on Android 13 when already granted",
            helper.shouldRequestPermission(activity)
        )
    }

    // ==================== shouldShowRationale Tests ====================

    @Test
    @Config(sdk = [Build.VERSION_CODES.S]) // API 31 - Android 12
    fun `shouldShowRationale returns false on Android 12`() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()

        assertFalse(
            "Should not show rationale on Android 12",
            helper.shouldShowRationale(activity)
        )
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU]) // API 33 - Android 13
    fun `shouldShowRationale returns false when permission never requested`() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()

        // When permission was never requested, shouldShowRationale returns false
        assertFalse(
            "Should not show rationale when permission never requested",
            helper.shouldShowRationale(activity)
        )
    }
}
