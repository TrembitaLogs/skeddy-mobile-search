package com.skeddy.data

import android.util.Log
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BlacklistRepositoryTest {

    private lateinit var dao: BlacklistDao
    private lateinit var repository: BlacklistRepository

    @Before
    fun setup() {
        dao = mockk(relaxed = true)
        repository = BlacklistRepository(dao)
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
    }

    @After
    fun teardown() {
        unmockkStatic(Log::class)
    }

    // 1) exists() delegates to dao.exists()
    @Test
    fun `exists delegates to dao exists`() = runTest {
        coEvery { dao.exists("test_key") } returns true

        val result = repository.exists("test_key")

        assertTrue(result)
        coVerify { dao.exists("test_key") }
    }

    // 2) addToBlacklist() delegates to dao.insert()
    @Test
    fun `addToBlacklist delegates to dao insert`() = runTest {
        val ride = createTestRide()

        repository.addToBlacklist(ride)

        coVerify { dao.insert(ride) }
    }

    // 3) clearAll() delegates to dao.clearAll()
    @Test
    fun `clearAll delegates to dao clearAll`() = runTest {
        repository.clearAll()

        coVerify { dao.clearAll() }
    }

    // 4) cleanupExpiredRides() calls both cleanup methods and returns sum
    @Test
    fun `cleanupExpiredRides calls both cleanup methods and returns sum`() = runTest {
        coEvery { dao.cleanupExpired(any()) } returns 3
        coEvery { dao.cleanupOld(any()) } returns 2

        val result = repository.cleanupExpiredRides()

        assertEquals(5, result)
        coVerify { dao.cleanupExpired(any()) }
        coVerify { dao.cleanupOld(any()) }
    }

    // 5) cleanupExpiredRides() uses correct values: now and 10-day fallback cutoff
    @Test
    fun `cleanupExpiredRides uses correct now and fallback TTL values`() = runTest {
        val capturedNow = slot<Long>()
        val capturedCutoff = slot<Long>()
        coEvery { dao.cleanupExpired(capture(capturedNow)) } returns 0
        coEvery { dao.cleanupOld(capture(capturedCutoff)) } returns 0

        repository.cleanupExpiredRides()

        val now = capturedNow.captured
        val cutoff = capturedCutoff.captured
        val expectedCutoff = now - BlacklistRepository.FALLBACK_TTL_MS
        assertTrue("now should be a recent timestamp", now > 0)
        assertEquals(expectedCutoff, cutoff)
    }

    // 6) cleanupExpiredRides() logs only when count > 0
    @Test
    fun `cleanupExpiredRides logs only when count greater than zero`() = runTest {
        // Case: total > 0 — should log
        coEvery { dao.cleanupExpired(any()) } returns 2
        coEvery { dao.cleanupOld(any()) } returns 1

        repository.cleanupExpiredRides()

        verify { Log.i("BlacklistRepository", "Cleanup: removed 2 expired, 1 old (10d fallback)") }

        // Case: total == 0 — should NOT log beyond the previous call
        coEvery { dao.cleanupExpired(any()) } returns 0
        coEvery { dao.cleanupOld(any()) } returns 0

        repository.cleanupExpiredRides()

        // Still only 1 Log.i call total (from the first invocation)
        verify(exactly = 1) { Log.i(any(), any()) }
    }

    private fun createTestRide(
        rideKey: String = "test_key_abc123",
        price: Double = 25.0,
        pickupTime: String = "Tomorrow · 6:05AM",
        pickupLocation: String = "123 Main St",
        riderName: String = "John",
        dropoffLocation: String = "456 Oak Ave",
        addedAt: Long = 1_000_000L,
        parsedPickupTimestamp: Long? = null
    ) = BlacklistedRide(
        rideKey = rideKey,
        price = price,
        pickupTime = pickupTime,
        pickupLocation = pickupLocation,
        riderName = riderName,
        dropoffLocation = dropoffLocation,
        addedAt = addedAt,
        parsedPickupTimestamp = parsedPickupTimestamp
    )
}
