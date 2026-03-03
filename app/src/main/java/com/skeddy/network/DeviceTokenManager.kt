package com.skeddy.network

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import androidx.annotation.VisibleForTesting
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.skeddy.logging.SkeddyLogger
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Manages secure storage and retrieval of the device token
 * used for server authentication (X-Device-Token header),
 * along with the device identifier (ANDROID_ID) for X-Device-ID header.
 *
 * Device token is stored in EncryptedSharedPreferences with AES256 encryption.
 * Falls back to unencrypted SharedPreferences if encryption is unavailable.
 */
@Suppress("DEPRECATION")
class DeviceTokenManager @VisibleForTesting internal constructor(
    private val prefs: SharedPreferences,
    private val contentResolver: ContentResolver
) {

    constructor(context: Context) : this(
        createPreferences(context),
        context.contentResolver
    )

    /**
     * Retrieves the stored device token.
     * @return device token string, or null if not paired.
     */
    fun getDeviceToken(): String? {
        val token = prefs.getString(KEY_DEVICE_TOKEN, null)
        SkeddyLogger.d(TAG, "DeviceToken: retrieved (exists=${token != null})")
        return token
    }

    /**
     * Saves the device token received from pairing confirmation.
     * @param token the device token from POST /pairing/confirm response.
     */
    fun saveDeviceToken(token: String) {
        prefs.edit().putString(KEY_DEVICE_TOKEN, token).apply()
        SkeddyLogger.i(TAG, "DeviceToken: saved")
    }

    /**
     * Clears the stored device token.
     * Called on 401/403 server responses to trigger re-pairing.
     */
    fun clearDeviceToken() {
        prefs.edit().remove(KEY_DEVICE_TOKEN).apply()
        SkeddyLogger.i(TAG, "DeviceToken: cleared")
    }

    /**
     * Checks whether a device token is stored (device is paired).
     * @return true if device token exists.
     */
    fun isPaired(): Boolean = getDeviceToken() != null

    /**
     * Returns the unique device identifier (Settings.Secure.ANDROID_ID).
     * Stable per app signing key + user + device combination.
     */
    fun getDeviceId(): String {
        return Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?: ""
    }

    companion object {
        private const val TAG = "DeviceTokenManager"
        private const val PREFS_NAME = "skeddy_secure_prefs"
        private const val KEY_DEVICE_TOKEN = "device_token"

        private fun createPreferences(context: Context): SharedPreferences {
            return try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: GeneralSecurityException) {
                SkeddyLogger.w(
                    TAG,
                    "Failed to create encrypted preferences, falling back to unencrypted",
                    e
                )
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            } catch (e: IOException) {
                SkeddyLogger.w(
                    TAG,
                    "Failed to create encrypted preferences, falling back to unencrypted",
                    e
                )
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
        }
    }
}
