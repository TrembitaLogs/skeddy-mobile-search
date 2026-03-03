package com.skeddy.service

import com.skeddy.network.models.RideStatusReport

/**
 * Manages ride verification state between pings.
 *
 * The server sends a list of ride hashes to verify via [setPendingVerification].
 * During the next search cycle, the search flow calls [reportVerificationResults]
 * with the set of all parsed ride hashes from the Lyft Driver UI. This class
 * compares the two sets and builds [RideStatusReport] entries.
 *
 * After a successful ping sends the results, [reset] clears the accumulated
 * statuses but preserves [pendingHashes] — the server may keep requesting
 * verification for the same rides until their deadline is reached.
 */
class PendingVerificationAccumulator {

    /** Ride hashes the server wants verified (from the last verify_rides response). */
    private var pendingHashes: List<String> = emptyList()

    /** Accumulated verification results to be sent in the next ping request. */
    private val _rideStatuses: MutableList<RideStatusReport> = mutableListOf()

    /** Sets the ride hashes to verify (from server's verify_rides response). */
    fun setPendingVerification(hashes: List<String>) {
        pendingHashes = hashes
        _rideStatuses.clear()
    }

    /** Returns the hashes that need to be checked during the next search cycle. */
    fun getPendingHashes(): List<String> = pendingHashes

    /**
     * Compares [parsedRideHashes] (all ride IDs visible in Lyft Driver UI)
     * against [pendingHashes] and builds [RideStatusReport] for each.
     *
     * Should be called after ride parsing, before filtering.
     */
    fun reportVerificationResults(parsedRideHashes: Set<String>) {
        if (pendingHashes.isEmpty()) return
        _rideStatuses.clear()
        for (hash in pendingHashes) {
            _rideStatuses.add(RideStatusReport(rideHash = hash, present = hash in parsedRideHashes))
        }
    }

    /**
     * Returns the list for inclusion in [PingRequest.rideStatuses],
     * or null if no verification results are available.
     */
    fun toRideStatusList(): List<RideStatusReport>? {
        return _rideStatuses.ifEmpty { null }?.toList()
    }

    /**
     * Clears accumulated statuses after a successful ping.
     * Does NOT clear [pendingHashes] — the server controls which rides to verify.
     */
    fun reset() {
        _rideStatuses.clear()
    }
}
