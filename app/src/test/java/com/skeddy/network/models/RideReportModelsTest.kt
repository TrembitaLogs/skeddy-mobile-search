package com.skeddy.network.models

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RideReportModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    // --- Test 1: RideReportRequest serialization with full PRD example data ---

    @Test
    fun `RideReportRequest serializes with full ride data from PRD example`() {
        val request = RideReportRequest(
            idempotencyKey = "550e8400-e29b-41d4-a716-446655440000",
            eventType = "ACCEPTED",
            rideHash = "c76966d3fb9c",
            timezone = "America/New_York",
            rideData = RideData(
                price = 25.50,
                pickupTime = "Tomorrow \u00b7 6:05AM",
                pickupLocation = "Maida Ter & Maida Way",
                dropoffLocation = "East Rd & Leonardville Rd",
                duration = "9 min",
                distance = "3.6 mi",
                riderName = "Kathleen"
            )
        )

        val jsonString = json.encodeToString(request)
        val deserialized = json.decodeFromString<RideReportRequest>(jsonString)

        assertEquals(request, deserialized)
        assertEquals(25.50, deserialized.rideData.price, 0.001)
        assertEquals("Tomorrow \u00b7 6:05AM", deserialized.rideData.pickupTime)
        assertEquals("Maida Ter & Maida Way", deserialized.rideData.pickupLocation)
        assertEquals("East Rd & Leonardville Rd", deserialized.rideData.dropoffLocation)
        assertEquals("9 min", deserialized.rideData.duration)
        assertEquals("3.6 mi", deserialized.rideData.distance)
        assertEquals("Kathleen", deserialized.rideData.riderName)
    }

    // --- Test 2: snake_case verification for all fields ---

    @Test
    fun `RideReportRequest serializes all fields with correct snake_case names`() {
        val request = RideReportRequest(
            idempotencyKey = "test-uuid",
            eventType = "ACCEPTED",
            rideHash = "abc123",
            timezone = "America/Chicago",
            rideData = RideData(
                price = 20.0,
                pickupTime = "Today \u00b7 3:00PM",
                pickupLocation = "Start St",
                dropoffLocation = "End Ave",
                duration = "5 min",
                distance = "2.0 mi",
                riderName = "John"
            )
        )

        val jsonString = json.encodeToString(request)
        val jsonObject = json.decodeFromString<JsonObject>(jsonString)

        assertTrue("Should have idempotency_key", jsonObject.containsKey("idempotency_key"))
        assertTrue("Should have event_type", jsonObject.containsKey("event_type"))
        assertTrue("Should have ride_hash", jsonObject.containsKey("ride_hash"))
        assertTrue("Should have timezone", jsonObject.containsKey("timezone"))
        assertTrue("Should have ride_data", jsonObject.containsKey("ride_data"))

        val rideData = json.decodeFromString<JsonObject>(jsonObject["ride_data"].toString())
        assertTrue("Should have pickup_time", rideData.containsKey("pickup_time"))
        assertTrue("Should have pickup_location", rideData.containsKey("pickup_location"))
        assertTrue("Should have dropoff_location", rideData.containsKey("dropoff_location"))
        assertTrue("Should have rider_name", rideData.containsKey("rider_name"))
        assertTrue("Should have price", rideData.containsKey("price"))
        assertTrue("Should have duration", rideData.containsKey("duration"))
        assertTrue("Should have distance", rideData.containsKey("distance"))

        // Verify camelCase keys are NOT present
        assertTrue("Should NOT have camelCase keys",
            !jsonObject.containsKey("idempotencyKey") &&
            !jsonObject.containsKey("eventType") &&
            !jsonObject.containsKey("rideHash") &&
            !jsonObject.containsKey("rideData") &&
            !rideData.containsKey("pickupTime") &&
            !rideData.containsKey("pickupLocation") &&
            !rideData.containsKey("dropoffLocation") &&
            !rideData.containsKey("riderName")
        )
    }

    // --- Test 3: RideReportResponse deserialization with ok=true and ride_id (201 new ride) ---

    @Test
    fun `RideReportResponse deserializes with ok true and ride_id for new ride (201)`() {
        val responseJson = """
            {
              "ok": true,
              "ride_id": "some-uuid-value"
            }
        """.trimIndent()

        val response = json.decodeFromString<RideReportResponse>(responseJson)

        assertTrue(response.ok)
        assertNotNull(response.rideId)
        assertEquals("some-uuid-value", response.rideId)
    }

    // --- Test 4: RideReportResponse for idempotent replay (200 with existing ride_id) ---

    @Test
    fun `RideReportResponse deserializes for idempotent replay with existing ride_id (200)`() {
        val responseJson = """
            {
              "ok": true,
              "ride_id": "existing-ride-uuid"
            }
        """.trimIndent()

        val response = json.decodeFromString<RideReportResponse>(responseJson)

        assertTrue(response.ok)
        assertNotNull(response.rideId)
        assertEquals("existing-ride-uuid", response.rideId)
    }

    // --- Test 5: idempotency_key UUID format ---

    @Test
    fun `RideReportRequest idempotency_key serializes as valid UUID string`() {
        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        val request = RideReportRequest(
            idempotencyKey = uuid,
            eventType = "ACCEPTED",
            rideHash = "somehash",
            timezone = "America/New_York",
            rideData = RideData(
                price = 15.0,
                pickupTime = "Today \u00b7 1:00PM",
                pickupLocation = "A St",
                dropoffLocation = "B Ave",
                duration = "4 min",
                distance = "1.5 mi",
                riderName = "Alice"
            )
        )

        val jsonString = json.encodeToString(request)
        val jsonObject = json.decodeFromString<JsonObject>(jsonString)
        val serializedKey = jsonObject["idempotency_key"]?.jsonPrimitive?.content

        assertEquals(uuid, serializedKey)

        val uuidRegex = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        assertTrue("idempotency_key should match UUID v4 format", uuidRegex.matches(serializedKey!!))
    }

    // --- Test 6: Defensive — JSON without ride_id field → rideId == null ---

    @Test
    fun `RideReportResponse rideId is null when ride_id field absent in JSON`() {
        val responseJson = """
            {
              "ok": true
            }
        """.trimIndent()

        val response = json.decodeFromString<RideReportResponse>(responseJson)

        assertTrue(response.ok)
        assertNull(response.rideId)
    }

    // --- Full API contract round-trip ---

    @Test
    fun `RideReportRequest matches API contract JSON structure`() {
        val apiContractJson = """
            {
              "idempotency_key": "client-generated-uuid",
              "event_type": "ACCEPTED",
              "ride_hash": "c76966d3fb9c",
              "timezone": "America/New_York",
              "ride_data": {
                "price": 25.50,
                "pickup_time": "Tomorrow \u00b7 6:05AM",
                "pickup_location": "Maida Ter & Maida Way",
                "dropoff_location": "East Rd & Leonardville Rd",
                "duration": "9 min",
                "distance": "3.6 mi",
                "rider_name": "Kathleen"
              }
            }
        """.trimIndent()

        val request = json.decodeFromString<RideReportRequest>(apiContractJson)

        assertEquals("client-generated-uuid", request.idempotencyKey)
        assertEquals("ACCEPTED", request.eventType)
        assertEquals("c76966d3fb9c", request.rideHash)
        assertEquals("America/New_York", request.timezone)
        assertEquals(25.50, request.rideData.price, 0.001)
        assertEquals("Tomorrow \u00b7 6:05AM", request.rideData.pickupTime)
        assertEquals("Maida Ter & Maida Way", request.rideData.pickupLocation)
        assertEquals("East Rd & Leonardville Rd", request.rideData.dropoffLocation)
        assertEquals("9 min", request.rideData.duration)
        assertEquals("3.6 mi", request.rideData.distance)
        assertEquals("Kathleen", request.rideData.riderName)

        val reserialized = json.encodeToString(request)
        val reParsed = json.decodeFromString<RideReportRequest>(reserialized)
        assertEquals(request, reParsed)
    }
}
