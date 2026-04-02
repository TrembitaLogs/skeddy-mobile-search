package com.skeddy.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.skeddy.network.models.DeviceLocation
import kotlinx.coroutines.tasks.await

/**
 * Collects device GPS coordinates for ping requests.
 *
 * Uses [FusedLocationProviderClient.getLastLocation] to read the most recent
 * cached location (provided by Lyft Driver's active GPS usage).
 * Returns null if location permission is not granted or no fix is available.
 */
class LocationCollector(private val context: Context) {

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)

    /**
     * Collects current device location.
     *
     * @return [DeviceLocation] with latitude and longitude, or null
     */
    suspend fun collect(): DeviceLocation? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "Location permission not granted, skipping")
            return null
        }
        return try {
            val location = fusedClient.lastLocation.await()
            location?.let {
                DeviceLocation(
                    latitude = it.latitude,
                    longitude = it.longitude
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get last location", e)
            null
        }
    }

    companion object {
        private const val TAG = "LocationCollector"
    }
}
