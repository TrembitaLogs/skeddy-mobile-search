package com.skeddy.network.models

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchLoginDeviceOverrideModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    // --- Test 1: SearchLoginRequest serialization with snake_case for device_id ---

    @Test
    fun `SearchLoginRequest serializes to JSON with snake_case device_id`() {
        val request = SearchLoginRequest(
            email = "user@example.com",
            password = "password123",
            deviceId = "android_device_unique_id",
            timezone = "America/New_York"
        )

        val jsonString = json.encodeToString(request)
        val jsonObject = json.decodeFromString<JsonObject>(jsonString)

        assertEquals("user@example.com", jsonObject["email"]?.jsonPrimitive?.content)
        assertEquals("password123", jsonObject["password"]?.jsonPrimitive?.content)
        assertEquals("android_device_unique_id", jsonObject["device_id"]?.jsonPrimitive?.content)
        assertEquals("America/New_York", jsonObject["timezone"]?.jsonPrimitive?.content)
        assertFalse("Should use snake_case, not camelCase", jsonObject.containsKey("deviceId"))
    }

    // --- Test 2: SearchLoginResponse deserialization with device_token and user_id ---

    @Test
    fun `SearchLoginResponse deserializes device_token and user_id from snake_case JSON`() {
        val responseJson = """
            {
              "device_token": "long_lived_token_string",
              "user_id": "some-uuid-value"
            }
        """.trimIndent()

        val response = json.decodeFromString<SearchLoginResponse>(responseJson)

        assertEquals("long_lived_token_string", response.deviceToken)
        assertEquals("some-uuid-value", response.userId)
    }

    // --- Test 3: SearchLoginResponse parses JSON from API contract ---

    @Test
    fun `SearchLoginResponse correctly parses API contract JSON example`() {
        val apiContractJson = """
            {
              "device_token": "long_lived_token_string",
              "user_id": "uuid"
            }
        """.trimIndent()

        val response = json.decodeFromString<SearchLoginResponse>(apiContractJson)

        assertEquals("long_lived_token_string", response.deviceToken)
        assertEquals("uuid", response.userId)

        val reserialized = json.encodeToString(response)
        val reParsed = json.decodeFromString<SearchLoginResponse>(reserialized)
        assertEquals(response, reParsed)
    }

    // --- Test 4: DeviceOverrideRequest with active=true and active=false ---

    @Test
    fun `DeviceOverrideRequest serializes with active true`() {
        val request = DeviceOverrideRequest(active = true)

        val jsonString = json.encodeToString(request)
        val jsonObject = json.decodeFromString<JsonObject>(jsonString)

        assertEquals("true", jsonObject["active"]?.jsonPrimitive?.content)
    }

    @Test
    fun `DeviceOverrideRequest serializes with active false`() {
        val request = DeviceOverrideRequest(active = false)

        val jsonString = json.encodeToString(request)
        val jsonObject = json.decodeFromString<JsonObject>(jsonString)

        assertEquals("false", jsonObject["active"]?.jsonPrimitive?.content)
    }

    @Test
    fun `DeviceOverrideRequest round-trips correctly`() {
        val request = DeviceOverrideRequest(active = false)

        val jsonString = json.encodeToString(request)
        val deserialized = json.decodeFromString<DeviceOverrideRequest>(jsonString)

        assertEquals(request, deserialized)
    }

    // --- Test 5: OkResponse with ok=true and ok=false ---

    @Test
    fun `OkResponse deserializes with ok true`() {
        val responseJson = """{"ok": true}"""

        val response = json.decodeFromString<OkResponse>(responseJson)

        assertTrue(response.ok)
    }

    @Test
    fun `OkResponse deserializes with ok false`() {
        val responseJson = """{"ok": false}"""

        val response = json.decodeFromString<OkResponse>(responseJson)

        assertFalse(response.ok)
    }

    @Test
    fun `OkResponse round-trips correctly`() {
        val response = OkResponse(ok = true)

        val jsonString = json.encodeToString(response)
        val deserialized = json.decodeFromString<OkResponse>(jsonString)

        assertEquals(response, deserialized)
    }
}
