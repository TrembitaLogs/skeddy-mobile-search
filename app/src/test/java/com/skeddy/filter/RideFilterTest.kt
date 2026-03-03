package com.skeddy.filter

import com.skeddy.data.BlacklistRepository
import com.skeddy.model.RideStatus
import com.skeddy.model.ScheduledRide
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RideFilterTest {

    private lateinit var blacklistRepository: BlacklistRepository
    private lateinit var filter: RideFilter

    @Before
    fun setup() {
        blacklistRepository = mockk()
        // By default no rides are blacklisted
        coEvery { blacklistRepository.exists(any()) } returns false
        filter = RideFilter(blacklistRepository)
    }

    private fun createRide(
        id: String = "ride_1",
        price: Double = 25.0,
        pickupTime: String = "Today · 6:05AM",
        riderName: String = "John",
        pickupLocation: String = "123 Main St",
        dropoffLocation: String = "456 Oak Ave"
    ) = ScheduledRide(
        id = id,
        price = price,
        bonus = null,
        pickupTime = pickupTime,
        pickupLocation = pickupLocation,
        dropoffLocation = dropoffLocation,
        duration = "15 min",
        distance = "5.2 mi",
        riderName = riderName,
        riderRating = 4.8,
        isVerified = true,
        foundAt = System.currentTimeMillis(),
        status = RideStatus.NEW
    )

    // ==================== Step 1: Hardcoded $10 Filter ====================

    @Test
    fun `filterRides removes rides below hardcoded 10 dollar minimum`() = runTest {
        val rides = listOf(
            createRide(id = "1", price = 5.0),
            createRide(id = "2", price = 9.99),
            createRide(id = "3", price = 3.0)
        )

        val result = filter.filterRides(rides, serverMinPrice = 10.0)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `filterRides includes ride exactly at hardcoded 10 dollar minimum`() = runTest {
        val rides = listOf(
            createRide(id = "1", price = 10.0)
        )

        val result = filter.filterRides(rides, serverMinPrice = 10.0)

        assertEquals(1, result.size)
        assertEquals("1", result[0].id)
    }

    @Test
    fun `filterRides hardcoded filter applies regardless of serverMinPrice`() = runTest {
        // Even if serverMinPrice is lower than hardcoded, $10 still applies
        val rides = listOf(
            createRide(id = "1", price = 8.0),
            createRide(id = "2", price = 12.0)
        )

        val result = filter.filterRides(rides, serverMinPrice = 5.0)

        assertEquals(1, result.size)
        assertEquals("2", result[0].id)
    }

    // ==================== Step 2: Server min_price Filter ====================

    @Test
    fun `filterRides removes rides below serverMinPrice when higher than hardcoded`() = runTest {
        val rides = listOf(
            createRide(id = "1", price = 12.0),
            createRide(id = "2", price = 18.0),
            createRide(id = "3", price = 25.0)
        )

        val result = filter.filterRides(rides, serverMinPrice = 20.0)

        assertEquals(1, result.size)
        assertEquals("3", result[0].id)
    }

    @Test
    fun `filterRides includes ride exactly at serverMinPrice`() = runTest {
        val rides = listOf(
            createRide(id = "1", price = 20.0)
        )

        val result = filter.filterRides(rides, serverMinPrice = 20.0)

        assertEquals(1, result.size)
    }

    // ==================== Step 3: Blacklist Filter ====================

    @Test
    fun `filterRides excludes blacklisted rides`() = runTest {
        val rides = listOf(
            createRide(id = "1", price = 25.0, riderName = "Alice"),
            createRide(id = "2", price = 30.0, riderName = "Bob"),
            createRide(id = "3", price = 35.0, riderName = "Carol")
        )

        // Blacklist the key for ride "2" (Bob)
        val bobKey = com.skeddy.data.generateRideKey(createRide(id = "2", price = 30.0, riderName = "Bob"))
        coEvery { blacklistRepository.exists(bobKey) } returns true

        val result = filter.filterRides(rides, serverMinPrice = 10.0)

        assertEquals(2, result.size)
        assertTrue(result.none { it.id == "2" })
        assertTrue(result.any { it.id == "1" })
        assertTrue(result.any { it.id == "3" })
    }

    @Test
    fun `filterRides excludes all blacklisted rides`() = runTest {
        val rides = listOf(
            createRide(id = "1", price = 25.0),
            createRide(id = "2", price = 30.0)
        )

        coEvery { blacklistRepository.exists(any()) } returns true

        val result = filter.filterRides(rides, serverMinPrice = 10.0)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `filterRides calls blacklistRepository exists with correct key`() = runTest {
        val rides = listOf(
            createRide(
                id = "1",
                price = 25.0,
                pickupTime = "Today · 6:05AM",
                riderName = "Alice",
                pickupLocation = "123 Main St",
                dropoffLocation = "456 Oak Ave"
            )
        )

        val expectedKey = com.skeddy.data.generateRideKey(rides[0])

        filter.filterRides(rides, serverMinPrice = 10.0)

        coVerify(exactly = 1) { blacklistRepository.exists(expectedKey) }
    }

    // ==================== Combined Cascade ====================

    @Test
    fun `filterRides applies all three filters in cascade`() = runTest {
        val rides = listOf(
            createRide(id = "cheap", price = 5.0),               // Filtered by hardcoded $10
            createRide(id = "below-server", price = 15.0),        // Filtered by serverMinPrice $20
            createRide(id = "blacklisted", price = 25.0, riderName = "Blocked"),  // Filtered by blacklist
            createRide(id = "passes", price = 30.0, riderName = "Good")           // Passes all
        )

        val blockedKey = com.skeddy.data.generateRideKey(createRide(id = "blacklisted", price = 25.0, riderName = "Blocked"))
        coEvery { blacklistRepository.exists(blockedKey) } returns true

        val result = filter.filterRides(rides, serverMinPrice = 20.0)

        assertEquals(1, result.size)
        assertEquals("passes", result[0].id)
    }

    @Test
    fun `filterRides does not check blacklist for price-filtered rides`() = runTest {
        val rides = listOf(
            createRide(id = "cheap", price = 5.0),
            createRide(id = "below-server", price = 15.0),
            createRide(id = "passes", price = 25.0)
        )

        filter.filterRides(rides, serverMinPrice = 20.0)

        // Only the ride that passed both price filters should trigger a blacklist lookup
        coVerify(exactly = 1) { blacklistRepository.exists(any()) }
    }

    // ==================== Order Preservation ====================

    @Test
    fun `filterRides preserves original ride order`() = runTest {
        val rides = listOf(
            createRide(id = "C", price = 50.0),
            createRide(id = "A", price = 20.0),
            createRide(id = "B", price = 30.0)
        )

        val result = filter.filterRides(rides, serverMinPrice = 10.0)

        assertEquals(3, result.size)
        assertEquals("C", result[0].id)
        assertEquals("A", result[1].id)
        assertEquals("B", result[2].id)
    }

    // ==================== Empty Input ====================

    @Test
    fun `filterRides returns empty list for empty input`() = runTest {
        val result = filter.filterRides(emptyList(), serverMinPrice = 20.0)

        assertTrue(result.isEmpty())
    }

    // ==================== Edge Cases ====================

    @Test
    fun `filterRides passes rides above hardcoded when serverMinPrice is zero`() = runTest {
        val rides = listOf(
            createRide(id = "1", price = 5.0),
            createRide(id = "2", price = 10.0),
            createRide(id = "3", price = 15.0)
        )

        val result = filter.filterRides(rides, serverMinPrice = 0.0)

        assertEquals(2, result.size)
        assertEquals("2", result[0].id)
        assertEquals("3", result[1].id)
    }

    @Test
    fun `filterRides filters out rides with negative prices`() = runTest {
        val rides = listOf(
            createRide(id = "1", price = -5.0),
            createRide(id = "2", price = 0.0),
            createRide(id = "3", price = 25.0)
        )

        val result = filter.filterRides(rides, serverMinPrice = 10.0)

        assertEquals(1, result.size)
        assertEquals("3", result[0].id)
    }

    @Test
    fun `filterRides returns empty when all rides filtered by serverMinPrice`() = runTest {
        val rides = listOf(
            createRide(id = "1", price = 12.0),
            createRide(id = "2", price = 15.0),
            createRide(id = "3", price = 18.0)
        )

        val result = filter.filterRides(rides, serverMinPrice = 20.0)

        assertTrue(result.isEmpty())
    }

    // ==================== HARDCODED_MIN_PRICE Constant ====================

    @Test
    fun `HARDCODED_MIN_PRICE is 10 dollars`() {
        assertEquals(10.0, RideFilter.HARDCODED_MIN_PRICE, 0.0)
    }
}
