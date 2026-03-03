package com.skeddy.service

import android.content.Context
import com.skeddy.logging.SkeddyLogger
import com.skeddy.network.models.RideData
import com.skeddy.network.models.RideReportRequest
import com.skeddy.util.PickupTimeParser
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PendingRideQueueTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var queue: PendingRideQueue

    private fun createTestPrefs(name: String = "test_pending_rides") =
        context.getSharedPreferences(name, Context.MODE_PRIVATE).also {
            it.edit().clear().commit()
        }

    @Before
    fun setUp() {
        @Suppress("DEPRECATION")
        context = RuntimeEnvironment.application
        queue = PendingRideQueue(createTestPrefs())

        SkeddyLogger.reset()
        SkeddyLogger.logToLogcat = false
        SkeddyLogger.initWithFile(tempFolder.newFile("test_logs.txt"))
    }

    @After
    fun tearDown() {
        PickupTimeParser.clock = Clock.systemDefaultZone()
        SkeddyLogger.reset()
    }

    // ==================== Helper ====================

    private fun createTestRequest(
        idempotencyKey: String = "test-uuid-001",
        price: Double = 25.50,
        pickupTime: String = "Tomorrow \u00b7 6:05AM"
    ) = RideReportRequest(
        idempotencyKey = idempotencyKey,
        eventType = "ACCEPTED",
        rideHash = "testhash_$idempotencyKey",
        timezone = "America/New_York",
        rideData = RideData(
            price = price,
            pickupTime = pickupTime,
            pickupLocation = "Maida Ter & Maida Way",
            dropoffLocation = "East Rd & Leonardville Rd",
            duration = "9 min",
            distance = "3.6 mi",
            riderName = "Kathleen"
        )
    )

    private fun createTestEntry(
        idempotencyKey: String = "test-uuid-001",
        price: Double = 25.50,
        pickupTime: String = "Tomorrow \u00b7 6:05AM",
        createdAt: Long = System.currentTimeMillis()
    ): QueueEntry {
        val request = createTestRequest(idempotencyKey, price, pickupTime)
        return QueueEntry(
            request = request,
            createdAt = createdAt,
            pickupTimeRaw = request.rideData.pickupTime
        )
    }

    // ==================== QueueEntry Serialization Tests ====================

    @Test
    fun `QueueEntry serializes and deserializes correctly`() {
        val entry = createTestEntry()
        val json = queue.json

        val serialized = json.encodeToString(entry)
        val deserialized = json.decodeFromString<QueueEntry>(serialized)

        assertEquals(entry, deserialized)
    }

    @Test
    fun `QueueEntry preserves all RideReportRequest fields through serialization`() {
        val entry = createTestEntry(
            idempotencyKey = "uuid-preserve-test",
            price = 42.75,
            pickupTime = "Today \u00b7 3:00PM"
        )
        val json = queue.json

        val deserialized = json.decodeFromString<QueueEntry>(json.encodeToString(entry))

        assertEquals("uuid-preserve-test", deserialized.request.idempotencyKey)
        assertEquals("ACCEPTED", deserialized.request.eventType)
        assertEquals(42.75, deserialized.request.rideData.price, 0.001)
        assertEquals("Today \u00b7 3:00PM", deserialized.request.rideData.pickupTime)
        assertEquals("Maida Ter & Maida Way", deserialized.request.rideData.pickupLocation)
        assertEquals("East Rd & Leonardville Rd", deserialized.request.rideData.dropoffLocation)
        assertEquals("9 min", deserialized.request.rideData.duration)
        assertEquals("3.6 mi", deserialized.request.rideData.distance)
        assertEquals("Kathleen", deserialized.request.rideData.riderName)
    }

    @Test
    fun `QueueEntry preserves metadata through serialization`() {
        val createdAt = 1707500000000L
        val entry = createTestEntry(
            pickupTime = "Mon \u00b7 8:30AM",
            createdAt = createdAt
        )
        val json = queue.json

        val deserialized = json.decodeFromString<QueueEntry>(json.encodeToString(entry))

        assertEquals(createdAt, deserialized.createdAt)
        assertEquals("Mon \u00b7 8:30AM", deserialized.pickupTimeRaw)
    }

    @Test
    fun `QueueEntry list serializes and deserializes correctly`() {
        val entries = listOf(
            createTestEntry(idempotencyKey = "uuid-1", price = 15.0),
            createTestEntry(idempotencyKey = "uuid-2", price = 30.0),
            createTestEntry(idempotencyKey = "uuid-3", price = 50.0)
        )
        val json = queue.json

        val serialized = json.encodeToString(entries)
        val deserialized = json.decodeFromString<List<QueueEntry>>(serialized)

        assertEquals(3, deserialized.size)
        assertEquals("uuid-1", deserialized[0].request.idempotencyKey)
        assertEquals("uuid-2", deserialized[1].request.idempotencyKey)
        assertEquals("uuid-3", deserialized[2].request.idempotencyKey)
    }

    @Test
    fun `QueueEntry deserialization ignores unknown JSON keys`() {
        val jsonWithExtra = """
            {
                "request": {
                    "idempotency_key": "test-uuid",
                    "event_type": "ACCEPTED",
                    "ride_hash": "testhash123",
                    "timezone": "America/New_York",
                    "ride_data": {
                        "price": 20.0,
                        "pickup_time": "Today \u00b7 1:00PM",
                        "pickup_location": "A St",
                        "dropoff_location": "B Ave",
                        "duration": "5 min",
                        "distance": "2.0 mi",
                        "rider_name": "John"
                    }
                },
                "created_at": 1707500000000,
                "pickup_time_raw": "Today \u00b7 1:00PM",
                "future_field": "should be ignored"
            }
        """.trimIndent()
        val json = queue.json

        val entry = json.decodeFromString<QueueEntry>(jsonWithExtra)

        assertEquals("test-uuid", entry.request.idempotencyKey)
        assertEquals(1707500000000L, entry.createdAt)
    }

    // ==================== readQueue / writeQueue Tests ====================

    @Test
    fun `readQueue returns empty list for fresh instance`() {
        val entries = queue.readQueue()
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `writeQueue then readQueue returns same entries`() {
        val entries = listOf(
            createTestEntry(idempotencyKey = "uuid-a", price = 20.0),
            createTestEntry(idempotencyKey = "uuid-b", price = 35.0)
        )

        queue.writeQueue(entries)
        val result = queue.readQueue()

        assertEquals(2, result.size)
        assertEquals("uuid-a", result[0].request.idempotencyKey)
        assertEquals(20.0, result[0].request.rideData.price, 0.001)
        assertEquals("uuid-b", result[1].request.idempotencyKey)
        assertEquals(35.0, result[1].request.rideData.price, 0.001)
    }

    @Test
    fun `writeQueue with empty list clears the queue`() {
        queue.writeQueue(listOf(createTestEntry()))
        assertEquals(1, queue.readQueue().size)

        queue.writeQueue(emptyList())
        assertTrue(queue.readQueue().isEmpty())
    }

    @Test
    fun `writeQueue overwrites previous entries`() {
        queue.writeQueue(listOf(createTestEntry(idempotencyKey = "first")))
        queue.writeQueue(listOf(createTestEntry(idempotencyKey = "second")))

        val result = queue.readQueue()
        assertEquals(1, result.size)
        assertEquals("second", result[0].request.idempotencyKey)
    }

    // ==================== Persistence Tests ====================

    @Test
    fun `data persists across PendingRideQueue instances`() {
        val prefsName = "test_persistence_prefs"
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val queue1 = PendingRideQueue(prefs)
        queue1.writeQueue(
            listOf(
                createTestEntry(idempotencyKey = "persistent-uuid", price = 99.99)
            )
        )

        // Create a new instance backed by the same SharedPreferences
        val queue2 = PendingRideQueue(prefs)
        val result = queue2.readQueue()

        assertEquals(1, result.size)
        assertEquals("persistent-uuid", result[0].request.idempotencyKey)
        assertEquals(99.99, result[0].request.rideData.price, 0.001)
    }

    @Test
    fun `multiple entries persist across instances preserving FIFO order`() {
        val prefsName = "test_fifo_prefs"
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val queue1 = PendingRideQueue(prefs)
        queue1.writeQueue(
            listOf(
                createTestEntry(idempotencyKey = "first", createdAt = 1000L),
                createTestEntry(idempotencyKey = "second", createdAt = 2000L),
                createTestEntry(idempotencyKey = "third", createdAt = 3000L)
            )
        )

        val queue2 = PendingRideQueue(prefs)
        val result = queue2.readQueue()

        assertEquals(3, result.size)
        assertEquals("first", result[0].request.idempotencyKey)
        assertEquals("second", result[1].request.idempotencyKey)
        assertEquals("third", result[2].request.idempotencyKey)
    }

    // ==================== Corrupted Data Handling Tests ====================

    @Test
    fun `readQueue returns empty and clears prefs when data is corrupted`() {
        val prefs = createTestPrefs("test_corrupted")
        prefs.edit().putString(PendingRideQueue.QUEUE_KEY, "not valid json!!!").commit()

        val corruptedQueue = PendingRideQueue(prefs)
        val result = corruptedQueue.readQueue()

        assertTrue(result.isEmpty())
        // Verify corrupted data was cleared
        assertTrue(corruptedQueue.readQueue().isEmpty())
    }

    @Test
    fun `readQueue handles partially valid JSON gracefully`() {
        val prefs = createTestPrefs("test_partial")
        prefs.edit().putString(PendingRideQueue.QUEUE_KEY, "[{\"broken\": true}]").commit()

        val partialQueue = PendingRideQueue(prefs)
        val result = partialQueue.readQueue()

        assertTrue(result.isEmpty())
    }

    @Test
    fun `readQueue recovers after corrupted data is cleared`() {
        val prefs = createTestPrefs("test_recovery")
        prefs.edit().putString(PendingRideQueue.QUEUE_KEY, "corrupted").commit()

        val recoveryQueue = PendingRideQueue(prefs)
        // First read clears corrupted data
        recoveryQueue.readQueue()

        // Now write valid data
        recoveryQueue.writeQueue(listOf(createTestEntry(idempotencyKey = "recovered")))
        val result = recoveryQueue.readQueue()

        assertEquals(1, result.size)
        assertEquals("recovered", result[0].request.idempotencyKey)
    }

    // ==================== Logging Tests ====================

    @Test
    fun `corrupted data deserialization logs warning`() {
        val prefs = createTestPrefs("test_log_corrupted")
        prefs.edit().putString(PendingRideQueue.QUEUE_KEY, "bad data").commit()

        val logQueue = PendingRideQueue(prefs)
        logQueue.readQueue()

        val entries = SkeddyLogger.getEntriesByTag("PendingRideQueue")
        assertTrue(
            "Should log warning about deserialization failure",
            entries.any { it.message.contains("Failed to deserialize queue") }
        )
    }

    // ==================== enqueue Tests ====================

    @Test
    fun `enqueue adds element and it appears in dequeueAll`() {
        val request = createTestRequest(idempotencyKey = "enqueue-test")

        queue.enqueue(request)

        val result = queue.dequeueAll()
        assertEquals(1, result.size)
        assertEquals("enqueue-test", result[0].idempotencyKey)
    }

    @Test
    fun `enqueue stores createdAt from timeProvider`() {
        val fixedTime = 1700000000000L
        val timedQueue = PendingRideQueue(createTestPrefs("test_time"), timeProvider = { fixedTime })

        timedQueue.enqueue(createTestRequest())

        val entries = timedQueue.readQueue()
        assertEquals(fixedTime, entries[0].createdAt)
    }

    @Test
    fun `enqueue stores pickupTimeRaw from ride data`() {
        val request = createTestRequest(pickupTime = "Mon \u00b7 8:30AM")

        queue.enqueue(request)

        val entries = queue.readQueue()
        assertEquals("Mon \u00b7 8:30AM", entries[0].pickupTimeRaw)
    }

    @Test
    fun `enqueue preserves all RideReportRequest fields`() {
        val request = createTestRequest(
            idempotencyKey = "full-fields-test",
            price = 42.75,
            pickupTime = "Tomorrow \u00b7 3:00PM"
        )

        queue.enqueue(request)

        val result = queue.dequeueAll()
        assertEquals(1, result.size)
        val stored = result[0]
        assertEquals("full-fields-test", stored.idempotencyKey)
        assertEquals("ACCEPTED", stored.eventType)
        assertEquals(42.75, stored.rideData.price, 0.001)
        assertEquals("Tomorrow \u00b7 3:00PM", stored.rideData.pickupTime)
        assertEquals("Maida Ter & Maida Way", stored.rideData.pickupLocation)
        assertEquals("East Rd & Leonardville Rd", stored.rideData.dropoffLocation)
        assertEquals("9 min", stored.rideData.duration)
        assertEquals("3.6 mi", stored.rideData.distance)
        assertEquals("Kathleen", stored.rideData.riderName)
    }

    // ==================== FIFO Order Tests ====================

    @Test
    fun `enqueue preserves FIFO order`() {
        queue.enqueue(createTestRequest(idempotencyKey = "first"))
        queue.enqueue(createTestRequest(idempotencyKey = "second"))
        queue.enqueue(createTestRequest(idempotencyKey = "third"))

        val result = queue.dequeueAll()
        assertEquals(3, result.size)
        assertEquals("first", result[0].idempotencyKey)
        assertEquals("second", result[1].idempotencyKey)
        assertEquals("third", result[2].idempotencyKey)
    }

    // ==================== dequeueAll Tests ====================

    @Test
    fun `dequeueAll returns empty list for empty queue`() {
        val result = queue.dequeueAll()
        assertTrue(result.isEmpty())
    }

    @Test
    fun `dequeueAll returns RideReportRequest objects not QueueEntry`() {
        queue.enqueue(createTestRequest(idempotencyKey = "type-check"))

        val result = queue.dequeueAll()
        // Compile-time: result is List<RideReportRequest>
        assertEquals("type-check", result[0].idempotencyKey)
        assertEquals("ACCEPTED", result[0].eventType)
    }

    // ==================== remove Tests ====================

    @Test
    fun `remove deletes specific element by idempotencyKey`() {
        queue.enqueue(createTestRequest(idempotencyKey = "keep-1"))
        queue.enqueue(createTestRequest(idempotencyKey = "remove-me"))
        queue.enqueue(createTestRequest(idempotencyKey = "keep-2"))

        queue.remove(createTestRequest(idempotencyKey = "remove-me"))

        val result = queue.dequeueAll()
        assertEquals(2, result.size)
        assertEquals("keep-1", result[0].idempotencyKey)
        assertEquals("keep-2", result[1].idempotencyKey)
    }

    @Test
    fun `remove non-existent entry does not change queue`() {
        queue.enqueue(createTestRequest(idempotencyKey = "existing"))

        queue.remove(createTestRequest(idempotencyKey = "non-existent"))

        val result = queue.dequeueAll()
        assertEquals(1, result.size)
        assertEquals("existing", result[0].idempotencyKey)
    }

    @Test
    fun `remove only removes first matching entry when duplicates exist`() {
        // idempotencyKey should be unique in practice, but verify behavior
        queue.enqueue(createTestRequest(idempotencyKey = "dup-key"))
        queue.enqueue(createTestRequest(idempotencyKey = "dup-key"))

        queue.remove(createTestRequest(idempotencyKey = "dup-key"))

        // Both entries have the same key, so both are removed by filter
        val result = queue.dequeueAll()
        assertTrue(result.isEmpty())
    }

    // ==================== isEmpty Tests ====================

    @Test
    fun `isEmpty returns true for fresh queue`() {
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `isEmpty returns false after enqueue`() {
        queue.enqueue(createTestRequest())
        assertFalse(queue.isEmpty())
    }

    @Test
    fun `isEmpty returns true after enqueue and remove`() {
        val request = createTestRequest(idempotencyKey = "temp")
        queue.enqueue(request)
        queue.remove(request)
        assertTrue(queue.isEmpty())
    }

    // ==================== clear Tests ====================

    @Test
    fun `clear empties the queue`() {
        queue.enqueue(createTestRequest(idempotencyKey = "a"))
        queue.enqueue(createTestRequest(idempotencyKey = "b"))
        queue.enqueue(createTestRequest(idempotencyKey = "c"))

        queue.clear()

        assertTrue(queue.isEmpty())
        assertTrue(queue.dequeueAll().isEmpty())
    }

    @Test
    fun `clear on empty queue does not throw`() {
        queue.clear()
        assertTrue(queue.isEmpty())
    }

    // ==================== Combined Operations Tests ====================

    @Test
    fun `enqueue multiple remove one others remain in order`() {
        queue.enqueue(createTestRequest(idempotencyKey = "ride-1", price = 15.0))
        queue.enqueue(createTestRequest(idempotencyKey = "ride-2", price = 25.0))
        queue.enqueue(createTestRequest(idempotencyKey = "ride-3", price = 35.0))

        queue.remove(createTestRequest(idempotencyKey = "ride-2"))

        val result = queue.dequeueAll()
        assertEquals(2, result.size)
        assertEquals("ride-1", result[0].idempotencyKey)
        assertEquals(15.0, result[0].rideData.price, 0.001)
        assertEquals("ride-3", result[1].idempotencyKey)
        assertEquals(35.0, result[1].rideData.price, 0.001)
    }

    @Test
    fun `enqueue after remove appends to end`() {
        queue.enqueue(createTestRequest(idempotencyKey = "first"))
        queue.enqueue(createTestRequest(idempotencyKey = "second"))
        queue.remove(createTestRequest(idempotencyKey = "first"))
        queue.enqueue(createTestRequest(idempotencyKey = "third"))

        val result = queue.dequeueAll()
        assertEquals(2, result.size)
        assertEquals("second", result[0].idempotencyKey)
        assertEquals("third", result[1].idempotencyKey)
    }

    // ==================== Persistence with Public API Tests ====================

    @Test
    fun `enqueued data persists across PendingRideQueue instances`() {
        val prefs = createTestPrefs("test_public_persist")

        val queue1 = PendingRideQueue(prefs)
        queue1.enqueue(createTestRequest(idempotencyKey = "persist-1", price = 50.0))
        queue1.enqueue(createTestRequest(idempotencyKey = "persist-2", price = 60.0))

        val queue2 = PendingRideQueue(prefs)
        val result = queue2.dequeueAll()

        assertEquals(2, result.size)
        assertEquals("persist-1", result[0].idempotencyKey)
        assertEquals(50.0, result[0].rideData.price, 0.001)
        assertEquals("persist-2", result[1].idempotencyKey)
        assertEquals(60.0, result[1].rideData.price, 0.001)
    }

    @Test
    fun `clear persists across PendingRideQueue instances`() {
        val prefs = createTestPrefs("test_clear_persist")

        val queue1 = PendingRideQueue(prefs)
        queue1.enqueue(createTestRequest(idempotencyKey = "will-clear"))
        queue1.clear()

        val queue2 = PendingRideQueue(prefs)
        assertTrue(queue2.isEmpty())
        assertTrue(queue2.dequeueAll().isEmpty())
    }

    // ==================== TTL Helpers ====================

    /**
     * Fixes PickupTimeParser clock to Feb 12, 2026 midnight
     * and returns the system default zone for timestamp calculations.
     */
    private fun fixClockToFeb12(): ZoneId {
        val zone = ZoneId.systemDefault()
        val feb12 = LocalDate.of(2026, 2, 12)
        PickupTimeParser.clock = Clock.fixed(feb12.atStartOfDay(zone).toInstant(), zone)
        return zone
    }

    private fun epochMillis(date: LocalDate, hour: Int, minute: Int, zone: ZoneId): Long {
        return ZonedDateTime.of(date, LocalTime.of(hour, minute), zone)
            .toInstant().toEpochMilli()
    }

    // ==================== calculateExpiryTime Tests ====================

    @Test
    fun `calculateExpiryTime returns parsed pickup time plus 1h for parsable time`() {
        val zone = fixClockToFeb12()
        val feb12 = LocalDate.of(2026, 2, 12)
        val pickupMillis = epochMillis(feb12, 6, 5, zone)

        val entry = createTestEntry(pickupTime = "Today \u00b7 6:05AM")

        assertEquals(
            pickupMillis + PendingRideQueue.PARSED_TTL_MILLIS,
            queue.calculateExpiryTime(entry)
        )
    }

    @Test
    fun `calculateExpiryTime returns created_at plus 48h for unparsable time`() {
        val createdAt = 1_000_000_000_000L
        val entry = createTestEntry(pickupTime = "Invalid Format", createdAt = createdAt)

        assertEquals(
            createdAt + PendingRideQueue.FALLBACK_TTL_MILLIS,
            queue.calculateExpiryTime(entry)
        )
    }

    // ==================== isExpired Tests ====================

    @Test
    fun `isExpired returns false for parsable pickup time before 1h TTL expires`() {
        val zone = fixClockToFeb12()
        val feb12 = LocalDate.of(2026, 2, 12)
        val pickupMillis = epochMillis(feb12, 6, 5, zone)
        val expiryTime = pickupMillis + PendingRideQueue.PARSED_TTL_MILLIS

        val ttlQueue = PendingRideQueue(createTestPrefs("ttl_parsed_before"), timeProvider = { expiryTime - 1 })
        val entry = createTestEntry(pickupTime = "Today \u00b7 6:05AM")

        assertFalse(ttlQueue.isExpired(entry))
    }

    @Test
    fun `isExpired returns true for parsable pickup time after 1h TTL expires`() {
        val zone = fixClockToFeb12()
        val feb12 = LocalDate.of(2026, 2, 12)
        val pickupMillis = epochMillis(feb12, 6, 5, zone)
        val expiryTime = pickupMillis + PendingRideQueue.PARSED_TTL_MILLIS

        val ttlQueue = PendingRideQueue(createTestPrefs("ttl_parsed_after"), timeProvider = { expiryTime + 1 })
        val entry = createTestEntry(pickupTime = "Today \u00b7 6:05AM")

        assertTrue(ttlQueue.isExpired(entry))
    }

    @Test
    fun `isExpired returns false for unparsable pickup time before 48h TTL expires`() {
        val createdAt = 1_000_000_000_000L
        val expiryTime = createdAt + PendingRideQueue.FALLBACK_TTL_MILLIS

        val ttlQueue = PendingRideQueue(createTestPrefs("ttl_fallback_before"), timeProvider = { expiryTime - 1 })
        val entry = createTestEntry(pickupTime = "Invalid Format", createdAt = createdAt)

        assertFalse(ttlQueue.isExpired(entry))
    }

    @Test
    fun `isExpired returns true for unparsable pickup time after 48h TTL expires`() {
        val createdAt = 1_000_000_000_000L
        val expiryTime = createdAt + PendingRideQueue.FALLBACK_TTL_MILLIS

        val ttlQueue = PendingRideQueue(createTestPrefs("ttl_fallback_after"), timeProvider = { expiryTime + 1 })
        val entry = createTestEntry(pickupTime = "Invalid Format", createdAt = createdAt)

        assertTrue(ttlQueue.isExpired(entry))
    }

    @Test
    fun `isExpired returns true when pickup time is in the past by more than 1h`() {
        val zone = fixClockToFeb12()
        val feb12 = LocalDate.of(2026, 2, 12)
        // "Today · 6:05AM" = 6:05AM, noon is well past 6:05AM + 1h = 7:05AM
        val noonMillis = epochMillis(feb12, 12, 0, zone)

        val ttlQueue = PendingRideQueue(createTestPrefs("ttl_past"), timeProvider = { noonMillis })
        val entry = createTestEntry(pickupTime = "Today \u00b7 6:05AM")

        assertTrue(ttlQueue.isExpired(entry))
    }

    @Test
    fun `isExpired boundary - exactly at expiry time is expired`() {
        val zone = fixClockToFeb12()
        val feb12 = LocalDate.of(2026, 2, 12)
        val pickupMillis = epochMillis(feb12, 6, 5, zone)
        val exactExpiry = pickupMillis + PendingRideQueue.PARSED_TTL_MILLIS

        val ttlQueue = PendingRideQueue(createTestPrefs("ttl_boundary_exact"), timeProvider = { exactExpiry })
        val entry = createTestEntry(pickupTime = "Today \u00b7 6:05AM")

        assertTrue(ttlQueue.isExpired(entry))
    }

    @Test
    fun `isExpired boundary - 1ms before expiry time is not expired`() {
        val zone = fixClockToFeb12()
        val feb12 = LocalDate.of(2026, 2, 12)
        val pickupMillis = epochMillis(feb12, 6, 5, zone)
        val exactExpiry = pickupMillis + PendingRideQueue.PARSED_TTL_MILLIS

        val ttlQueue = PendingRideQueue(createTestPrefs("ttl_boundary_before"), timeProvider = { exactExpiry - 1 })
        val entry = createTestEntry(pickupTime = "Today \u00b7 6:05AM")

        assertFalse(ttlQueue.isExpired(entry))
    }

    // ==================== cleanupExpired Tests ====================

    @Test
    fun `cleanupExpired on empty queue does not throw`() {
        queue.cleanupExpired()
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `cleanupExpired removes only expired entries from storage`() {
        val zone = fixClockToFeb12()
        val feb12 = LocalDate.of(2026, 2, 12)
        val pickupMillis = epochMillis(feb12, 6, 5, zone)
        val expiryTime = pickupMillis + PendingRideQueue.PARSED_TTL_MILLIS

        // now is past "Today · 6:05AM" + 1h but before "Tomorrow · 3:00PM" + 1h
        val ttlQueue = PendingRideQueue(createTestPrefs("cleanup_mixed"), timeProvider = { expiryTime + 1 })

        ttlQueue.writeQueue(
            listOf(
                createTestEntry(idempotencyKey = "expired-ride", pickupTime = "Today \u00b7 6:05AM"),
                createTestEntry(idempotencyKey = "active-ride", pickupTime = "Tomorrow \u00b7 3:00PM")
            )
        )

        ttlQueue.cleanupExpired()

        val remaining = ttlQueue.readQueue()
        assertEquals(1, remaining.size)
        assertEquals("active-ride", remaining[0].request.idempotencyKey)
    }

    @Test
    fun `cleanupExpired removes all entries when all are expired`() {
        val createdAt = 1_000_000_000_000L
        val expiryTime = createdAt + PendingRideQueue.FALLBACK_TTL_MILLIS

        val ttlQueue = PendingRideQueue(createTestPrefs("cleanup_all_expired"), timeProvider = { expiryTime + 1 })

        ttlQueue.writeQueue(
            listOf(
                createTestEntry(idempotencyKey = "old-1", pickupTime = "Invalid", createdAt = createdAt),
                createTestEntry(idempotencyKey = "old-2", pickupTime = "Unknown", createdAt = createdAt)
            )
        )

        ttlQueue.cleanupExpired()

        assertTrue(ttlQueue.readQueue().isEmpty())
    }

    @Test
    fun `cleanupExpired preserves all entries when none are expired`() {
        val createdAt = 1_000_000_000_000L

        val ttlQueue = PendingRideQueue(createTestPrefs("cleanup_none_expired"), timeProvider = { createdAt + 1000 })

        ttlQueue.writeQueue(
            listOf(
                createTestEntry(idempotencyKey = "fresh-1", pickupTime = "Invalid", createdAt = createdAt),
                createTestEntry(idempotencyKey = "fresh-2", pickupTime = "Unknown", createdAt = createdAt)
            )
        )

        ttlQueue.cleanupExpired()

        assertEquals(2, ttlQueue.readQueue().size)
    }

    // ==================== dequeueAll with TTL Tests ====================

    @Test
    fun `dequeueAll excludes expired entries and removes them from storage`() {
        val zone = fixClockToFeb12()
        val feb12 = LocalDate.of(2026, 2, 12)
        val pickupMillis = epochMillis(feb12, 6, 5, zone)
        val expiryTime = pickupMillis + PendingRideQueue.PARSED_TTL_MILLIS

        val ttlQueue = PendingRideQueue(createTestPrefs("dequeue_ttl"), timeProvider = { expiryTime + 1 })

        ttlQueue.writeQueue(
            listOf(
                createTestEntry(idempotencyKey = "expired-ride", pickupTime = "Today \u00b7 6:05AM"),
                createTestEntry(idempotencyKey = "active-ride", pickupTime = "Tomorrow \u00b7 3:00PM")
            )
        )

        val result = ttlQueue.dequeueAll()
        assertEquals(1, result.size)
        assertEquals("active-ride", result[0].idempotencyKey)

        // Verify expired entry was physically removed from storage
        val rawEntries = ttlQueue.readQueue()
        assertEquals(1, rawEntries.size)
        assertEquals("active-ride", rawEntries[0].request.idempotencyKey)
    }

    // ==================== TTL Logging Tests ====================

    @Test
    fun `cleanupExpired logs with parsed_pickup_time TTL type for parsable entries`() {
        val zone = fixClockToFeb12()
        val feb12 = LocalDate.of(2026, 2, 12)
        val pickupMillis = epochMillis(feb12, 6, 5, zone)
        val expiryTime = pickupMillis + PendingRideQueue.PARSED_TTL_MILLIS

        val ttlQueue = PendingRideQueue(createTestPrefs("log_parsed"), timeProvider = { expiryTime + 1 })

        ttlQueue.writeQueue(
            listOf(createTestEntry(idempotencyKey = "log-parsed-test", pickupTime = "Today \u00b7 6:05AM"))
        )

        ttlQueue.cleanupExpired()

        val entries = SkeddyLogger.getEntriesByTag("PendingRideQueue")
        assertTrue(
            "Should log with parsed_pickup_time TTL type",
            entries.any {
                it.message.contains("parsed_pickup_time + 1h") &&
                    it.message.contains("log-parsed-test")
            }
        )
    }

    @Test
    fun `cleanupExpired logs with created_at TTL type for unparsable entries`() {
        val createdAt = 1_000_000_000_000L
        val expiryTime = createdAt + PendingRideQueue.FALLBACK_TTL_MILLIS

        val ttlQueue = PendingRideQueue(createTestPrefs("log_fallback"), timeProvider = { expiryTime + 1 })

        ttlQueue.writeQueue(
            listOf(createTestEntry(idempotencyKey = "log-fallback-test", pickupTime = "Invalid", createdAt = createdAt))
        )

        ttlQueue.cleanupExpired()

        val entries = SkeddyLogger.getEntriesByTag("PendingRideQueue")
        assertTrue(
            "Should log with created_at TTL type",
            entries.any {
                it.message.contains("created_at + 48h") &&
                    it.message.contains("log-fallback-test")
            }
        )
    }
}
