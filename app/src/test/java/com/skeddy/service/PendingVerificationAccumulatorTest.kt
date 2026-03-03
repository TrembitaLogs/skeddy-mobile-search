package com.skeddy.service

import com.skeddy.network.models.RideStatusReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PendingVerificationAccumulatorTest {

    private lateinit var accumulator: PendingVerificationAccumulator

    @Before
    fun setUp() {
        accumulator = PendingVerificationAccumulator()
    }

    @Test
    fun `initial state has no pending hashes and null status list`() {
        assertTrue(accumulator.getPendingHashes().isEmpty())
        assertNull(accumulator.toRideStatusList())
    }

    @Test
    fun `setPendingVerification stores hashes`() {
        accumulator.setPendingVerification(listOf("hash1", "hash2", "hash3"))

        assertEquals(3, accumulator.getPendingHashes().size)
        assertEquals(listOf("hash1", "hash2", "hash3"), accumulator.getPendingHashes())
    }

    @Test
    fun `setPendingVerification overwrites previous hashes`() {
        accumulator.setPendingVerification(listOf("old1", "old2"))
        accumulator.setPendingVerification(listOf("new1"))

        assertEquals(listOf("new1"), accumulator.getPendingHashes())
    }

    @Test
    fun `reportVerificationResults marks present and not-present correctly`() {
        accumulator.setPendingVerification(listOf("hash1", "hash2", "hash3"))

        val parsedHashes = setOf("hash1", "hash3", "other_hash")
        accumulator.reportVerificationResults(parsedHashes)

        val statuses = accumulator.toRideStatusList()!!
        assertEquals(3, statuses.size)

        assertEquals("hash1", statuses[0].rideHash)
        assertTrue(statuses[0].present)

        assertEquals("hash2", statuses[1].rideHash)
        assertTrue(!statuses[1].present)

        assertEquals("hash3", statuses[2].rideHash)
        assertTrue(statuses[2].present)
    }

    @Test
    fun `toRideStatusList returns null when no results reported`() {
        accumulator.setPendingVerification(listOf("hash1"))
        // No reportVerificationResults called
        assertNull(accumulator.toRideStatusList())
    }

    @Test
    fun `toRideStatusList returns list after reporting`() {
        accumulator.setPendingVerification(listOf("hash1"))
        accumulator.reportVerificationResults(setOf("hash1"))

        val statuses = accumulator.toRideStatusList()
        assertEquals(1, statuses?.size)
        assertEquals(RideStatusReport(rideHash = "hash1", present = true), statuses!![0])
    }

    @Test
    fun `reset clears statuses but keeps pending hashes`() {
        accumulator.setPendingVerification(listOf("hash1", "hash2"))
        accumulator.reportVerificationResults(setOf("hash1"))

        accumulator.reset()

        assertNull(accumulator.toRideStatusList())
        assertEquals(listOf("hash1", "hash2"), accumulator.getPendingHashes())
    }

    @Test
    fun `reportVerificationResults with no pending hashes is no-op`() {
        accumulator.reportVerificationResults(setOf("hash1", "hash2"))
        assertNull(accumulator.toRideStatusList())
    }

    @Test
    fun `reportVerificationResults clears previous results`() {
        accumulator.setPendingVerification(listOf("hash1"))
        accumulator.reportVerificationResults(setOf("hash1"))
        assertEquals(1, accumulator.toRideStatusList()?.size)

        // Report again with different data
        accumulator.reportVerificationResults(emptySet())
        val statuses = accumulator.toRideStatusList()!!
        assertEquals(1, statuses.size)
        assertTrue(!statuses[0].present)
    }

    @Test
    fun `setPendingVerification clears previous statuses`() {
        accumulator.setPendingVerification(listOf("hash1"))
        accumulator.reportVerificationResults(setOf("hash1"))
        assertEquals(1, accumulator.toRideStatusList()?.size)

        // Setting new hashes clears old statuses
        accumulator.setPendingVerification(listOf("new_hash"))
        assertNull(accumulator.toRideStatusList())
    }

    @Test
    fun `toRideStatusList returns defensive copy`() {
        accumulator.setPendingVerification(listOf("hash1"))
        accumulator.reportVerificationResults(setOf("hash1"))

        val list1 = accumulator.toRideStatusList()
        val list2 = accumulator.toRideStatusList()
        assertEquals(list1, list2)
        assertTrue(list1 !== list2) // Different list instances
    }
}
