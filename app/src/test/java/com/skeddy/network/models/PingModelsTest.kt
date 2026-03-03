package com.skeddy.network.models

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PingModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    // --- Test 1: PingRequest serialization with snake_case ---

    @Test
    fun `PingRequest serializes to JSON with correct snake_case field names`() {
        val request = PingRequest(
            timezone = "America/New_York",
            appVersion = "1.2.0",
            deviceHealth = DeviceHealth(
                accessibilityEnabled = true,
                lyftRunning = true,
                screenOn = true
            ),
            stats = PingStats(
                batchId = "test-uuid-v4",
                cyclesSinceLastPing = 1,
                ridesFound = 0,
                acceptFailures = emptyList()
            )
        )

        val jsonString = json.encodeToString(request)
        val jsonObject = json.decodeFromString<JsonObject>(jsonString)

        assertEquals("America/New_York", jsonObject["timezone"]?.jsonPrimitive?.content)
        assertEquals("1.2.0", jsonObject["app_version"]?.jsonPrimitive?.content)
        assertTrue(jsonObject.containsKey("device_health"))
        assertTrue(jsonObject.containsKey("stats"))

        val deviceHealth = json.decodeFromString<JsonObject>(jsonObject["device_health"].toString())
        assertTrue(deviceHealth.containsKey("accessibility_enabled"))
        assertTrue(deviceHealth.containsKey("lyft_running"))
        assertTrue(deviceHealth.containsKey("screen_on"))

        val stats = json.decodeFromString<JsonObject>(jsonObject["stats"].toString())
        assertTrue(stats.containsKey("batch_id"))
        assertTrue(stats.containsKey("cycles_since_last_ping"))
        assertTrue(stats.containsKey("rides_found"))
        assertTrue(stats.containsKey("accept_failures"))
    }

    // --- Test 2: PingResponse deserialization ---

    @Test
    fun `PingResponse deserializes from JSON with search active`() {
        val responseJson = """
            {
              "search": true,
              "interval_seconds": 30,
              "force_update": false,
              "filters": {
                "min_price": 20.0
              }
            }
        """.trimIndent()

        val response = json.decodeFromString<PingResponse>(responseJson)

        assertTrue(response.search)
        assertEquals(30, response.intervalSeconds)
        assertFalse(response.forceUpdate)
        assertEquals(20.0, response.filters.minPrice, 0.001)
        assertNull(response.updateUrl)
    }

    @Test
    fun `PingResponse deserializes from JSON with search stopped`() {
        val responseJson = """
            {
              "search": false,
              "interval_seconds": 60,
              "force_update": false,
              "filters": {
                "min_price": 20.0
              }
            }
        """.trimIndent()

        val response = json.decodeFromString<PingResponse>(responseJson)

        assertFalse(response.search)
        assertEquals(60, response.intervalSeconds)
        assertFalse(response.forceUpdate)
    }

    // --- Test 3: AcceptFailure with various values ---

    @Test
    fun `AcceptFailure serializes and deserializes with various values`() {
        val failure = AcceptFailure(
            reason = "AcceptButtonNotFound",
            ridePrice = 25.50,
            pickupTime = "Tomorrow · 6:05AM",
            timestamp = "2026-02-09T10:30:00Z"
        )

        val jsonString = json.encodeToString(failure)
        val deserialized = json.decodeFromString<AcceptFailure>(jsonString)

        assertEquals("AcceptButtonNotFound", deserialized.reason)
        assertEquals(25.50, deserialized.ridePrice, 0.001)
        assertEquals("Tomorrow · 6:05AM", deserialized.pickupTime)
        assertEquals("2026-02-09T10:30:00Z", deserialized.timestamp)
    }

    @Test
    fun `AcceptFailure deserializes from API contract JSON example`() {
        val failureJson = """
            {
              "reason": "AcceptButtonNotFound",
              "ride_price": 25.50,
              "pickup_time": "Tomorrow · 6:05AM",
              "timestamp": "2026-02-09T10:30:00Z"
            }
        """.trimIndent()

        val failure = json.decodeFromString<AcceptFailure>(failureJson)

        assertEquals("AcceptButtonNotFound", failure.reason)
        assertEquals(25.50, failure.ridePrice, 0.001)
        assertEquals("Tomorrow · 6:05AM", failure.pickupTime)
        assertEquals("2026-02-09T10:30:00Z", failure.timestamp)
    }

    // --- Test 4: Optional updateUrl (null and non-null) ---

    @Test
    fun `PingResponse updateUrl is null when field absent in JSON`() {
        val responseJson = """
            {
              "search": true,
              "interval_seconds": 30,
              "force_update": false,
              "filters": { "min_price": 20.0 }
            }
        """.trimIndent()

        val response = json.decodeFromString<PingResponse>(responseJson)

        assertNull(response.updateUrl)
        assertFalse(response.forceUpdate)
    }

    @Test
    fun `PingResponse updateUrl is present when force_update is true`() {
        val responseJson = """
            {
              "search": false,
              "interval_seconds": 300,
              "force_update": true,
              "update_url": "https://skeddy.net/download/search-app.apk",
              "filters": { "min_price": 20.0 }
            }
        """.trimIndent()

        val response = json.decodeFromString<PingResponse>(responseJson)

        assertTrue(response.forceUpdate)
        assertEquals("https://skeddy.net/download/search-app.apk", response.updateUrl)
        assertFalse(response.search)
        assertEquals(300, response.intervalSeconds)
    }

    // --- Test 5: Empty acceptFailures list ---

    @Test
    fun `PingRequest with empty acceptFailures list serializes correctly`() {
        val request = PingRequest(
            timezone = "America/New_York",
            appVersion = "1.2.0",
            deviceHealth = DeviceHealth(
                accessibilityEnabled = true,
                lyftRunning = true,
                screenOn = true
            ),
            stats = PingStats(
                batchId = "batch-123",
                cyclesSinceLastPing = 1,
                ridesFound = 0,
                acceptFailures = emptyList()
            )
        )

        val jsonString = json.encodeToString(request)

        assertTrue(jsonString.contains("\"accept_failures\":[]"))
    }

    @Test
    fun `PingRequest with non-empty acceptFailures list round-trips correctly`() {
        val failures = listOf(
            AcceptFailure(
                reason = "AcceptButtonNotFound",
                ridePrice = 25.50,
                pickupTime = "Tomorrow · 6:05AM",
                timestamp = "2026-02-09T10:30:00Z"
            ),
            AcceptFailure(
                reason = "Timeout",
                ridePrice = 18.00,
                pickupTime = "Today · 3:00PM",
                timestamp = "2026-02-09T11:00:00Z"
            )
        )

        val request = PingRequest(
            timezone = "America/Chicago",
            appVersion = "1.3.0",
            deviceHealth = DeviceHealth(
                accessibilityEnabled = false,
                lyftRunning = true,
                screenOn = false
            ),
            stats = PingStats(
                batchId = "batch-456",
                cyclesSinceLastPing = 3,
                ridesFound = 5,
                acceptFailures = failures
            )
        )

        val jsonString = json.encodeToString(request)
        val deserialized = json.decodeFromString<PingRequest>(jsonString)

        assertEquals(request, deserialized)
        assertEquals(2, deserialized.stats.acceptFailures.size)
        assertEquals("AcceptButtonNotFound", deserialized.stats.acceptFailures[0].reason)
        assertEquals("Timeout", deserialized.stats.acceptFailures[1].reason)
    }

    // --- Full API contract round-trip ---

    @Test
    fun `Full PingRequest matches API contract JSON structure`() {
        val apiContractJson = """
            {
              "timezone": "America/New_York",
              "app_version": "1.2.0",
              "device_health": {
                "accessibility_enabled": true,
                "lyft_running": true,
                "screen_on": true
              },
              "stats": {
                "batch_id": "uuid-v4",
                "cycles_since_last_ping": 1,
                "rides_found": 0,
                "accept_failures": []
              }
            }
        """.trimIndent()

        val request = json.decodeFromString<PingRequest>(apiContractJson)

        assertEquals("America/New_York", request.timezone)
        assertEquals("1.2.0", request.appVersion)
        assertTrue(request.deviceHealth.accessibilityEnabled)
        assertTrue(request.deviceHealth.lyftRunning)
        assertTrue(request.deviceHealth.screenOn)
        assertEquals("uuid-v4", request.stats.batchId)
        assertEquals(1, request.stats.cyclesSinceLastPing)
        assertEquals(0, request.stats.ridesFound)
        assertTrue(request.stats.acceptFailures.isEmpty())

        val reserialized = json.encodeToString(request)
        val reParsed = json.decodeFromString<PingRequest>(reserialized)
        assertEquals(request, reParsed)
    }

    // --- ride_statuses in PingRequest ---

    @Test
    fun `PingRequest with ride_statuses serializes correctly`() {
        val request = PingRequest(
            timezone = "America/New_York",
            appVersion = "1.2.0",
            deviceHealth = DeviceHealth(
                accessibilityEnabled = true,
                lyftRunning = true,
                screenOn = true
            ),
            stats = PingStats(
                batchId = "batch-rs",
                cyclesSinceLastPing = 1,
                ridesFound = 0,
                acceptFailures = emptyList()
            ),
            rideStatuses = listOf(
                RideStatusReport(rideHash = "abc123", present = true),
                RideStatusReport(rideHash = "def456", present = false)
            )
        )

        val jsonString = json.encodeToString(request)
        val jsonObject = json.decodeFromString<JsonObject>(jsonString)

        assertTrue("Should have ride_statuses", jsonObject.containsKey("ride_statuses"))
        assertFalse("Should NOT have rideStatuses camelCase", jsonObject.containsKey("rideStatuses"))

        val deserialized = json.decodeFromString<PingRequest>(jsonString)
        assertEquals(2, deserialized.rideStatuses?.size)
        assertEquals("abc123", deserialized.rideStatuses!![0].rideHash)
        assertTrue(deserialized.rideStatuses!![0].present)
        assertEquals("def456", deserialized.rideStatuses!![1].rideHash)
        assertFalse(deserialized.rideStatuses!![1].present)
    }

    @Test
    fun `PingRequest without ride_statuses defaults to null`() {
        val request = PingRequest(
            timezone = "America/New_York",
            appVersion = "1.2.0",
            deviceHealth = DeviceHealth(
                accessibilityEnabled = true,
                lyftRunning = true,
                screenOn = true
            ),
            stats = PingStats(
                batchId = "batch-no-rs",
                cyclesSinceLastPing = 0,
                ridesFound = 0,
                acceptFailures = emptyList()
            )
        )

        assertNull(request.rideStatuses)

        val jsonString = json.encodeToString(request)
        val deserialized = json.decodeFromString<PingRequest>(jsonString)
        assertNull(deserialized.rideStatuses)
    }

    @Test
    fun `RideStatusReport serializes with correct snake_case field names`() {
        val status = RideStatusReport(rideHash = "hash123", present = true)
        val jsonString = json.encodeToString(status)
        val jsonObject = json.decodeFromString<JsonObject>(jsonString)

        assertTrue("Should have ride_hash", jsonObject.containsKey("ride_hash"))
        assertTrue("Should have present", jsonObject.containsKey("present"))
        assertFalse("Should NOT have rideHash camelCase", jsonObject.containsKey("rideHash"))
    }

    // --- reason and verify_rides in PingResponse ---

    @Test
    fun `PingResponse with reason and verify_rides deserializes correctly`() {
        val responseJson = """
            {
              "search": false,
              "interval_seconds": 60,
              "force_update": false,
              "filters": { "min_price": 20.0 },
              "reason": "NO_CREDITS",
              "verify_rides": [
                {"ride_hash": "abc123"},
                {"ride_hash": "def456"}
              ]
            }
        """.trimIndent()

        val response = json.decodeFromString<PingResponse>(responseJson)

        assertFalse(response.search)
        assertEquals("NO_CREDITS", response.reason)
        assertEquals(2, response.verifyRides.size)
        assertEquals("abc123", response.verifyRides[0].rideHash)
        assertEquals("def456", response.verifyRides[1].rideHash)
    }

    @Test
    fun `PingResponse without reason and verify_rides uses safe defaults`() {
        val responseJson = """
            {
              "search": true,
              "interval_seconds": 30,
              "force_update": false,
              "filters": { "min_price": 20.0 }
            }
        """.trimIndent()

        val response = json.decodeFromString<PingResponse>(responseJson)

        assertNull(response.reason)
        assertTrue(response.verifyRides.isEmpty())
    }

    @Test
    fun `PingResponse with empty verify_rides list deserializes to empty list`() {
        val responseJson = """
            {
              "search": true,
              "interval_seconds": 30,
              "force_update": false,
              "filters": { "min_price": 20.0 },
              "verify_rides": []
            }
        """.trimIndent()

        val response = json.decodeFromString<PingResponse>(responseJson)

        assertTrue(response.verifyRides.isEmpty())
    }

    @Test
    fun `VerifyRide serializes with correct snake_case field name`() {
        val verifyRide = VerifyRide(rideHash = "hash123")
        val jsonString = json.encodeToString(verifyRide)
        val jsonObject = json.decodeFromString<JsonObject>(jsonString)

        assertTrue("Should have ride_hash", jsonObject.containsKey("ride_hash"))
        assertFalse("Should NOT have rideHash camelCase", jsonObject.containsKey("rideHash"))
    }
}
