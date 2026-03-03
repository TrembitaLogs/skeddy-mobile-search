package com.skeddy.service

import com.skeddy.network.models.AcceptFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [PendingStatsAccumulator].
 *
 * Verifies that:
 * - [PendingStatsAccumulator.reset] generates a new batchId and clears all counters.
 * - Increment/add methods correctly accumulate stats.
 * - [PendingStatsAccumulator.toPingStats] produces a correct immutable snapshot.
 */
class PendingStatsAccumulatorTest {

    private lateinit var accumulator: PendingStatsAccumulator

    @Before
    fun setUp() {
        accumulator = PendingStatsAccumulator()
    }

    @Test
    fun `initial state has zero counters and empty failures`() {
        assertEquals(0, accumulator.cyclesSinceLastPing)
        assertEquals(0, accumulator.ridesFound)
        assertTrue(accumulator.acceptFailures.isEmpty())
        assertTrue(accumulator.batchId.isNotEmpty())
    }

    @Test
    fun `incrementCycles increases counter by one`() {
        accumulator.incrementCycles()
        assertEquals(1, accumulator.cyclesSinceLastPing)

        accumulator.incrementCycles()
        assertEquals(2, accumulator.cyclesSinceLastPing)
    }

    @Test
    fun `addRidesFound adds to rides counter`() {
        accumulator.addRidesFound(3)
        assertEquals(3, accumulator.ridesFound)

        accumulator.addRidesFound(2)
        assertEquals(5, accumulator.ridesFound)
    }

    @Test
    fun `addAcceptFailure appends to failures list`() {
        val failure = AcceptFailure(
            reason = "AcceptButtonNotFound",
            ridePrice = 25.50,
            pickupTime = "Tomorrow · 6:05AM",
            timestamp = "2026-02-09T10:30:00Z"
        )
        accumulator.addAcceptFailure(failure)

        assertEquals(1, accumulator.acceptFailures.size)
        assertEquals(failure, accumulator.acceptFailures[0])
    }

    @Test
    fun `reset generates new batchId`() {
        val originalBatchId = accumulator.batchId
        accumulator.reset()
        assertNotEquals(originalBatchId, accumulator.batchId)
        assertTrue(accumulator.batchId.isNotEmpty())
    }

    @Test
    fun `reset clears all counters`() {
        accumulator.incrementCycles()
        accumulator.incrementCycles()
        accumulator.addRidesFound(5)
        accumulator.addAcceptFailure(
            AcceptFailure("ClickFailed", 30.0, "Today · 3:00PM", "2026-02-09T11:00:00Z")
        )

        accumulator.reset()

        assertEquals(0, accumulator.cyclesSinceLastPing)
        assertEquals(0, accumulator.ridesFound)
        assertTrue(accumulator.acceptFailures.isEmpty())
    }

    @Test
    fun `toPingStats creates correct snapshot`() {
        accumulator.incrementCycles()
        accumulator.incrementCycles()
        accumulator.incrementCycles()
        accumulator.addRidesFound(2)
        val failure = AcceptFailure(
            reason = "ConfirmationTimeout",
            ridePrice = 18.0,
            pickupTime = "Tomorrow · 9:00AM",
            timestamp = "2026-02-09T12:00:00Z"
        )
        accumulator.addAcceptFailure(failure)

        val stats = accumulator.toPingStats()

        assertEquals(accumulator.batchId, stats.batchId)
        assertEquals(3, stats.cyclesSinceLastPing)
        assertEquals(2, stats.ridesFound)
        assertEquals(1, stats.acceptFailures.size)
        assertEquals(failure, stats.acceptFailures[0])
    }

    @Test
    fun `toPingStats returns independent copy of failures`() {
        val failure = AcceptFailure(
            reason = "AcceptButtonNotFound",
            ridePrice = 25.0,
            pickupTime = "Tomorrow · 6:05AM",
            timestamp = "2026-02-09T10:30:00Z"
        )
        accumulator.addAcceptFailure(failure)

        val stats = accumulator.toPingStats()

        // Adding another failure after snapshot should not affect the snapshot
        accumulator.addAcceptFailure(
            AcceptFailure("ClickFailed", 30.0, "Today · 3:00PM", "2026-02-09T11:00:00Z")
        )

        assertEquals(1, stats.acceptFailures.size)
        assertEquals(2, accumulator.acceptFailures.size)
    }

    @Test
    fun `multiple resets generate unique batchIds`() {
        val ids = mutableSetOf<String>()
        ids.add(accumulator.batchId)

        repeat(10) {
            accumulator.reset()
            ids.add(accumulator.batchId)
        }

        // All 11 IDs (initial + 10 resets) should be unique
        assertEquals(11, ids.size)
    }
}
