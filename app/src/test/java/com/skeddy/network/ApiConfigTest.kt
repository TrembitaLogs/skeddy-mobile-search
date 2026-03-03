package com.skeddy.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiConfigTest {

    @Test
    fun `CONNECT_TIMEOUT_SECONDS is 10`() {
        assertEquals(10L, ApiConfig.CONNECT_TIMEOUT_SECONDS)
    }

    @Test
    fun `READ_TIMEOUT_SECONDS is 15`() {
        assertEquals(15L, ApiConfig.READ_TIMEOUT_SECONDS)
    }

    @Test
    fun `WRITE_TIMEOUT_SECONDS is 15`() {
        assertEquals(15L, ApiConfig.WRITE_TIMEOUT_SECONDS)
    }

    @Test
    fun `BASE_URL ends with slash`() {
        assertTrue(ApiConfig.BASE_URL.endsWith("/"))
    }
}
