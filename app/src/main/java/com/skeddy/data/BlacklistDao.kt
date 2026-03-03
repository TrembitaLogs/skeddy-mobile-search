package com.skeddy.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BlacklistDao {

    @Query("SELECT EXISTS(SELECT 1 FROM blacklisted_rides WHERE rideKey = :key)")
    suspend fun exists(key: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(ride: BlacklistedRide)

    @Query("DELETE FROM blacklisted_rides WHERE parsedPickupTimestamp < :now")
    suspend fun cleanupExpired(now: Long): Int

    @Query("DELETE FROM blacklisted_rides WHERE addedAt < :cutoff AND parsedPickupTimestamp IS NULL")
    suspend fun cleanupOld(cutoff: Long): Int

    @Query("DELETE FROM blacklisted_rides")
    suspend fun clearAll()
}
