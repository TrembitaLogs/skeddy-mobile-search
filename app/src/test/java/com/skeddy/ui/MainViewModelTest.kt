package com.skeddy.ui

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.skeddy.R
import com.skeddy.error.SkeddyError
import com.skeddy.network.ApiResult
import com.skeddy.network.DeviceTokenManager
import com.skeddy.network.SkeddyServerClient
import com.skeddy.network.models.DeviceOverrideRequest
import com.skeddy.network.models.OkResponse
import com.skeddy.service.MonitoringForegroundService
import org.junit.Assert.assertNotNull
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var serverClient: SkeddyServerClient
    private lateinit var deviceTokenManager: DeviceTokenManager
    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        serverClient = mockk()
        deviceTokenManager = mockk()
        viewModel = MainViewModel(serverClient, deviceTokenManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `serviceStatus initial value is false`() {
        assertEquals(false, viewModel.serviceStatus.value)
    }

    @Test
    fun `updateServiceStatus updates serviceStatus to true`() {
        viewModel.updateServiceStatus(true)
        assertTrue(viewModel.serviceStatus.value!!)
    }

    @Test
    fun `updateServiceStatus updates serviceStatus to false`() {
        viewModel.updateServiceStatus(true)
        viewModel.updateServiceStatus(false)
        assertFalse(viewModel.serviceStatus.value!!)
    }

    // ==================== Search Active State Tests ====================

    @Test
    fun `isSearchActive initial value is false`() {
        assertEquals(false, viewModel.isSearchActive.value)
    }

    @Test
    fun `updateSearchActive updates isSearchActive to true`() {
        viewModel.updateSearchActive(true)
        assertTrue(viewModel.isSearchActive.value!!)
    }

    @Test
    fun `updateSearchActive updates isSearchActive to false`() {
        viewModel.updateSearchActive(true)
        viewModel.updateSearchActive(false)
        assertFalse(viewModel.isSearchActive.value!!)
    }

    @Test
    fun `updateServiceStatus false also clears isSearchActive`() {
        viewModel.updateSearchActive(true)
        assertTrue(viewModel.isSearchActive.value!!)

        viewModel.updateServiceStatus(false)
        assertFalse(viewModel.isSearchActive.value!!)
    }

    @Test
    fun `updateServiceStatus true does not change isSearchActive`() {
        assertFalse(viewModel.isSearchActive.value!!)

        viewModel.updateServiceStatus(true)
        assertFalse(viewModel.isSearchActive.value!!)
    }

    @Test
    fun `lastCheckTime initial value is null`() {
        assertNull(viewModel.lastCheckTime.value)
    }

    @Test
    fun `updateLastCheckTime sets correct timestamp`() {
        val timestamp = 1234567890L
        viewModel.updateLastCheckTime(timestamp)
        assertEquals(timestamp, viewModel.lastCheckTime.value)
    }

    // ==================== Error Handling Tests ====================

    @Test
    fun `errorEvent initial value is null`() {
        assertNull(viewModel.errorEvent.value)
    }

    @Test
    fun `lastError initial value is null`() {
        assertNull(viewModel.lastError.value)
    }

    @Test
    fun `postError sets errorEvent`() {
        viewModel.postError(SkeddyError.ParseTimeout)
        val event = viewModel.errorEvent.value
        assertNotNull(event)
        assertEquals(SkeddyError.ParseTimeout, event?.peekError())
    }

    @Test
    fun `postError sets lastError`() {
        viewModel.postError(SkeddyError.LyftAppNotFound)
        val event = viewModel.lastError.value
        assertNotNull(event)
        assertEquals(SkeddyError.LyftAppNotFound, event?.peekError())
    }

    @Test
    fun `clearLastError sets lastError to null`() {
        viewModel.postError(SkeddyError.DatabaseError)
        assertNotNull(viewModel.lastError.value)

        viewModel.clearLastError()
        assertNull(viewModel.lastError.value)
    }

    @Test
    fun `multiple postError calls update values`() {
        viewModel.postError(SkeddyError.ParseTimeout)
        assertEquals(SkeddyError.ParseTimeout, viewModel.lastError.value?.peekError())

        viewModel.postError(SkeddyError.MenuButtonNotFound)
        assertEquals(SkeddyError.MenuButtonNotFound, viewModel.lastError.value?.peekError())

        viewModel.postError(SkeddyError.ServiceKilled)
        assertEquals(SkeddyError.ServiceKilled, viewModel.lastError.value?.peekError())
    }

    // ==================== Toggle Search (Device Override) Tests ====================

    @Test
    fun `isToggleLoading initial value is false`() {
        assertEquals(false, viewModel.isToggleLoading.value)
    }

    @Test
    fun `toastMessage initial value is null`() {
        assertNull(viewModel.toastMessage.value)
    }

    @Test
    fun `navigateToLogin initial value is false`() {
        assertEquals(false, viewModel.navigateToLogin.value)
    }

    @Test
    fun `toggleSearch calls deviceOverride with active true when service is stopped`() {
        // Service is stopped (default false)
        coEvery { serverClient.deviceOverride(any()) } returns ApiResult.Success(OkResponse(ok = true))

        viewModel.toggleSearch()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { serverClient.deviceOverride(DeviceOverrideRequest(active = true)) }
    }

    @Test
    fun `toggleSearch calls deviceOverride with active false when search is active`() {
        viewModel.updateSearchActive(true)
        coEvery { serverClient.deviceOverride(any()) } returns ApiResult.Success(OkResponse(ok = true))

        viewModel.toggleSearch()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { serverClient.deviceOverride(DeviceOverrideRequest(active = false)) }
    }

    @Test
    fun `toggleSearch success when starting shows search_started toast`() {
        coEvery { serverClient.deviceOverride(any()) } returns ApiResult.Success(OkResponse(ok = true))

        viewModel.toggleSearch()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(R.string.search_started, viewModel.toastMessage.value)
    }

    @Test
    fun `toggleSearch success when stopping shows search_stopped toast`() {
        viewModel.updateSearchActive(true)
        coEvery { serverClient.deviceOverride(any()) } returns ApiResult.Success(OkResponse(ok = true))

        viewModel.toggleSearch()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(R.string.search_stopped, viewModel.toastMessage.value)
    }

    @Test
    fun `toggleSearch unauthorized clears device token and navigates to login`() {
        coEvery { serverClient.deviceOverride(any()) } returns ApiResult.Unauthorized
        every { deviceTokenManager.clearDeviceToken() } just runs

        viewModel.toggleSearch()
        testDispatcher.scheduler.advanceUntilIdle()

        verify { deviceTokenManager.clearDeviceToken() }
        assertTrue(viewModel.navigateToLogin.value!!)
    }

    @Test
    fun `toggleSearch network error shows network_error toast`() {
        coEvery { serverClient.deviceOverride(any()) } returns ApiResult.NetworkError

        viewModel.toggleSearch()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(R.string.network_error, viewModel.toastMessage.value)
    }

    @Test
    fun `toggleSearch server error shows server_error toast`() {
        coEvery { serverClient.deviceOverride(any()) } returns ApiResult.ServerError

        viewModel.toggleSearch()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(R.string.server_error, viewModel.toastMessage.value)
    }

    @Test
    fun `toggleSearch service unavailable shows server_error toast`() {
        coEvery { serverClient.deviceOverride(any()) } returns ApiResult.ServiceUnavailable

        viewModel.toggleSearch()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(R.string.server_error, viewModel.toastMessage.value)
    }

    @Test
    fun `toggleSearch rate limited shows server_error toast`() {
        coEvery { serverClient.deviceOverride(any()) } returns ApiResult.RateLimited(60)

        viewModel.toggleSearch()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(R.string.server_error, viewModel.toastMessage.value)
    }

    @Test
    fun `toggleSearch sets isToggleLoading to false after completion`() {
        coEvery { serverClient.deviceOverride(any()) } returns ApiResult.Success(OkResponse(ok = true))

        viewModel.toggleSearch()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.isToggleLoading.value!!)
    }

    @Test
    fun `toggleSearch prevents double calls while loading`() {
        coEvery { serverClient.deviceOverride(any()) } returns ApiResult.Success(OkResponse(ok = true))

        viewModel.toggleSearch()
        viewModel.toggleSearch() // Should be ignored
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { serverClient.deviceOverride(any()) }
    }

    @Test
    fun `clearToastMessage resets to null`() {
        coEvery { serverClient.deviceOverride(any()) } returns ApiResult.Success(OkResponse(ok = true))

        viewModel.toggleSearch()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(viewModel.toastMessage.value)

        viewModel.clearToastMessage()
        assertNull(viewModel.toastMessage.value)
    }

    @Test
    fun `clearNavigateToLogin resets to false`() {
        coEvery { serverClient.deviceOverride(any()) } returns ApiResult.Unauthorized
        every { deviceTokenManager.clearDeviceToken() } just runs

        viewModel.toggleSearch()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.navigateToLogin.value!!)

        viewModel.clearNavigateToLogin()
        assertFalse(viewModel.navigateToLogin.value!!)
    }

    // ==================== Force Update Tests ====================

    @Test
    fun `forceUpdateActive initial value is false`() {
        assertEquals(false, viewModel.forceUpdateActive.value)
    }

    @Test
    fun `forceUpdateUrl initial value is null`() {
        assertNull(viewModel.forceUpdateUrl.value)
    }

    @Test
    fun `setForceUpdateState sets active to true and url`() {
        val url = "https://play.google.com/store/apps/details?id=com.skeddy"
        viewModel.setForceUpdateState(url)
        assertTrue(viewModel.forceUpdateActive.value!!)
        assertEquals(url, viewModel.forceUpdateUrl.value)
    }

    @Test
    fun `setForceUpdateState with null url sets active true and url null`() {
        viewModel.setForceUpdateState(null)
        assertTrue(viewModel.forceUpdateActive.value!!)
        assertNull(viewModel.forceUpdateUrl.value)
    }

    @Test
    fun `clearForceUpdate resets to false and null`() {
        viewModel.setForceUpdateState("https://example.com")
        assertTrue(viewModel.forceUpdateActive.value!!)

        viewModel.clearForceUpdate()
        assertFalse(viewModel.forceUpdateActive.value!!)
        assertNull(viewModel.forceUpdateUrl.value)
    }

    @Test
    fun `clearForceUpdate when already cleared is safe`() {
        // Should not throw even when already in cleared state
        viewModel.clearForceUpdate()
        assertFalse(viewModel.forceUpdateActive.value!!)
        assertNull(viewModel.forceUpdateUrl.value)
    }

    // ==================== Search Status Tests (Task 17.3) ====================

    @Test
    fun `searchStatus initial value is Stopped`() {
        assertEquals(SearchStatus.Stopped, viewModel.searchStatus.value)
    }

    @Test
    fun `currentInterval initial value is null`() {
        assertNull(viewModel.currentInterval.value)
    }

    @Test
    fun `ridesTodayCount initial value is 0`() {
        assertEquals(0, viewModel.ridesTodayCount.value)
    }

    @Test
    fun `accessibilityEnabled initial value is false`() {
        assertFalse(viewModel.accessibilityEnabled.value!!)
    }

    @Test
    fun `updateAccessibilityEnabled sets value`() {
        viewModel.updateAccessibilityEnabled(true)
        assertTrue(viewModel.accessibilityEnabled.value!!)

        viewModel.updateAccessibilityEnabled(false)
        assertFalse(viewModel.accessibilityEnabled.value!!)
    }

    @Test
    fun `updateCurrentInterval sets value`() {
        viewModel.updateCurrentInterval(45)
        assertEquals(45, viewModel.currentInterval.value)
    }

    @Test
    fun `updateRidesTodayCount sets value`() {
        viewModel.updateRidesTodayCount(5)
        assertEquals(5, viewModel.ridesTodayCount.value)
    }

    @Test
    fun `updateSearchStatus sets value`() {
        viewModel.updateSearchStatus(SearchStatus.Searching(30))
        assertEquals(SearchStatus.Searching(30), viewModel.searchStatus.value)

        viewModel.updateSearchStatus(SearchStatus.ServerOffline)
        assertEquals(SearchStatus.ServerOffline, viewModel.searchStatus.value)
    }

    @Test
    fun `updateServiceStatus false resets searchStatus to Stopped`() {
        viewModel.updateSearchStatus(SearchStatus.Searching(30))
        assertEquals(SearchStatus.Searching(30), viewModel.searchStatus.value)

        viewModel.updateServiceStatus(false)
        assertEquals(SearchStatus.Stopped, viewModel.searchStatus.value)
    }

    // ==================== updateFromBroadcast Tests ====================

    @Test
    fun `updateFromBroadcast with searching state`() {
        viewModel.updateFromBroadcast(
            isRunning = true,
            isSearchActive = true,
            serverOffline = false,
            lastCheckTime = 1000L,
            ridesCount = 3,
            currentIntervalSeconds = 30,
            searchState = MonitoringForegroundService.SEARCH_STATE_SEARCHING
        )

        assertTrue(viewModel.serviceStatus.value!!)
        assertTrue(viewModel.isSearchActive.value!!)
        assertEquals(1000L, viewModel.lastCheckTime.value)
        assertEquals(30, viewModel.currentInterval.value)
        assertEquals(3, viewModel.ridesTodayCount.value)
        assertEquals(SearchStatus.Searching(30), viewModel.searchStatus.value)
    }

    @Test
    fun `updateFromBroadcast with stopped state`() {
        viewModel.updateFromBroadcast(
            isRunning = true,
            isSearchActive = false,
            serverOffline = false,
            lastCheckTime = null,
            ridesCount = 0,
            currentIntervalSeconds = 60,
            searchState = MonitoringForegroundService.SEARCH_STATE_STOPPED
        )

        assertTrue(viewModel.serviceStatus.value!!)
        assertFalse(viewModel.isSearchActive.value!!)
        assertEquals(60, viewModel.currentInterval.value)
        assertEquals(SearchStatus.Stopped, viewModel.searchStatus.value)
    }

    @Test
    fun `updateFromBroadcast with waiting state`() {
        viewModel.updateFromBroadcast(
            isRunning = true,
            isSearchActive = false,
            serverOffline = false,
            lastCheckTime = null,
            ridesCount = 0,
            currentIntervalSeconds = 30,
            searchState = MonitoringForegroundService.SEARCH_STATE_WAITING
        )

        assertEquals(SearchStatus.WaitingForServer, viewModel.searchStatus.value)
    }

    @Test
    fun `updateFromBroadcast with offline state`() {
        viewModel.updateFromBroadcast(
            isRunning = true,
            isSearchActive = false,
            serverOffline = true,
            lastCheckTime = null,
            ridesCount = 2,
            currentIntervalSeconds = 30,
            searchState = MonitoringForegroundService.SEARCH_STATE_OFFLINE
        )

        assertEquals(SearchStatus.ServerOffline, viewModel.searchStatus.value)
        assertEquals(2, viewModel.ridesTodayCount.value)
    }

    @Test
    fun `updateFromBroadcast with service not running`() {
        viewModel.updateFromBroadcast(
            isRunning = false,
            isSearchActive = false,
            serverOffline = false,
            lastCheckTime = null,
            ridesCount = 5,
            currentIntervalSeconds = 30,
            searchState = MonitoringForegroundService.SEARCH_STATE_STOPPED
        )

        assertFalse(viewModel.serviceStatus.value!!)
        assertFalse(viewModel.isSearchActive.value!!)
        assertEquals(SearchStatus.Stopped, viewModel.searchStatus.value)
    }

    @Test
    fun `updateFromBroadcast does not overwrite lastCheckTime with zero`() {
        viewModel.updateLastCheckTime(5000L)
        assertEquals(5000L, viewModel.lastCheckTime.value)

        viewModel.updateFromBroadcast(
            isRunning = true,
            isSearchActive = true,
            serverOffline = false,
            lastCheckTime = null,
            ridesCount = 0,
            currentIntervalSeconds = 30,
            searchState = MonitoringForegroundService.SEARCH_STATE_SEARCHING
        )

        // lastCheckTime should remain unchanged
        assertEquals(5000L, viewModel.lastCheckTime.value)
    }

    @Test
    fun `updateFromBroadcast with unknown search state defaults to Stopped`() {
        viewModel.updateFromBroadcast(
            isRunning = true,
            isSearchActive = false,
            serverOffline = false,
            lastCheckTime = null,
            ridesCount = 0,
            currentIntervalSeconds = 30,
            searchState = "unknown_state"
        )

        assertEquals(SearchStatus.Stopped, viewModel.searchStatus.value)
    }

    @Test
    fun `updateFromBroadcast searching interval is reflected in SearchStatus`() {
        viewModel.updateFromBroadcast(
            isRunning = true,
            isSearchActive = true,
            serverOffline = false,
            lastCheckTime = 2000L,
            ridesCount = 1,
            currentIntervalSeconds = 45,
            searchState = MonitoringForegroundService.SEARCH_STATE_SEARCHING
        )

        val status = viewModel.searchStatus.value as SearchStatus.Searching
        assertEquals(45, status.intervalSeconds)
    }

}
