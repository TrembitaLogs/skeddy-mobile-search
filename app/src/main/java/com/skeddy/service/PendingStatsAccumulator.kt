package com.skeddy.service

import com.skeddy.network.models.AcceptFailure
import com.skeddy.network.models.PingStats
import java.util.UUID

/**
 * Mutable accumulator for statistics sent with the next ping request.
 *
 * Tracks cycles executed, rides found, and accept failures between
 * successful pings. After each successful ping, [reset] generates
 * a new [batchId] and clears all counters, starting a fresh
 * accumulation window.
 *
 * The [batchId] enables server-side deduplication: if a ping is
 * retried due to a network error, the server recognises the same
 * batch and ignores duplicate stats.
 */
class PendingStatsAccumulator {

    /** UUID v4 identifying this batch of stats. Regenerated on [reset]. */
    var batchId: String = UUID.randomUUID().toString()
        private set

    /** Number of search cycles executed since the last successful ping. */
    var cyclesSinceLastPing: Int = 0
        private set

    /** Number of rides that passed all filters since the last successful ping. */
    var ridesFound: Int = 0
        private set

    /** Accept failures collected since the last successful ping. */
    private val _acceptFailures: MutableList<AcceptFailure> = mutableListOf()
    val acceptFailures: List<AcceptFailure> get() = _acceptFailures

    /** Increments the cycle counter by one. Call after each search cycle. */
    fun incrementCycles() {
        cyclesSinceLastPing++
    }

    /** Adds [count] to the rides found counter. */
    fun addRidesFound(count: Int) {
        ridesFound += count
    }

    /** Records a single accept failure. */
    fun addAcceptFailure(failure: AcceptFailure) {
        _acceptFailures.add(failure)
    }

    /**
     * Creates an immutable [PingStats] snapshot of the current state.
     * Use before sending the ping request.
     */
    fun toPingStats(): PingStats = PingStats(
        batchId = batchId,
        cyclesSinceLastPing = cyclesSinceLastPing,
        ridesFound = ridesFound,
        acceptFailures = _acceptFailures.toList()
    )

    /**
     * Resets all counters and generates a new [batchId].
     * Call after a successful ping response has been processed.
     */
    fun reset() {
        batchId = UUID.randomUUID().toString()
        cyclesSinceLastPing = 0
        ridesFound = 0
        _acceptFailures.clear()
    }
}
