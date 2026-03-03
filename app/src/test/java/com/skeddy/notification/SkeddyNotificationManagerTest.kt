package com.skeddy.notification

import android.app.NotificationManager
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNotificationManager
import com.skeddy.R
import java.util.Locale

/**
 * Unit tests for SkeddyNotificationManager.
 *
 * Verifies:
 * - Monitoring notification channel is created correctly
 * - Monitoring notification content uses string resources (no hardcoded strings)
 * - Notification text changes based on isSearching and intervalSeconds
 * - Localization works for es-ES locale
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SkeddyNotificationManagerTest {

    private lateinit var context: Context
    private lateinit var notificationManager: SkeddyNotificationManager
    private lateinit var shadowNotificationManager: ShadowNotificationManager
    private lateinit var systemNotificationManager: NotificationManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()

        systemNotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowNotificationManager = Shadows.shadowOf(systemNotificationManager)
        notificationManager = SkeddyNotificationManager(context)
    }

    // ==================== Channel Creation Tests ====================

    @Test
    fun `createChannels creates monitoring channel`() {
        notificationManager.createChannels()

        val channel = systemNotificationManager.getNotificationChannel(
            SkeddyNotificationManager.CHANNEL_MONITORING
        )

        assertNotNull("Monitoring channel should be created", channel)
        assertEquals("Skeddy Monitoring", channel?.name)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel?.importance)
    }

    @Test
    fun `createChannels does not create rides channel`() {
        notificationManager.createChannels()

        val channel = systemNotificationManager.getNotificationChannel("skeddy_rides")
        assertNull("Rides channel should not be created", channel)
    }

    // ==================== Monitoring Notification Tests ====================

    @Test
    fun `getMonitoringNotification returns notification with correct title from string resources`() {
        notificationManager.createChannels()

        val notification = notificationManager.getMonitoringNotification(isSearching = true, intervalSeconds = 30)
        val title = notification.extras.getString("android.title") ?: ""
        val expectedTitle = context.getString(R.string.notification_title)

        assertEquals(expectedTitle, title)
    }

    @Test
    fun `getMonitoringNotification shows searching text with interval when searching`() {
        notificationManager.createChannels()

        val notification = notificationManager.getMonitoringNotification(isSearching = true, intervalSeconds = 30)
        val content = notification.extras.getString("android.text") ?: ""
        val expectedContent = context.getString(R.string.notification_searching, 30)

        assertEquals(expectedContent, content)
    }

    @Test
    fun `getMonitoringNotification shows stopped text when not searching`() {
        notificationManager.createChannels()

        val notification = notificationManager.getMonitoringNotification(isSearching = false)
        val content = notification.extras.getString("android.text") ?: ""
        val expectedContent = context.getString(R.string.notification_stopped)

        assertEquals(expectedContent, content)
    }

    @Test
    fun `getMonitoringNotification shows stopped text when searching but intervalSeconds is null`() {
        notificationManager.createChannels()

        val notification = notificationManager.getMonitoringNotification(isSearching = true, intervalSeconds = null)
        val content = notification.extras.getString("android.text") ?: ""
        val expectedContent = context.getString(R.string.notification_stopped)

        assertEquals(expectedContent, content)
    }

    @Test
    fun `getMonitoringNotification uses monitoring channel`() {
        notificationManager.createChannels()

        val notification = notificationManager.getMonitoringNotification(isSearching = true, intervalSeconds = 30)

        assertEquals(
            "Notification should use monitoring channel",
            SkeddyNotificationManager.CHANNEL_MONITORING,
            notification.channelId
        )
    }

    @Test
    fun `getMonitoringNotification is ongoing`() {
        notificationManager.createChannels()

        val notification = notificationManager.getMonitoringNotification(isSearching = false)

        assertTrue(
            "Monitoring notification should be ongoing",
            (notification.flags and android.app.Notification.FLAG_ONGOING_EVENT) != 0
        )
    }

    @Test
    fun `getMonitoringNotification has content intent`() {
        notificationManager.createChannels()

        val notification = notificationManager.getMonitoringNotification(isSearching = false)

        assertNotNull("Notification should have content intent", notification.contentIntent)
    }

    // ==================== Localization Tests ====================

    @Test
    @Config(qualifiers = "es")
    fun `getMonitoringNotification uses Spanish strings for es locale`() {
        val esContext = RuntimeEnvironment.getApplication()
        val esNotificationManager = SkeddyNotificationManager(esContext)
        esNotificationManager.createChannels()

        val searchingNotification = esNotificationManager.getMonitoringNotification(isSearching = true, intervalSeconds = 30)
        val searchingContent = searchingNotification.extras.getString("android.text") ?: ""
        val expectedSearching = esContext.getString(R.string.notification_searching, 30)

        assertEquals(expectedSearching, searchingContent)

        val stoppedNotification = esNotificationManager.getMonitoringNotification(isSearching = false)
        val stoppedContent = stoppedNotification.extras.getString("android.text") ?: ""
        val expectedStopped = esContext.getString(R.string.notification_stopped)

        assertEquals(expectedStopped, stoppedContent)
    }
}
