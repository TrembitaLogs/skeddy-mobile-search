package com.skeddy.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LocationCollectorTest {

    private lateinit var mockContext: Context
    private lateinit var mockFusedClient: FusedLocationProviderClient
    private lateinit var collector: LocationCollector

    @Before
    fun setup() {
        mockContext = mockk(relaxed = true)
        mockFusedClient = mockk(relaxed = true)

        mockkStatic(ContextCompat::class)
        mockkStatic(LocationServices::class)

        every { LocationServices.getFusedLocationProviderClient(mockContext) } returns mockFusedClient

        collector = LocationCollector(mockContext)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ==================== Permission Not Granted ====================

    @Test
    fun `collect returns null when location permission not granted`() = runTest {
        every {
            ContextCompat.checkSelfPermission(mockContext, Manifest.permission.ACCESS_FINE_LOCATION)
        } returns PackageManager.PERMISSION_DENIED

        val result = collector.collect()

        assertNull(result)
    }

    // ==================== Permission Granted, Location Available ====================

    @Test
    fun `collect returns DeviceLocation when permission granted and location available`() = runTest {
        every {
            ContextCompat.checkSelfPermission(mockContext, Manifest.permission.ACCESS_FINE_LOCATION)
        } returns PackageManager.PERMISSION_GRANTED

        val mockLocation = mockk<Location>()
        every { mockLocation.latitude } returns 40.7128
        every { mockLocation.longitude } returns -74.0060

        every { mockFusedClient.lastLocation } returns Tasks.forResult(mockLocation)

        val result = collector.collect()

        assertNotNull(result)
        assertEquals(40.7128, result!!.latitude, 0.0001)
        assertEquals(-74.0060, result.longitude, 0.0001)
    }

    // ==================== Permission Granted, No Location ====================

    @Test
    fun `collect returns null when permission granted but no last location`() = runTest {
        every {
            ContextCompat.checkSelfPermission(mockContext, Manifest.permission.ACCESS_FINE_LOCATION)
        } returns PackageManager.PERMISSION_GRANTED

        every { mockFusedClient.lastLocation } returns Tasks.forResult(null)

        val result = collector.collect()

        assertNull(result)
    }

    // ==================== Exception Handling ====================

    @Test
    fun `collect returns null when fused client throws exception`() = runTest {
        every {
            ContextCompat.checkSelfPermission(mockContext, Manifest.permission.ACCESS_FINE_LOCATION)
        } returns PackageManager.PERMISSION_GRANTED

        every { mockFusedClient.lastLocation } returns Tasks.forException(
            SecurityException("Location access denied")
        )

        val result = collector.collect()

        assertNull(result)
    }
}
