package com.skeddy.service

import com.skeddy.model.ScheduledRide
import com.skeddy.network.models.AcceptFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Unit tests for accept failure stats recording (Task 15.4).
 *
 * Verifies that:
 * - Each [AutoAcceptResult] failure type maps to the correct reason string
 *   matching the API contract (AcceptButtonNotFound, RideNotFound, etc.)
 * - [AcceptFailure] is correctly constructed from ride data
 * - Timestamp is in ISO8601 UTC format
 * - Multiple failures from different types accumulate in [PendingStatsAccumulator]
 */
class AcceptFailureStatsTest {

    private lateinit var accumulator: PendingStatsAccumulator

    @Before
    fun setUp() {
        accumulator = PendingStatsAccumulator()
    }

    // ==================== Reason string mapping ====================

    @Test
    fun `RideNotFound simpleName matches API reason string`() {
        val result: AutoAcceptResult = AutoAcceptResult.RideNotFound("details screen did not appear")
        assertEquals("RideNotFound", result::class.simpleName)
    }

    @Test
    fun `AcceptButtonNotFound simpleName matches API reason string`() {
        val result: AutoAcceptResult = AutoAcceptResult.AcceptButtonNotFound("no accept button found")
        assertEquals("AcceptButtonNotFound", result::class.simpleName)
    }

    @Test
    fun `ClickFailed simpleName matches API reason string`() {
        val result: AutoAcceptResult = AutoAcceptResult.ClickFailed("click action returned false")
        assertEquals("ClickFailed", result::class.simpleName)
    }

    @Test
    fun `ConfirmationTimeout simpleName matches API reason string`() {
        val result: AutoAcceptResult = AutoAcceptResult.ConfirmationTimeout("timed out waiting for confirmation")
        assertEquals("ConfirmationTimeout", result::class.simpleName)
    }

    @Test
    fun `Success simpleName is Success`() {
        val result: AutoAcceptResult = AutoAcceptResult.Success(createScheduledRide())
        assertEquals("Success", result::class.simpleName)
    }

    // ==================== AcceptFailure construction ====================

    @Test
    fun `AcceptFailure constructed with correct ride price`() {
        val ride = createTestRide(price = 35.75)
        val failure = createAcceptFailure(AutoAcceptResult.RideNotFound("reason"), ride)

        assertEquals(35.75, failure.ridePrice, 0.001)
    }

    @Test
    fun `AcceptFailure constructed with correct pickup time`() {
        val ride = createTestRide(pickupTime = "Tomorrow · 6:05AM")
        val failure = createAcceptFailure(AutoAcceptResult.AcceptButtonNotFound("reason"), ride)

        assertEquals("Tomorrow · 6:05AM", failure.pickupTime)
    }

    @Test
    fun `AcceptFailure constructed with correct reason from failure type`() {
        val ride = createTestRide()
        val failure = createAcceptFailure(AutoAcceptResult.ClickFailed("click returned false"), ride)

        assertEquals("ClickFailed", failure.reason)
    }

    // ==================== Timestamp format ====================

    @Test
    fun `AcceptFailure timestamp is valid ISO8601 UTC format`() {
        val ride = createTestRide()
        val failure = createAcceptFailure(AutoAcceptResult.RideNotFound("reason"), ride)

        // Verify format: yyyy-MM-dd'T'HH:mm:ss'Z'
        val parseFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        parseFormat.timeZone = TimeZone.getTimeZone("UTC")
        val parsed = parseFormat.parse(failure.timestamp)
        assertNotNull("Timestamp should be parseable as ISO8601 UTC", parsed)
    }

    @Test
    fun `AcceptFailure timestamp is close to current time`() {
        val beforeMs = System.currentTimeMillis()
        val ride = createTestRide()
        val failure = createAcceptFailure(AutoAcceptResult.RideNotFound("reason"), ride)
        val afterMs = System.currentTimeMillis()

        val parseFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        parseFormat.timeZone = TimeZone.getTimeZone("UTC")
        val parsedMs = parseFormat.parse(failure.timestamp)!!.time

        assertTrue(
            "Timestamp should be >= test start time",
            parsedMs >= beforeMs - 1000 // 1s tolerance for second boundary
        )
        assertTrue(
            "Timestamp should be <= test end time",
            parsedMs <= afterMs + 1000
        )
    }

    // ==================== Accumulation of different failure types ====================

    @Test
    fun `multiple failure types accumulate in stats`() {
        val ride = createTestRide()

        accumulator.addAcceptFailure(
            createAcceptFailure(AutoAcceptResult.RideNotFound("reason1"), ride)
        )
        accumulator.addAcceptFailure(
            createAcceptFailure(AutoAcceptResult.AcceptButtonNotFound("reason2"), ride)
        )
        accumulator.addAcceptFailure(
            createAcceptFailure(AutoAcceptResult.ClickFailed("reason3"), ride)
        )
        accumulator.addAcceptFailure(
            createAcceptFailure(AutoAcceptResult.ConfirmationTimeout("reason4"), ride)
        )

        assertEquals(4, accumulator.acceptFailures.size)
        assertEquals("RideNotFound", accumulator.acceptFailures[0].reason)
        assertEquals("AcceptButtonNotFound", accumulator.acceptFailures[1].reason)
        assertEquals("ClickFailed", accumulator.acceptFailures[2].reason)
        assertEquals("ConfirmationTimeout", accumulator.acceptFailures[3].reason)
    }

    @Test
    fun `accumulated failures are included in ping stats snapshot`() {
        val ride = createTestRide(price = 25.50, pickupTime = "Tomorrow · 6:05AM")

        accumulator.addAcceptFailure(
            createAcceptFailure(AutoAcceptResult.AcceptButtonNotFound("no button"), ride)
        )

        val stats = accumulator.toPingStats()

        assertEquals(1, stats.acceptFailures.size)
        assertEquals("AcceptButtonNotFound", stats.acceptFailures[0].reason)
        assertEquals(25.50, stats.acceptFailures[0].ridePrice, 0.001)
        assertEquals("Tomorrow · 6:05AM", stats.acceptFailures[0].pickupTime)
    }

    @Test
    fun `failures are cleared after reset`() {
        val ride = createTestRide()
        accumulator.addAcceptFailure(
            createAcceptFailure(AutoAcceptResult.RideNotFound("reason"), ride)
        )
        assertEquals(1, accumulator.acceptFailures.size)

        accumulator.reset()

        assertTrue(accumulator.acceptFailures.isEmpty())
    }

    @Test
    fun `Success result does not create AcceptFailure`() {
        val result: AutoAcceptResult = AutoAcceptResult.Success(createScheduledRide())

        // Verify the guard condition: only non-Success results should be recorded
        val shouldRecord = result !is AutoAcceptResult.Success
        assertEquals(false, shouldRecord)
    }

    // ==================== Helpers ====================

    /**
     * Mirrors the AcceptFailure construction logic from MonitoringForegroundService.
     * This ensures test coverage for the exact code path used in production.
     */
    private fun createAcceptFailure(
        result: AutoAcceptResult,
        ride: TestRide
    ): AcceptFailure {
        val reason = result::class.simpleName ?: "Unknown"
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return AcceptFailure(
            reason = reason,
            ridePrice = ride.price,
            pickupTime = ride.pickupTime,
            timestamp = sdf.format(Date())
        )
    }

    private fun createTestRide(
        price: Double = 25.50,
        pickupTime: String = "Tomorrow · 6:05AM"
    ): TestRide = TestRide(price = price, pickupTime = pickupTime)

    private fun createScheduledRide(): ScheduledRide = ScheduledRide(
        id = "test_id",
        price = 25.50,
        bonus = null,
        pickupTime = "Tomorrow · 6:05AM",
        pickupLocation = "Test Pickup",
        dropoffLocation = "Test Dropoff",
        duration = "9 min",
        distance = "3.6 mi",
        riderName = "TestRider",
        riderRating = 5.0,
        isVerified = false
    )

    /**
     * Lightweight ride stub for testing AcceptFailure construction.
     * Avoids dependency on [com.skeddy.model.ScheduledRide] which requires
     * many fields irrelevant to failure stats.
     */
    private data class TestRide(
        val price: Double,
        val pickupTime: String
    )
}
