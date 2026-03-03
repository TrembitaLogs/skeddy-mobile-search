package com.skeddy.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.skeddy.network.DeviceTokenManager
import com.skeddy.network.SkeddyServerClient

class MainViewModelFactory(
    private val serverClient: SkeddyServerClient,
    private val deviceTokenManager: DeviceTokenManager
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            return MainViewModel(serverClient, deviceTokenManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
