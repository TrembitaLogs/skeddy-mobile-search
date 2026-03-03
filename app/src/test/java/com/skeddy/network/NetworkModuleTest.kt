package com.skeddy.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import okhttp3.MediaType.Companion.toMediaType
import java.util.concurrent.TimeUnit

class NetworkModuleTest {

    private lateinit var mockWebServer: MockWebServer

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Serializable
    data class TestResponse(val message: String, val code: Int)

    interface TestApi {
        @GET("test")
        suspend fun getTest(): TestResponse
    }

    @Test
    fun `OkHttpClient has correct connect timeout`() {
        assertEquals(
            ApiConfig.CONNECT_TIMEOUT_SECONDS,
            NetworkModule.okHttpClient.connectTimeoutMillis.toLong() / 1000
        )
    }

    @Test
    fun `OkHttpClient has correct read timeout`() {
        assertEquals(
            ApiConfig.READ_TIMEOUT_SECONDS,
            NetworkModule.okHttpClient.readTimeoutMillis.toLong() / 1000
        )
    }

    @Test
    fun `OkHttpClient has correct write timeout`() {
        assertEquals(
            ApiConfig.WRITE_TIMEOUT_SECONDS,
            NetworkModule.okHttpClient.writeTimeoutMillis.toLong() / 1000
        )
    }

    @Test
    fun `OkHttpClient has logging interceptor`() {
        val hasLoggingInterceptor = NetworkModule.okHttpClient.interceptors.any {
            it is okhttp3.logging.HttpLoggingInterceptor
        }
        assertTrue("OkHttpClient should have a logging interceptor", hasLoggingInterceptor)
    }

    @Test
    fun `Retrofit has correct base URL`() {
        assertEquals(
            ApiConfig.BASE_URL,
            NetworkModule.retrofit.baseUrl().toString()
        )
    }

    @Test
    fun `Retrofit can make request and parse response via MockWebServer`() {
        val responseBody = """{"message":"ok","code":200}"""
        mockWebServer.enqueue(MockResponse().setBody(responseBody).setResponseCode(200))

        val retrofit = Retrofit.Builder()
            .baseUrl(mockWebServer.url("/"))
            .client(NetworkModule.okHttpClient)
            .addConverterFactory(
                NetworkModule.json.asConverterFactory("application/json; charset=UTF-8".toMediaType())
            )
            .build()

        val api = retrofit.create(TestApi::class.java)
        val response = kotlinx.coroutines.runBlocking { api.getTest() }

        assertEquals("ok", response.message)
        assertEquals(200, response.code)

        val request = mockWebServer.takeRequest(1, TimeUnit.SECONDS)
        assertEquals("/test", request?.path)
    }

    @Test
    fun `Json ignores unknown keys in response`() {
        val responseBody = """{"message":"ok","code":200,"extra_field":"should be ignored"}"""
        mockWebServer.enqueue(MockResponse().setBody(responseBody).setResponseCode(200))

        val retrofit = Retrofit.Builder()
            .baseUrl(mockWebServer.url("/"))
            .client(NetworkModule.okHttpClient)
            .addConverterFactory(
                NetworkModule.json.asConverterFactory("application/json; charset=UTF-8".toMediaType())
            )
            .build()

        val api = retrofit.create(TestApi::class.java)
        val response = kotlinx.coroutines.runBlocking { api.getTest() }

        assertEquals("ok", response.message)
        assertEquals(200, response.code)
    }
}
