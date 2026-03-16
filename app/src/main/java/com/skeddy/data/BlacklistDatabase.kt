package com.skeddy.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room Database for blacklisted rides used for local deduplication.
 *
 * Separate from the main AppDatabase because the blacklist has an independent
 * lifecycle — it is cleared on re-login and has its own TTL-based cleanup.
 *
 * Uses singleton pattern for thread-safe access.
 * Schema export is enabled for migration tracking.
 */
@Database(
    entities = [BlacklistedRide::class],
    version = 1,
    exportSchema = true
)
abstract class BlacklistDatabase : RoomDatabase() {

    abstract fun blacklistDao(): BlacklistDao

    companion object {
        private const val DATABASE_NAME = "skeddy_blacklist_database"

        @Volatile
        private var INSTANCE: BlacklistDatabase? = null

        /**
         * Returns the singleton instance of BlacklistDatabase.
         * Thread-safe implementation using double-checked locking.
         *
         * @param context Application context (will use applicationContext internally)
         * @return The singleton BlacklistDatabase instance
         */
        fun getInstance(context: Context): BlacklistDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): BlacklistDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                BlacklistDatabase::class.java,
                DATABASE_NAME
            )
                .fallbackToDestructiveMigration(true)
                .build()
        }
    }
}
