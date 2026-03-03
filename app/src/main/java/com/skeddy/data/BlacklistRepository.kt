package com.skeddy.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository wrapping [BlacklistDao] with business logic for ride cleanup.
 *
 * Provides a single access point for blacklist operations used by
 * MonitoringForegroundService, AutoAcceptManager, PairingActivity, and RideFilter.
 */
class BlacklistRepository(private val blacklistDao: BlacklistDao) {

    suspend fun exists(rideKey: String): Boolean {
        return blacklistDao.exists(rideKey)
    }

    suspend fun addToBlacklist(ride: BlacklistedRide) {
        blacklistDao.insert(ride)
    }

    suspend fun clearAll() {
        blacklistDao.clearAll()
    }

    /**
     * Cleanup expired rides:
     * 1. Remove rides where parsedPickupTimestamp < now (ride already happened)
     * 2. Remove rides without parsed timestamp older than [FALLBACK_TTL_MS] (fallback TTL)
     *
     * Fallback TTL is 10 days (not 48h) because scheduled rides can be up to a week
     * in the future. A shorter TTL would cause duplicate accept attempts for rides
     * whose pickup time string could not be parsed into a timestamp.
     *
     * Returns total number of removed entries.
     */
    suspend fun cleanupExpiredRides(): Int = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val expiredCount = blacklistDao.cleanupExpired(now)
        val cutoff = now - FALLBACK_TTL_MS
        val oldCount = blacklistDao.cleanupOld(cutoff)
        val total = expiredCount + oldCount
        if (total > 0) {
            Log.i(TAG, "Cleanup: removed $expiredCount expired, $oldCount old (10d fallback)")
        }
        total
    }

    companion object {
        private const val TAG = "BlacklistRepository"

        /** Fallback TTL for blacklisted rides with unparseable pickup time: 10 days. */
        internal const val FALLBACK_TTL_MS = 10L * 24 * 60 * 60 * 1000
    }
}
