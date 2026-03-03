package com.skeddy.data

import com.skeddy.model.ScheduledRide
import com.skeddy.model.RideStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerateRideKeyTest {

    private fun createRide(
        pickupTime: String = "Tomorrow \u00b7 6:05AM",
        price: Double = 25.0,
        riderName: String = "John",
        pickupLocation: String = "123 Main St",
        dropoffLocation: String = "456 Oak Ave",
        duration: String = "15 min",
        distance: String = "5.2 mi"
    ) = ScheduledRide(
        id = ScheduledRide.generateId(pickupTime, price, riderName, pickupLocation, dropoffLocation, duration, distance),
        price = price,
        bonus = null,
        pickupTime = pickupTime,
        pickupLocation = pickupLocation,
        dropoffLocation = dropoffLocation,
        duration = duration,
        distance = distance,
        riderName = riderName,
        riderRating = 4.5,
        isVerified = false
    )

    @Test
    fun sameInputs_produceSameHash() {
        val key1 = generateRideKey(createRide())
        val key2 = generateRideKey(createRide())

        assertEquals(key1, key2)
    }

    @Test
    fun differentInputs_produceDifferentHashes() {
        val key1 = generateRideKey(createRide(price = 25.0))
        val key2 = generateRideKey(createRide(price = 30.0))

        assertNotEquals(key1, key2)
    }

    @Test
    fun caseInsensitive_uppercaseAndLowercaseProduceSameHash() {
        val key1 = generateRideKey(createRide(
            riderName = "JOHN",
            pickupLocation = "123 MAIN ST",
            dropoffLocation = "456 OAK AVE"
        ))
        val key2 = generateRideKey(createRide(
            riderName = "john",
            pickupLocation = "123 main st",
            dropoffLocation = "456 oak ave"
        ))

        assertEquals(key1, key2)
    }

    @Test
    fun result_isSha256Format_64HexCharacters() {
        val key = generateRideKey(createRide())

        assertEquals(64, key.length)
        assertTrue(key.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun emptyStrings_doNotCauseException() {
        val key = generateRideKey(createRide(
            pickupTime = "",
            price = 0.0,
            riderName = "",
            pickupLocation = "",
            dropoffLocation = ""
        ))

        assertEquals(64, key.length)
        assertTrue(key.matches(Regex("[0-9a-f]{64}")))
    }
}
