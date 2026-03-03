package com.skeddy.data

import com.skeddy.model.ScheduledRide
import com.skeddy.util.PickupTimeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ToBlacklistedRideTest {

    private val baseRide = ScheduledRide(
        id = "ride_1",
        price = 25.50,
        bonus = 5.0,
        pickupTime = "Tomorrow · 6:05AM",
        pickupLocation = "123 Main St",
        dropoffLocation = "456 Oak Ave",
        duration = "15 min",
        distance = "8 mi",
        riderName = "Alice",
        riderRating = 4.9,
        isVerified = true
    )

    @Test
    fun `toBlacklistedRide copies all ride fields correctly`() {
        val result = baseRide.toBlacklistedRide()

        assertEquals(baseRide.price, result.price, 0.001)
        assertEquals(baseRide.pickupTime, result.pickupTime)
        assertEquals(baseRide.pickupLocation, result.pickupLocation)
        assertEquals(baseRide.dropoffLocation, result.dropoffLocation)
        assertEquals(baseRide.riderName, result.riderName)
    }

    @Test
    fun `toBlacklistedRide generates correct rideKey`() {
        val expectedKey = generateRideKey(baseRide)
        val result = baseRide.toBlacklistedRide()

        assertEquals(expectedKey, result.rideKey)
        assertEquals(64, result.rideKey.length)
        assertTrue(result.rideKey.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `toBlacklistedRide sets addedAt to current time`() {
        val before = System.currentTimeMillis()
        val result = baseRide.toBlacklistedRide()
        val after = System.currentTimeMillis()

        assertTrue(result.addedAt in before..after)
    }

    @Test
    fun `toBlacklistedRide parses pickup timestamp for valid format`() {
        // Fix clock so PickupTimeParser produces a deterministic result
        val fixedInstant = Instant.parse("2026-02-13T12:00:00Z")
        PickupTimeParser.clock = Clock.fixed(fixedInstant, ZoneId.of("America/New_York"))
        try {
            val result = baseRide.toBlacklistedRide()
            assertNotNull(result.parsedPickupTimestamp)
        } finally {
            PickupTimeParser.clock = Clock.systemDefaultZone()
        }
    }

    @Test
    fun `toBlacklistedRide returns null parsedPickupTimestamp for unparseable format`() {
        val ride = baseRide.copy(pickupTime = "Some unknown format")
        val result = ride.toBlacklistedRide()

        assertNull(result.parsedPickupTimestamp)
    }
}
