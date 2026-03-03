package com.skeddy.ui

/**
 * Represents the current search status displayed on the main screen.
 *
 * Derived from MonitoringForegroundService broadcasts.
 */
sealed class SearchStatus {
    data class Searching(val intervalSeconds: Int) : SearchStatus()
    data object Stopped : SearchStatus()
    data object WaitingForServer : SearchStatus()
    data object ServerOffline : SearchStatus()
}
