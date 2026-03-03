package com.skeddy.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skeddy.R
import com.skeddy.error.SkeddyError
import com.skeddy.network.ApiResult
import com.skeddy.network.DeviceTokenManager
import com.skeddy.network.SkeddyServerClient
import com.skeddy.network.models.DeviceOverrideRequest
import com.skeddy.service.MonitoringForegroundService
import com.skeddy.ui.error.ErrorEvent
import kotlinx.coroutines.launch

class MainViewModel(
    private val serverClient: SkeddyServerClient,
    private val deviceTokenManager: DeviceTokenManager
) : ViewModel() {

    private val _serviceStatus = MutableLiveData(false)
    val serviceStatus: LiveData<Boolean> = _serviceStatus

    private val _isSearchActive = MutableLiveData(false)
    val isSearchActive: LiveData<Boolean> = _isSearchActive

    private val _lastCheckTime = MutableLiveData<Long?>()
    val lastCheckTime: LiveData<Long?> = _lastCheckTime

    private val _errorEvent = MutableLiveData<ErrorEvent?>()
    val errorEvent: LiveData<ErrorEvent?> = _errorEvent

    private val _lastError = MutableLiveData<ErrorEvent?>()
    val lastError: LiveData<ErrorEvent?> = _lastError

    // Force update state
    private val _forceUpdateActive = MutableLiveData(false)
    val forceUpdateActive: LiveData<Boolean> = _forceUpdateActive

    private val _forceUpdateUrl = MutableLiveData<String?>(null)
    val forceUpdateUrl: LiveData<String?> = _forceUpdateUrl

    // Search status UI fields (Task 17.3)
    private val _searchStatus = MutableLiveData<SearchStatus>(SearchStatus.Stopped)
    val searchStatus: LiveData<SearchStatus> = _searchStatus

    private val _currentInterval = MutableLiveData<Int?>(null)
    val currentInterval: LiveData<Int?> = _currentInterval

    private val _ridesTodayCount = MutableLiveData(0)
    val ridesTodayCount: LiveData<Int> = _ridesTodayCount

    private val _accessibilityEnabled = MutableLiveData(false)
    val accessibilityEnabled: LiveData<Boolean> = _accessibilityEnabled

    fun setForceUpdateState(url: String?) {
        _forceUpdateActive.value = true
        _forceUpdateUrl.value = url
    }

    fun clearForceUpdate() {
        _forceUpdateActive.value = false
        _forceUpdateUrl.value = null
    }

    // Device override (Start/Stop toggle)
    private val _isToggleLoading = MutableLiveData(false)
    val isToggleLoading: LiveData<Boolean> = _isToggleLoading

    private val _toastMessage = MutableLiveData<Int?>()
    val toastMessage: LiveData<Int?> = _toastMessage

    private val _navigateToPairing = MutableLiveData(false)
    val navigateToPairing: LiveData<Boolean> = _navigateToPairing

    private val _serviceControlEvent = MutableLiveData<Boolean?>()
    val serviceControlEvent: LiveData<Boolean?> = _serviceControlEvent

    /**
     * Sends POST /search/device-override to toggle search status on the server.
     * Uses current [isSearchActive] to determine the new state.
     * UI updates via next ping response.
     */
    fun toggleSearch() {
        if (_isToggleLoading.value == true) return

        val currentlyActive = _isSearchActive.value ?: false
        val newState = !currentlyActive
        _isToggleLoading.value = true

        viewModelScope.launch {
            val result = serverClient.deviceOverride(DeviceOverrideRequest(active = newState))

            when (result) {
                is ApiResult.Success -> {
                    _toastMessage.value =
                        if (newState) R.string.search_started else R.string.search_stopped
                    _serviceControlEvent.value = newState
                    _isSearchActive.value = newState
                    _searchStatus.value = if (newState) SearchStatus.WaitingForServer else SearchStatus.Stopped
                }
                is ApiResult.Unauthorized -> {
                    // SkeddyServerClient already calls clearDeviceToken() on 401/403
                    deviceTokenManager.clearDeviceToken()
                    _navigateToPairing.value = true
                }
                is ApiResult.NetworkError -> {
                    _toastMessage.value = R.string.network_error
                }
                is ApiResult.ServerError,
                is ApiResult.ServiceUnavailable,
                is ApiResult.ValidationError,
                is ApiResult.RateLimited,
                is ApiResult.PairingError -> {
                    _toastMessage.value = R.string.server_error
                }
            }

            _isToggleLoading.value = false
        }
    }

    fun clearToastMessage() {
        _toastMessage.value = null
    }

    fun clearNavigateToPairing() {
        _navigateToPairing.value = false
    }

    fun clearServiceControlEvent() {
        _serviceControlEvent.value = null
    }

    fun updateServiceStatus(isRunning: Boolean) {
        _serviceStatus.value = isRunning
        if (!isRunning) {
            _isSearchActive.value = false
            _searchStatus.value = SearchStatus.Stopped
        }
    }

    fun updateSearchActive(isActive: Boolean) {
        _isSearchActive.value = isActive
    }

    /**
     * Updates all status fields from a MonitoringForegroundService broadcast.
     * Derives [SearchStatus] from the broadcast data.
     */
    fun updateFromBroadcast(
        isRunning: Boolean,
        isSearchActive: Boolean,
        serverOffline: Boolean,
        lastCheckTime: Long?,
        ridesCount: Int,
        currentIntervalSeconds: Int,
        searchState: String
    ) {
        _serviceStatus.value = isRunning
        _isSearchActive.value = isSearchActive
        if (lastCheckTime != null && lastCheckTime > 0) {
            _lastCheckTime.value = lastCheckTime
        }
        _currentInterval.value = currentIntervalSeconds
        _ridesTodayCount.value = ridesCount

        _searchStatus.value = when (searchState) {
            MonitoringForegroundService.SEARCH_STATE_SEARCHING ->
                SearchStatus.Searching(currentIntervalSeconds)
            MonitoringForegroundService.SEARCH_STATE_WAITING ->
                SearchStatus.WaitingForServer
            MonitoringForegroundService.SEARCH_STATE_OFFLINE ->
                SearchStatus.ServerOffline
            else -> SearchStatus.Stopped
        }
    }

    fun updateLastCheckTime(timestamp: Long) {
        _lastCheckTime.value = timestamp
    }

    fun updateAccessibilityEnabled(enabled: Boolean) {
        _accessibilityEnabled.value = enabled
    }

    fun updateCurrentInterval(intervalSeconds: Int) {
        _currentInterval.value = intervalSeconds
    }

    fun updateRidesTodayCount(count: Int) {
        _ridesTodayCount.value = count
    }

    fun updateSearchStatus(status: SearchStatus) {
        _searchStatus.value = status
    }

    fun postError(error: SkeddyError) {
        val event = ErrorEvent(error)
        _errorEvent.value = event
        _lastError.value = event
    }

    fun clearLastError() {
        _lastError.value = null
    }

    override fun onCleared() {
        super.onCleared()
    }
}
