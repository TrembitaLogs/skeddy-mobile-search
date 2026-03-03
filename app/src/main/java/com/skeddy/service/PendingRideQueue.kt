package com.skeddy.service

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.skeddy.logging.SkeddyLogger
import com.skeddy.network.models.RideReportRequest
import com.skeddy.util.PickupTimeParser
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Internal wrapper that pairs a [RideReportRequest] with metadata
 * needed for TTL calculations and queue management.
 *
 * @property request the original ride report that failed to send
 * @property createdAt epoch millis when the entry was enqueued (for fallback TTL)
 * @property pickupTimeRaw raw pickup time string from ride data (for parsed TTL)
 */
@Serializable
internal data class QueueEntry(
    @SerialName("request") val request: RideReportRequest,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("pickup_time_raw") val pickupTimeRaw: String
)

/**
 * Persistent FIFO queue for storing unsent POST /rides requests.
 *
 * Survives service restarts and device reboots by persisting to
 * EncryptedSharedPreferences with JSON serialization. The entire
 * queue is stored as a single JSON array under [QUEUE_KEY].
 *
 * The queue is expected to be minimal — search stops when server
 * connection is lost, so only rides accepted just before
 * connectivity loss will be queued.
 *
 * Thread-safety: all read-modify-write operations must be wrapped
 * in `synchronized(lock)`. The [readQueue] and [writeQueue] helpers
 * are intentionally not synchronized themselves to allow callers
 * to compose atomic operations.
 */
@Suppress("DEPRECATION")
class PendingRideQueue @VisibleForTesting internal constructor(
    private val prefs: SharedPreferences,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {

    constructor(context: Context) : this(createPreferences(context))

    /** Lock for thread-safe read-modify-write operations. */
    @VisibleForTesting
    internal val lock = Any()

    @VisibleForTesting
    internal val json = Json { ignoreUnknownKeys = true }

    /**
     * Reads the entire queue from SharedPreferences.
     * Returns an empty list if no data is stored or deserialization fails.
     *
     * Must be called within `synchronized(lock)` when used alongside [writeQueue].
     */
    @VisibleForTesting
    internal fun readQueue(): List<QueueEntry> {
        val raw = prefs.getString(QUEUE_KEY, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<QueueEntry>>(raw)
        } catch (e: Exception) {
            SkeddyLogger.w(TAG, "Failed to deserialize queue, clearing corrupted data", e)
            prefs.edit().remove(QUEUE_KEY).apply()
            emptyList()
        }
    }

    /**
     * Writes the entire queue to SharedPreferences as a JSON array.
     *
     * Must be called within `synchronized(lock)` when used alongside [readQueue].
     */
    @VisibleForTesting
    internal fun writeQueue(entries: List<QueueEntry>) {
        val raw = json.encodeToString(entries)
        prefs.edit().putString(QUEUE_KEY, raw).apply()
    }

    // ==================== Public Queue Operations ====================

    /**
     * Adds a [RideReportRequest] to the end of the queue.
     *
     * Stores the current timestamp as [QueueEntry.createdAt] for fallback TTL,
     * and extracts [RideData.pickupTime] as [QueueEntry.pickupTimeRaw] for
     * parsed TTL calculations.
     */
    fun enqueue(request: RideReportRequest) {
        synchronized(lock) {
            val entries = readQueue().toMutableList()
            entries.add(
                QueueEntry(
                    request = request,
                    createdAt = timeProvider(),
                    pickupTimeRaw = request.rideData.pickupTime
                )
            )
            writeQueue(entries)
        }
    }

    /**
     * Returns all non-expired [RideReportRequest] items in FIFO order.
     *
     * Automatically calls [cleanupExpiredInternal] first, which physically
     * removes expired entries from storage before returning results.
     */
    fun dequeueAll(): List<RideReportRequest> {
        synchronized(lock) {
            cleanupExpiredInternal()
            return readQueue().map { it.request }
        }
    }

    /**
     * Removes a specific entry by its [RideReportRequest.idempotencyKey].
     *
     * Call after successfully sending the ride report to the server.
     * If no entry matches the key, the queue remains unchanged.
     */
    fun remove(request: RideReportRequest) {
        synchronized(lock) {
            val entries = readQueue()
            val filtered = entries.filter {
                it.request.idempotencyKey != request.idempotencyKey
            }
            writeQueue(filtered)
        }
    }

    /**
     * Returns `true` if the queue contains no entries.
     */
    fun isEmpty(): Boolean {
        synchronized(lock) {
            return readQueue().isEmpty()
        }
    }

    /**
     * Removes all entries from the queue.
     *
     * Used during re-pairing to discard pending rides from the
     * previous pairing context that are no longer relevant.
     */
    fun clear() {
        synchronized(lock) {
            prefs.edit().remove(QUEUE_KEY).apply()
        }
    }

    // ==================== TTL ====================

    /**
     * Calculates the expiry time for a queue entry.
     *
     * Uses [PickupTimeParser] to parse the pickup time string. If parsing
     * succeeds, expiry is `parsedPickupTime + 1h`. Otherwise, falls back
     * to `createdAt + 48h`.
     *
     * @return epoch milliseconds when this entry expires
     */
    @VisibleForTesting
    internal fun calculateExpiryTime(entry: QueueEntry): Long {
        val parsedPickupTime = PickupTimeParser.parsePickupTimestamp(entry.pickupTimeRaw)
        return if (parsedPickupTime != null) {
            parsedPickupTime + PARSED_TTL_MILLIS
        } else {
            entry.createdAt + FALLBACK_TTL_MILLIS
        }
    }

    /**
     * Checks whether a queue entry has exceeded its time-to-live.
     *
     * TTL policy:
     * - `parsed_pickup_time + 1h` if [PickupTimeParser] can parse the pickup time
     * - `created_at + 48h` as a fallback when parsing fails
     */
    @VisibleForTesting
    internal fun isExpired(entry: QueueEntry): Boolean {
        return timeProvider() >= calculateExpiryTime(entry)
    }

    /**
     * Removes all expired entries from persistent storage.
     *
     * Call before sending pending rides to avoid transmitting stale data.
     */
    fun cleanupExpired() {
        synchronized(lock) {
            cleanupExpiredInternal()
        }
    }

    /**
     * Internal cleanup that must be called within `synchronized(lock)`.
     *
     * Partitions entries into active and expired, writes back only active
     * ones, and logs each removed entry with its TTL type for diagnostics.
     */
    private fun cleanupExpiredInternal() {
        val entries = readQueue()
        if (entries.isEmpty()) return

        val now = timeProvider()
        val active = mutableListOf<QueueEntry>()

        for (entry in entries) {
            val parsedPickupTime = PickupTimeParser.parsePickupTimestamp(entry.pickupTimeRaw)
            val expiryTime = if (parsedPickupTime != null) {
                parsedPickupTime + PARSED_TTL_MILLIS
            } else {
                entry.createdAt + FALLBACK_TTL_MILLIS
            }

            if (now >= expiryTime) {
                val ttlType = if (parsedPickupTime != null) {
                    "parsed_pickup_time + 1h"
                } else {
                    "created_at + 48h"
                }
                SkeddyLogger.d(
                    TAG,
                    "Expired entry removed: key=${entry.request.idempotencyKey}, " +
                        "ttlType=$ttlType, expiryTime=$expiryTime, now=$now"
                )
            } else {
                active.add(entry)
            }
        }

        if (active.size < entries.size) {
            writeQueue(active)
        }
    }

    companion object {
        private const val TAG = "PendingRideQueue"
        private const val PREFS_NAME = "skeddy_pending_rides"
        internal const val QUEUE_KEY = "pending_queue"

        /** TTL for entries with a successfully parsed pickup time: 1 hour after pickup. */
        internal const val PARSED_TTL_MILLIS = 3_600_000L

        /** Fallback TTL for entries where pickup time parsing failed: 48 hours after creation. */
        internal const val FALLBACK_TTL_MILLIS = 172_800_000L

        private fun createPreferences(context: Context): SharedPreferences {
            return try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: GeneralSecurityException) {
                SkeddyLogger.w(
                    TAG,
                    "Failed to create encrypted preferences, falling back to unencrypted",
                    e
                )
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            } catch (e: IOException) {
                SkeddyLogger.w(
                    TAG,
                    "Failed to create encrypted preferences, falling back to unencrypted",
                    e
                )
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
        }
    }
}
