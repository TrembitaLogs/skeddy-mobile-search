package com.skeddy.model

import org.junit.Assert.*
import org.junit.Test

class ScheduledRideTest {

    private fun createTestRide(
        id: String = "test_id",
        price: Double = 25.50,
        bonus: Double? = 3.60,
        pickupTime: String = "10:30 AM",
        pickupLocation: String = "123 Main St",
        dropoffLocation: String = "456 Oak Ave",
        duration: String = "15 min",
        distance: String = "5.2 mi",
        riderName: String = "John D.",
        riderRating: Double = 4.85,
        isVerified: Boolean = true,
        foundAt: Long = 1000L,
        status: RideStatus = RideStatus.NEW
    ) = ScheduledRide(
        id = id,
        price = price,
        bonus = bonus,
        pickupTime = pickupTime,
        pickupLocation = pickupLocation,
        dropoffLocation = dropoffLocation,
        duration = duration,
        distance = distance,
        riderName = riderName,
        riderRating = riderRating,
        isVerified = isVerified,
        foundAt = foundAt,
        status = status
    )

    @Test
    fun `default status should be NEW`() {
        val ride = ScheduledRide(
            id = "1",
            price = 20.0,
            bonus = null,
            pickupTime = "9:00 AM",
            pickupLocation = "Start",
            dropoffLocation = "End",
            duration = "10 min",
            distance = "3 mi",
            riderName = "Test",
            riderRating = 5.0,
            isVerified = false
        )

        assertEquals(RideStatus.NEW, ride.status)
    }

    @Test
    fun `foundAt should default to current time`() {
        val before = System.currentTimeMillis()
        val ride = ScheduledRide(
            id = "1",
            price = 20.0,
            bonus = null,
            pickupTime = "9:00 AM",
            pickupLocation = "Start",
            dropoffLocation = "End",
            duration = "10 min",
            distance = "3 mi",
            riderName = "Test",
            riderRating = 5.0,
            isVerified = false
        )
        val after = System.currentTimeMillis()

        assertTrue(ride.foundAt >= before)
        assertTrue(ride.foundAt <= after)
    }

    @Test
    fun `bonus can be null`() {
        val ride = createTestRide(bonus = null)
        assertNull(ride.bonus)
    }

    @Test
    fun `bonus can have value`() {
        val ride = createTestRide(bonus = 5.50)
        assertEquals(5.50, ride.bonus)
    }

    @Test
    fun `equals should return true for same data`() {
        val ride1 = createTestRide()
        val ride2 = createTestRide()

        assertEquals(ride1, ride2)
    }

    @Test
    fun `equals should return false for different id`() {
        val ride1 = createTestRide(id = "id1")
        val ride2 = createTestRide(id = "id2")

        assertNotEquals(ride1, ride2)
    }

    @Test
    fun `equals should return false for different price`() {
        val ride1 = createTestRide(price = 10.0)
        val ride2 = createTestRide(price = 20.0)

        assertNotEquals(ride1, ride2)
    }

    @Test
    fun `hashCode should be equal for same data`() {
        val ride1 = createTestRide()
        val ride2 = createTestRide()

        assertEquals(ride1.hashCode(), ride2.hashCode())
    }

    @Test
    fun `hashCode should differ for different data`() {
        val ride1 = createTestRide(id = "id1")
        val ride2 = createTestRide(id = "id2")

        assertNotEquals(ride1.hashCode(), ride2.hashCode())
    }

    @Test
    fun `copy should create identical ride`() {
        val original = createTestRide()
        val copy = original.copy()

        assertEquals(original, copy)
    }

    @Test
    fun `copy with status change should update only status`() {
        val original = createTestRide(status = RideStatus.NEW)
        val updated = original.copy(status = RideStatus.NOTIFIED)

        assertEquals(RideStatus.NOTIFIED, updated.status)
        assertEquals(original.id, updated.id)
        assertEquals(original.price, updated.price, 0.001)
        assertEquals(original.pickupLocation, updated.pickupLocation)
    }

    @Test
    fun `copy with multiple changes should update all specified fields`() {
        val original = createTestRide()
        val updated = original.copy(
            status = RideStatus.ACCEPTED,
            price = 30.0
        )

        assertEquals(RideStatus.ACCEPTED, updated.status)
        assertEquals(30.0, updated.price, 0.001)
        assertEquals(original.pickupLocation, updated.pickupLocation)
    }

    @Test
    fun `generateId should create consistent id for same inputs`() {
        val id1 = ScheduledRide.generateId("10:30 AM", 15.99, "Alice", "123 Main St", "456 Oak Ave", "10 min", "5 mi")
        val id2 = ScheduledRide.generateId("10:30 AM", 15.99, "Alice", "123 Main St", "456 Oak Ave", "10 min", "5 mi")

        assertEquals(id1, id2)
    }

    @Test
    fun `generateId should create different ids for different times`() {
        val id1 = ScheduledRide.generateId("10:30 AM", 15.99, "Alice", "123 Main St", "456 Oak Ave", "10 min", "5 mi")
        val id2 = ScheduledRide.generateId("11:00 AM", 15.99, "Alice", "123 Main St", "456 Oak Ave", "10 min", "5 mi")

        assertNotEquals(id1, id2)
    }

    @Test
    fun `generateId should create different ids for different locations`() {
        val id1 = ScheduledRide.generateId("10:30 AM", 15.99, "Alice", "123 Main St", "456 Oak Ave", "10 min", "5 mi")
        val id2 = ScheduledRide.generateId("10:30 AM", 15.99, "Alice", "789 Elm St", "456 Oak Ave", "10 min", "5 mi")

        assertNotEquals(id1, id2)
    }

    @Test
    fun `generateId should create different ids for different prices`() {
        val id1 = ScheduledRide.generateId("10:30 AM", 15.99, "Alice", "123 Main St", "456 Oak Ave", "10 min", "5 mi")
        val id2 = ScheduledRide.generateId("10:30 AM", 22.50, "Alice", "123 Main St", "456 Oak Ave", "10 min", "5 mi")

        assertNotEquals(id1, id2)
    }

    @Test
    fun `all RideStatus values should be accessible`() {
        val statuses = RideStatus.values()

        assertEquals(5, statuses.size)
        assertTrue(statuses.contains(RideStatus.NEW))
        assertTrue(statuses.contains(RideStatus.NOTIFIED))
        assertTrue(statuses.contains(RideStatus.ACCEPTED))
        assertTrue(statuses.contains(RideStatus.EXPIRED))
        assertTrue(statuses.contains(RideStatus.CANCELLED))
    }

    @Test
    fun `RideStatus valueOf should work correctly`() {
        assertEquals(RideStatus.NEW, RideStatus.valueOf("NEW"))
        assertEquals(RideStatus.NOTIFIED, RideStatus.valueOf("NOTIFIED"))
        assertEquals(RideStatus.ACCEPTED, RideStatus.valueOf("ACCEPTED"))
        assertEquals(RideStatus.EXPIRED, RideStatus.valueOf("EXPIRED"))
        assertEquals(RideStatus.CANCELLED, RideStatus.valueOf("CANCELLED"))
    }
}
