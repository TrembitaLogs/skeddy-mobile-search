package com.skeddy.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [DeviceTokenManager].
 * Runs on a real device/emulator to verify EncryptedSharedPreferences behavior,
 * persistence across instances, and ANDROID_ID consistency.
 */
@RunWith(AndroidJUnit4::class)
class DeviceTokenManagerIntegrationTest {

    private lateinit var context: Context
    private lateinit var manager: DeviceTokenManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        manager = DeviceTokenManager(context)
        manager.clearDeviceToken()
    }

    @After
    fun teardown() {
        manager.clearDeviceToken()
    }

    @Test
    fun testPersistenceAfterRecreation() {
        val token = "integration-test-token-abc123"
        manager.saveDeviceToken(token)

        // Create a completely new instance — simulates app restart
        val newManager = DeviceTokenManager(context)

        assertEquals(token, newManager.getDeviceToken())
        assertTrue(newManager.isLoggedIn())
    }

    @Test
    fun testEncryptionWorksCorrectly() {
        val testToken = "secret-token-should-not-appear-in-plaintext"
        manager.saveDeviceToken(testToken)

        // Access the raw SharedPreferences backing store (bypassing encryption layer).
        // EncryptedSharedPreferences writes encrypted keys and values to the same XML file.
        // Reading via regular getSharedPreferences returns the raw encrypted entries.
        val rawPrefs = context.getSharedPreferences("skeddy_secure_prefs", Context.MODE_PRIVATE)
        val allEntries = rawPrefs.all

        // The plain text key "device_token" should NOT appear — keys are encrypted
        assertFalse(
            "Plain text key 'device_token' should not appear in encrypted prefs",
            allEntries.containsKey("device_token")
        )

        // The plain text token value should NOT appear in any stored value
        for ((_, value) in allEntries) {
            if (value is String) {
                assertFalse(
                    "Plain text token should not appear in encrypted prefs values",
                    value.contains(testToken)
                )
            }
        }
    }

    @Test
    fun testClearTokenPersistsAfterRecreation() {
        manager.saveDeviceToken("token-to-clear")
        manager.clearDeviceToken()

        // Create a new instance — verify clear persisted
        val newManager = DeviceTokenManager(context)

        assertFalse(newManager.isLoggedIn())
        assertNull(newManager.getDeviceToken())
    }

    @Test
    fun testGetDeviceIdConsistency() {
        val id1 = manager.getDeviceId()
        val id2 = manager.getDeviceId()

        // Also verify consistency across different instances
        val newManager = DeviceTokenManager(context)
        val id3 = newManager.getDeviceId()

        assertTrue("Device ID should not be empty", id1.isNotEmpty())
        assertEquals("Device ID should be consistent across calls", id1, id2)
        assertEquals("Device ID should be consistent across instances", id1, id3)
    }
}
