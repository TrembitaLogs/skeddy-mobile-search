package com.skeddy.data

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@Database(entities = [BlacklistedRide::class], version = 1, exportSchema = false)
abstract class BlacklistTestDatabase : RoomDatabase() {
    abstract fun blacklistDao(): BlacklistDao
}

@RunWith(AndroidJUnit4::class)
class BlacklistDaoTest {

    private lateinit var database: BlacklistTestDatabase
    private lateinit var dao: BlacklistDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            BlacklistTestDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.blacklistDao()
    }

    @After
    fun teardown() {
        database.close()
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

    @Test
    fun insert_andExists_returnsTrue() = runTest {
        val ride = createTestRide()

        dao.insert(ride)

        assertTrue(dao.exists("test_key_abc123"))
    }

    @Test
    fun exists_forNonExistingKey_returnsFalse() = runTest {
        assertFalse(dao.exists("non_existing_key"))
    }

    @Test
    fun insert_withSameKey_replacesWithoutError() = runTest {
        val ride1 = createTestRide(rideKey = "same_key", price = 20.0)
        val ride2 = createTestRide(rideKey = "same_key", price = 30.0)

        dao.insert(ride1)
        dao.insert(ride2)

        assertTrue(dao.exists("same_key"))
    }

    @Test
    fun cleanupExpired_deletesRecordsWithExpiredTimestamp() = runTest {
        val now = 500_000L
        val expiredRide = createTestRide(
            rideKey = "expired",
            parsedPickupTimestamp = now - 60_000
        )
        val futureRide = createTestRide(
            rideKey = "future",
            parsedPickupTimestamp = now + 60_000
        )
        val nullTimestampRide = createTestRide(
            rideKey = "null_ts",
            parsedPickupTimestamp = null
        )

        dao.insert(expiredRide)
        dao.insert(futureRide)
        dao.insert(nullTimestampRide)

        val deletedCount = dao.cleanupExpired(now)

        assertEquals(1, deletedCount)
        assertFalse(dao.exists("expired"))
        assertTrue(dao.exists("future"))
        assertTrue(dao.exists("null_ts"))
    }

    @Test
    fun cleanupOld_deletesOldRecordsWithNullTimestamp() = runTest {
        val cutoff = 100_000L

        val oldNullRide = createTestRide(
            rideKey = "old_null",
            addedAt = cutoff - 1000,
            parsedPickupTimestamp = null
        )
        val recentNullRide = createTestRide(
            rideKey = "recent_null",
            addedAt = cutoff + 1000,
            parsedPickupTimestamp = null
        )
        val oldWithTimestampRide = createTestRide(
            rideKey = "old_with_ts",
            addedAt = cutoff - 1000,
            parsedPickupTimestamp = 999_999L
        )

        dao.insert(oldNullRide)
        dao.insert(recentNullRide)
        dao.insert(oldWithTimestampRide)

        val deletedCount = dao.cleanupOld(cutoff)

        assertEquals(1, deletedCount)
        assertFalse(dao.exists("old_null"))
        assertTrue(dao.exists("recent_null"))
        assertTrue(dao.exists("old_with_ts"))
    }

    @Test
    fun cleanupExpired_returnsZero_whenNoExpiredRecords() = runTest {
        val now = 500_000L
        dao.insert(createTestRide(rideKey = "future", parsedPickupTimestamp = now + 60_000))
        dao.insert(createTestRide(rideKey = "null_ts", parsedPickupTimestamp = null))

        val deletedCount = dao.cleanupExpired(now)

        assertEquals(0, deletedCount)
        assertTrue(dao.exists("future"))
        assertTrue(dao.exists("null_ts"))
    }

    @Test
    fun cleanupOld_returnsZero_whenNoOldRecords() = runTest {
        val cutoff = 100_000L
        dao.insert(createTestRide(rideKey = "recent_null", addedAt = cutoff + 1000, parsedPickupTimestamp = null))
        dao.insert(createTestRide(rideKey = "old_with_ts", addedAt = cutoff - 1000, parsedPickupTimestamp = 999_999L))

        val deletedCount = dao.cleanupOld(cutoff)

        assertEquals(0, deletedCount)
        assertTrue(dao.exists("recent_null"))
        assertTrue(dao.exists("old_with_ts"))
    }

    @Test
    fun cleanupExpired_deletesMultipleExpiredRecords() = runTest {
        val now = 500_000L
        dao.insert(createTestRide(rideKey = "expired1", parsedPickupTimestamp = now - 10_000))
        dao.insert(createTestRide(rideKey = "expired2", parsedPickupTimestamp = now - 20_000))
        dao.insert(createTestRide(rideKey = "expired3", parsedPickupTimestamp = now - 30_000))
        dao.insert(createTestRide(rideKey = "future", parsedPickupTimestamp = now + 60_000))

        val deletedCount = dao.cleanupExpired(now)

        assertEquals(3, deletedCount)
        assertFalse(dao.exists("expired1"))
        assertFalse(dao.exists("expired2"))
        assertFalse(dao.exists("expired3"))
        assertTrue(dao.exists("future"))
    }

    @Test
    fun cleanup_combinedScenario_returnsCorrectCounts() = runTest {
        val now = 1_000_000L
        val cutoff48h = now - 48 * 60 * 60 * 1000L

        // Expired: parsedPickupTimestamp in the past
        dao.insert(createTestRide(rideKey = "expired1", parsedPickupTimestamp = now - 60_000, addedAt = now))
        dao.insert(createTestRide(rideKey = "expired2", parsedPickupTimestamp = now - 120_000, addedAt = now))
        // Future: parsedPickupTimestamp in the future — should survive both cleanups
        dao.insert(createTestRide(rideKey = "future", parsedPickupTimestamp = now + 3_600_000, addedAt = now))
        // Old with null timestamp: addedAt before 48h cutoff — cleanupOld target
        dao.insert(createTestRide(rideKey = "old_null1", parsedPickupTimestamp = null, addedAt = cutoff48h - 1000))
        dao.insert(createTestRide(rideKey = "old_null2", parsedPickupTimestamp = null, addedAt = cutoff48h - 2000))
        // Recent with null timestamp: addedAt after 48h cutoff — should survive
        dao.insert(createTestRide(rideKey = "recent_null", parsedPickupTimestamp = null, addedAt = now))
        // Old with valid timestamp: should only be affected by cleanupExpired, not cleanupOld
        dao.insert(createTestRide(rideKey = "old_with_ts", parsedPickupTimestamp = now + 60_000, addedAt = cutoff48h - 5000))

        val expiredCount = dao.cleanupExpired(now)
        val oldCount = dao.cleanupOld(cutoff48h)

        assertEquals(2, expiredCount)
        assertEquals(2, oldCount)

        assertFalse(dao.exists("expired1"))
        assertFalse(dao.exists("expired2"))
        assertTrue(dao.exists("future"))
        assertFalse(dao.exists("old_null1"))
        assertFalse(dao.exists("old_null2"))
        assertTrue(dao.exists("recent_null"))
        assertTrue(dao.exists("old_with_ts"))
    }

    @Test
    fun clearAll_removesAllRecords() = runTest {
        dao.insert(createTestRide(rideKey = "ride_1"))
        dao.insert(createTestRide(rideKey = "ride_2"))
        dao.insert(createTestRide(rideKey = "ride_3"))

        dao.clearAll()

        assertFalse(dao.exists("ride_1"))
        assertFalse(dao.exists("ride_2"))
        assertFalse(dao.exists("ride_3"))
    }
}
