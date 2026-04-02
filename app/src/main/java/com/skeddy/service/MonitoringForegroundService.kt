package com.skeddy.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.skeddy.R
import com.skeddy.accessibility.SkeddyAccessibilityService
import com.skeddy.data.BlacklistDatabase
import com.skeddy.data.BlacklistRepository
import com.skeddy.data.SkeddyPreferences
import com.skeddy.filter.RideFilter
import com.skeddy.model.ScheduledRide
import com.skeddy.navigation.LyftNavigator
import com.skeddy.network.ApiResult
import com.skeddy.network.DeviceTokenManager
import com.skeddy.network.NetworkModule
import com.skeddy.network.SkeddyApi
import com.skeddy.network.SkeddyServerClient
import com.skeddy.notification.SkeddyNotificationManager
import com.skeddy.navigation.LyftScreen
import com.skeddy.parser.RideParser
import com.skeddy.recovery.AutoRecoveryManager
import com.skeddy.logging.SkeddyLogger
import com.skeddy.ui.MainActivity
import com.skeddy.util.DeviceHealthCollector
import com.skeddy.util.LocationCollector
import com.skeddy.utils.PermissionUtils
import android.view.accessibility.AccessibilityNodeInfo
import com.skeddy.BuildConfig
import com.skeddy.network.models.PingRequest
import com.skeddy.network.models.AcceptFailure
import com.skeddy.network.models.PingResponse
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import com.skeddy.service.search.ClassicSearchFlow
import com.skeddy.service.search.OptimizedSearchFlow
import com.skeddy.service.search.SearchFlowContext
import com.skeddy.service.search.SearchFlowResult
import com.skeddy.service.search.SearchFlowStrategy
import com.skeddy.service.search.SearchFlowType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sealed class representing different types of monitoring errors.
 * Used for categorizing failures during the monitoring cycle.
 */
sealed class MonitoringError(val message: String) {
    /** Accessibility Service is not available or not connected */
    object AccessibilityUnavailable : MonitoringError("Accessibility Service недоступний")

    /** Failed to navigate to required screen */
    object NavigationFailed : MonitoringError("Помилка навігації")

    /** Failed to parse ride information from UI */
    object ParseFailed : MonitoringError("Помилка парсингу")

    /** Unexpected error during monitoring cycle */
    data class UnexpectedError(val cause: Throwable) : MonitoringError(cause.message ?: "Невідома помилка")
}

/**
 * Foreground Service for cyclic monitoring of Lyft scheduled rides.
 *
 * Monitors Lyft app every 30 seconds, navigates to Scheduled Rides screen,
 * parses ride information, filters high-value rides, and saves them to database.
 *
 * Usage:
 * - Start: startService(Intent(context, MonitoringForegroundService::class.java).apply {
 *     action = MonitoringForegroundService.ACTION_START
 * })
 * - Stop: startService(Intent(context, MonitoringForegroundService::class.java).apply {
 *     action = MonitoringForegroundService.ACTION_STOP
 * })
 */
class MonitoringForegroundService : Service() {

    companion object {
        const val TAG = "MonitoringService"

        /** Intent action to start monitoring */
        const val ACTION_START = "com.skeddy.action.START_MONITORING"

        /** Intent action to stop monitoring */
        const val ACTION_STOP = "com.skeddy.action.STOP_MONITORING"

        /** Intent action to test high-value ride notification (for testing) */
        const val ACTION_TEST_NOTIFICATION = "com.skeddy.action.TEST_NOTIFICATION"

        /** Intent action to update monitoring interval */
        const val ACTION_UPDATE_INTERVAL = "com.skeddy.action.UPDATE_INTERVAL"

        /** Intent action for AlarmManager wakeup during Doze mode */
        const val ACTION_ALARM_WAKEUP = "com.skeddy.action.ALARM_WAKEUP"

        /** Intent action to request current service state broadcast (DEF-7) */
        const val ACTION_REQUEST_STATE = "com.skeddy.action.REQUEST_STATE"

        /** Extra key for monitoring interval in milliseconds (Long) */
        const val EXTRA_INTERVAL_MS = "extra_interval_ms"

        /** Request code for AlarmManager PendingIntent */
        private const val ALARM_REQUEST_CODE = 1001

        /** Broadcast action sent when monitoring status changes */
        const val ACTION_MONITORING_STATUS = "com.skeddy.broadcast.MONITORING_STATUS"

        /** Broadcast action sent when a new high-value ride is found */
        const val ACTION_NEW_RIDE_FOUND = "com.skeddy.broadcast.NEW_RIDE_FOUND"

        /** Extra key for monitoring status (Boolean) */
        const val EXTRA_IS_MONITORING = "extra_is_monitoring"

        /** Extra key for last check timestamp (Long) */
        const val EXTRA_LAST_CHECK_TIME = "extra_last_check_time"

        /** Extra key for total rides found count (Int) */
        const val EXTRA_RIDES_COUNT = "extra_rides_count"

        /** Extra key for ride price (Double) */
        const val EXTRA_RIDE_PRICE = "extra_ride_price"

        /** Extra key for ride pickup location (String) */
        const val EXTRA_RIDE_PICKUP = "extra_ride_pickup"

        /** Extra key for ride dropoff location (String) */
        const val EXTRA_RIDE_DROPOFF = "extra_ride_dropoff"

        /** Broadcast action sent when server requires app update */
        const val ACTION_FORCE_UPDATE = "com.skeddy.broadcast.FORCE_UPDATE"

        /** Broadcast action sent when server clears force update requirement */
        const val ACTION_FORCE_UPDATE_CLEARED = "com.skeddy.broadcast.FORCE_UPDATE_CLEARED"

        /** Broadcast action sent when device token is invalidated (401/403) */
        const val ACTION_UNPAIRED = "com.skeddy.broadcast.UNPAIRED"

        /** Extra key for update URL (String) */
        const val EXTRA_UPDATE_URL = "extra_update_url"

        /** Extra key for server offline flag (Boolean) */
        const val EXTRA_SERVER_OFFLINE = "extra_server_offline"

        /** Extra key for server search active state (Boolean) */
        const val EXTRA_IS_SEARCH_ACTIVE = "extra_is_search_active"

        /** Extra key for current search interval in seconds (Int) */
        const val EXTRA_CURRENT_INTERVAL = "extra_current_interval"

        /** Extra key for search state string (see SEARCH_STATE_* constants) */
        const val EXTRA_SEARCH_STATE = "extra_search_state"

        // Search state values for EXTRA_SEARCH_STATE
        const val SEARCH_STATE_SEARCHING = "searching"
        const val SEARCH_STATE_STOPPED = "stopped"
        const val SEARCH_STATE_WAITING = "waiting_for_server"
        const val SEARCH_STATE_OFFLINE = "server_offline"

        /** Blacklist cleanup interval: 1 hour */
        internal const val CLEANUP_INTERVAL_MS = 60 * 60 * 1000L

        /** Default search interval in seconds (used until first successful ping) */
        internal const val DEFAULT_INTERVAL_SECONDS = 30

        /** Default server minimum price (used until first successful ping) */
        internal const val DEFAULT_MIN_PRICE = 20.0

        /** Retry delay for ping after network error (milliseconds) */
        internal const val RETRY_PING_DELAY_MS = 30_000L

        // State persistence keys
        private const val STATE_PREFS_NAME = "monitoring_service_state"
        private const val KEY_IS_MONITORING = "is_monitoring"
        private const val KEY_TOTAL_RIDES_FOUND = "total_rides_found"
        private const val KEY_CONSECUTIVE_FAILURES = "consecutive_failures"
        private const val KEY_LAST_SAVE_TIME = "last_save_time"

        /** Singleton instance for direct access */
        @Volatile
        private var instance: MonitoringForegroundService? = null

        /**
         * Gets the current service instance.
         * @return The service instance or null if not running
         */
        fun getInstance(): MonitoringForegroundService? = instance

        /**
         * Starts the monitoring service from any context.
         * Uses ContextCompat.startForegroundService for compatibility with Android O+.
         *
         * @param context The context to start the service from
         */
        fun start(context: Context) {
            val intent = Intent(context, MonitoringForegroundService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * Stops the monitoring service from any context.
         *
         * @param context The context to stop the service from
         */
        fun stop(context: Context) {
            val intent = Intent(context, MonitoringForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    /** Monitoring cycle interval in milliseconds (30 seconds) */
    private var monitoringInterval = 30_000L

    /** Server-provided search interval in seconds. Updates [monitoringInterval] on each ping. */
    internal var currentInterval: Int = DEFAULT_INTERVAL_SECONDS

    /** Server-provided minimum price filter. Default used until first successful ping. */
    internal var currentMinPrice: Double = DEFAULT_MIN_PRICE

    /** Active search flow strategy. Defaults to Optimized, can switch to Classic at runtime. */
    private var activeStrategy: SearchFlowStrategy = OptimizedSearchFlow()

    /** Whether onActivated() has been called on the current strategy */
    private var strategyActivated = false

    /** Accumulator for stats sent with the next ping request. */
    internal val pendingStats = PendingStatsAccumulator()

    /** Accumulator for ride verification state between pings. */
    internal val pendingVerification = PendingVerificationAccumulator()

    /** Duration of the last completed search cycle in milliseconds, sent with ping requests */
    internal var lastCycleDurationMs: Long? = null

    /** Flag indicating whether monitoring is currently active */
    private var isMonitoring = false

    /** Flag indicating if the initial ping (DEF-16) has completed (success or failure) */
    internal var isInitialPingCompleted = false

    /** Last known search active state from server (for UI synchronization via bound service) */
    private var lastSearchActiveFromServer = false

    /** Binder for local service binding */
    private val binder = LocalBinder()

    /** Handler for scheduling monitoring cycles on main thread */
    private val handler = Handler(Looper.getMainLooper())

    /** CoroutineScope for async monitoring operations */
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** AlarmManager for Doze-aware scheduling */
    private lateinit var alarmManager: AlarmManager

    /** PowerManager for checking Doze mode status */
    private lateinit var powerManager: PowerManager

    /** Flag indicating if device is currently in Doze mode */
    private var isInDozeMode = false

    /** Doze mode broadcast receiver */
    private val dozeReceiver = DozeModeBroadcastReceiver()

    /** Flag indicating if screen is currently off */
    internal var isScreenOff = false

    /** Flag indicating if monitoring was active before screen was turned off */
    internal var wasMonitoringBeforeScreenOff = false

    /** Flag indicating if the server has requested a force update */
    private var isInForceUpdateState = false

    /** Flag indicating if the server is currently unreachable (network error) */
    internal var isServerOffline = false

    /** Screen state broadcast receiver */
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "screenStateReceiver: Screen turned OFF")
                    handleScreenOff()
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(TAG, "screenStateReceiver: Screen turned ON")
                    handleScreenOn()
                }
            }
        }
    }

    /** BroadcastReceiver for AlarmManager wakeup intents */
    private val alarmReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_ALARM_WAKEUP) {
                Log.d(TAG, "alarmReceiver: Received ALARM_WAKEUP")
                if (isMonitoring) {
                    coroutineScope.launch {
                        monitoringCycleWithPing()
                    }
                }
            }
        }
    }

    /** NotificationManager for creating and updating notifications */
    private lateinit var notificationManager: NotificationManager

    /** User preferences for ride filtering */
    private lateinit var preferences: SkeddyPreferences

    /** Filter for high-value rides based on user preferences */
    private lateinit var rideFilter: RideFilter

    /** Repository for blacklist cleanup operations */
    internal lateinit var blacklistRepository: BlacklistRepository

    /** Server API client for ping, ride reporting, and device override */
    internal lateinit var serverClient: SkeddyServerClient

    /** Manages device token storage and device ID retrieval */
    internal lateinit var deviceTokenManager: DeviceTokenManager

    /** Persistent queue for unsent ride reports (retry after server recovery) */
    internal lateinit var pendingRideQueue: PendingRideQueue

    /** Collector for device health data (accessibility, Lyft, screen state) */
    private lateinit var deviceHealthCollector: DeviceHealthCollector

    /** Collector for device GPS coordinates */
    private lateinit var locationCollector: LocationCollector

    /** Notification manager for high-value ride alerts */
    private lateinit var rideNotificationManager: SkeddyNotificationManager

    /** SharedPreferences for persisting service state during low memory */
    private lateinit var statePrefs: SharedPreferences

    /** Timestamp of the last completed monitoring cycle (DEF-7: persisted for onResume queries) */
    private var lastCheckTime: Long = 0L

    /** Total count of new rides found in current session */
    private var totalNewRidesFound: Int = 0

    /** Counter for consecutive monitoring cycle failures */
    internal var consecutiveFailures: Int = 0

    /** Maximum allowed consecutive failures before pausing monitoring */
    internal val maxConsecutiveFailures: Int = 5

    /**
     * Runnable that executes monitoring cycle and reschedules itself.
     * Uses coroutine to perform async monitoring operations.
     * Delegates to monitoringCycleWithPing for ping + search cycle.
     *
     * Note: This is used for Handler-based scheduling when not in Doze mode.
     * During Doze mode, AlarmManager with setExactAndAllowWhileIdle is used.
     */
    private val monitoringRunnable: Runnable = object : Runnable {
        override fun run() {
            if (isMonitoring) {
                if (isServerOffline) {
                    Log.d(TAG, "Server offline, skipping monitoring cycle (retry handles recovery)")
                    return
                }
                Log.d(TAG, "Starting ping cycle at ${System.currentTimeMillis()}")
                coroutineScope.launch {
                    monitoringCycleWithPing()
                }
            }
        }
    }

    /**
     * Runnable that performs periodic blacklist cleanup and reschedules itself.
     * Runs every [CLEANUP_INTERVAL_MS] (1 hour) to remove expired entries.
     * Independent of monitoring cycles — does not require screen to be on.
     */
    private val cleanupRunnable: Runnable = object : Runnable {
        override fun run() {
            if (isMonitoring) {
                coroutineScope.launch {
                    performBlacklistCleanup()
                }
                handler.postDelayed(this, CLEANUP_INTERVAL_MS)
            }
        }
    }

    /**
     * Runnable for retrying ping after a network error.
     *
     * Separate from [monitoringRunnable] to allow independent lifecycle management
     * (e.g. cancel retry without affecting normal monitoring cycle scheduling).
     * Guarded by [isMonitoring] to avoid executing after [stopMonitoring].
     */
    private val retryPingRunnable = Runnable {
        if (isMonitoring) {
            Log.d(TAG, "retryPingRunnable: Executing retry ping")
            coroutineScope.launch { monitoringCycleWithPing() }
        }
    }

    /**
     * Binder class for local clients to access the service instance.
     */
    inner class LocalBinder : Binder() {
        /**
         * Returns the MonitoringForegroundService instance.
         * @return The service instance for direct method calls
         */
        fun getService(): MonitoringForegroundService = this@MonitoringForegroundService
    }

    // ==================== Lifecycle Methods ====================

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "onCreate: Service created")

        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        // Register receivers for Doze mode, AlarmManager wakeups, and screen state
        registerDozeReceiver()
        registerAlarmReceiver()
        registerScreenStateReceiver()

        // Initialize dependencies for monitoring cycle
        preferences = SkeddyPreferences(this)

        // Derive interval from server-provided value (default until first ping)
        monitoringInterval = currentInterval * 1000L

        val blacklistDb = BlacklistDatabase.getInstance(this)
        blacklistRepository = BlacklistRepository(blacklistDb.blacklistDao())
        rideFilter = RideFilter(blacklistRepository)

        // Initialize device health collector
        deviceHealthCollector = DeviceHealthCollector(this)

        // Initialize location collector
        locationCollector = LocationCollector(this)

        // Initialize network dependencies
        deviceTokenManager = DeviceTokenManager(this)
        NetworkModule.initialize(deviceTokenManager)
        val api = NetworkModule.createService<SkeddyApi>()
        serverClient = SkeddyServerClient(api, deviceTokenManager)
        pendingRideQueue = PendingRideQueue(this)

        rideNotificationManager = SkeddyNotificationManager(this)
        rideNotificationManager.createChannels()

        // Initialize state persistence
        statePrefs = getSharedPreferences(STATE_PREFS_NAME, Context.MODE_PRIVATE)

        // Restore state if service was killed due to low memory
        restoreServiceState()

        // Restore force update state from preferences
        isInForceUpdateState = preferences.forceUpdateActive

        Log.d(TAG, "onCreate: Dependencies initialized (serverMinPrice=$currentMinPrice, interval=${monitoringInterval}ms, forceUpdate=$isInForceUpdateState)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}, flags=$flags, startId=$startId")

        when (intent?.action) {
            ACTION_START -> {
                Log.i(TAG, "onStartCommand: ACTION_START received")
                // Always call startForeground() to satisfy the system contract
                // (startForegroundService must be followed by startForeground within ~10s)
                startForeground(SkeddyNotificationManager.NOTIFICATION_MONITORING_ID, createNotification())
                if (!isMonitoring) {
                    Log.i(TAG, "onStartCommand: Foreground service started, beginning monitoring")
                    startMonitoring()
                } else {
                    Log.w(TAG, "onStartCommand: Monitoring already active, refreshed foreground notification")
                }
            }
            ACTION_STOP -> {
                Log.i(TAG, "onStartCommand: ACTION_STOP received")
                stopMonitoring()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                Log.i(TAG, "onStartCommand: Service stopped")
            }
            ACTION_TEST_NOTIFICATION -> {
                Log.i(TAG, "onStartCommand: ACTION_TEST_NOTIFICATION received (ride notifications removed)")
            }
            ACTION_UPDATE_INTERVAL -> {
                val newIntervalMs = intent.getLongExtra(EXTRA_INTERVAL_MS, monitoringInterval)
                Log.i(TAG, "onStartCommand: ACTION_UPDATE_INTERVAL received, newInterval=${newIntervalMs}ms")
                setMonitoringInterval(newIntervalMs)
            }
            ACTION_REQUEST_STATE -> {
                Log.d(TAG, "onStartCommand: ACTION_REQUEST_STATE received")
                broadcastCurrentState()
            }
            else -> {
                // Default behavior: start monitoring if not already running
                Log.d(TAG, "onStartCommand: No action or unknown action (${intent?.action}), starting if not monitoring")
                if (!isMonitoring) {
                    startForeground(SkeddyNotificationManager.NOTIFICATION_MONITORING_ID, createNotification())
                    startMonitoring()
                    Log.i(TAG, "onStartCommand: Started monitoring via default action")
                }
            }
        }

        // START_STICKY ensures the service restarts if killed by the system
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "onBind: Client binding to service")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "onUnbind: Client unbinding from service")
        return super.onUnbind(intent)
    }

    override fun onRebind(intent: Intent?) {
        Log.d(TAG, "onRebind: Client rebinding to service")
        super.onRebind(intent)
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: Service destroying, isMonitoring was $isMonitoring")
        stopMonitoring()
        // Unregister receivers
        unregisterDozeReceiver()
        unregisterAlarmReceiver()
        unregisterScreenStateReceiver()
        // Cancel any pending alarms
        cancelAlarmWakeup()
        // Clear saved state on intentional destruction
        clearSavedState()
        instance = null
        super.onDestroy()
    }

    /**
     * Called when the user swipes away the app from recent apps.
     * Schedules a restart via JobScheduler to ensure monitoring continues.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w(TAG, "onTaskRemoved: App swiped away, isMonitoring=$isMonitoring")
        SkeddyLogger.w(TAG, "onTaskRemoved: App swiped away by user")

        if (isMonitoring) {
            // Save state before potential kill
            saveServiceState()

            // Mark that monitoring was active for restart decision
            ServiceRestartJobService.markMonitoringActive(this, true)
            BootCompletedReceiver.setAutoStartEnabled(this, true)

            // Schedule restart via JobScheduler (primary backup)
            scheduleRestartJob()

            // Schedule restart via WorkManager (fallback for Android 12+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                scheduleWorkManagerRestart()
            }

            Log.i(TAG, "onTaskRemoved: Restart scheduled via JobScheduler and WorkManager")
            SkeddyLogger.i(TAG, "onTaskRemoved: Restart mechanisms scheduled")
        }

        super.onTaskRemoved(rootIntent)
    }

    // ==================== Service Restart Methods ====================

    /**
     * Schedules a restart job via JobScheduler.
     * This is triggered when the app is swiped away or killed.
     */
    private fun scheduleRestartJob() {
        val jobScheduler = getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler

        val componentName = ComponentName(this, ServiceRestartJobService::class.java)
        val jobInfo = JobInfo.Builder(ServiceRestartJobService.JOB_ID, componentName)
            .setMinimumLatency(1000) // Wait 1 second before restart
            .setOverrideDeadline(5000) // Must run within 5 seconds
            .setPersisted(true) // Survive device reboot
            .build()

        val result = jobScheduler.schedule(jobInfo)
        if (result == JobScheduler.RESULT_SUCCESS) {
            Log.i(TAG, "scheduleRestartJob: Job scheduled successfully")
            SkeddyLogger.i(TAG, "Service restart job scheduled via JobScheduler")
        } else {
            Log.e(TAG, "scheduleRestartJob: Failed to schedule job")
            SkeddyLogger.e(TAG, "Failed to schedule service restart job")
        }
    }

    /**
     * Schedules a restart via WorkManager as fallback for Android 12+.
     * WorkManager has better support for background work restrictions.
     */
    private fun scheduleWorkManagerRestart() {
        try {
            val restartWork = OneTimeWorkRequestBuilder<ServiceRestartWorker>()
                .build()

            WorkManager.getInstance(this)
                .enqueueUniqueWork(
                    "service_restart",
                    ExistingWorkPolicy.REPLACE,
                    restartWork
                )

            Log.i(TAG, "scheduleWorkManagerRestart: WorkManager restart scheduled")
            SkeddyLogger.i(TAG, "Service restart scheduled via WorkManager")
        } catch (e: Exception) {
            Log.e(TAG, "scheduleWorkManagerRestart: Failed to schedule WorkManager restart", e)
            SkeddyLogger.e(TAG, "Failed to schedule WorkManager restart: ${e.message}")
        }
    }

    /**
     * Cancels any pending restart jobs.
     * Called when monitoring is intentionally stopped.
     */
    private fun cancelRestartJobs() {
        // Cancel JobScheduler job
        val jobScheduler = getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        jobScheduler.cancel(ServiceRestartJobService.JOB_ID)
        Log.d(TAG, "cancelRestartJobs: JobScheduler job cancelled")

        // Cancel WorkManager work
        try {
            WorkManager.getInstance(this).cancelUniqueWork("service_restart")
            Log.d(TAG, "cancelRestartJobs: WorkManager work cancelled")
        } catch (e: Exception) {
            Log.w(TAG, "cancelRestartJobs: Failed to cancel WorkManager work", e)
        }

        // Clear restart flags
        ServiceRestartJobService.clearMonitoringFlag(this)
    }

    // ==================== Low Memory Handling ====================

    /**
     * Called when the system is running low on memory.
     * Responds to different memory pressure levels by releasing resources
     * and persisting critical state.
     *
     * @param level The context of the trim, indicating how much memory should be released
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        val levelName = when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE"
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE"
            else -> "UNKNOWN($level)"
        }
        Log.w(TAG, "onTrimMemory: level=$levelName")
        SkeddyLogger.w(TAG, "onTrimMemory: level=$levelName")

        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                // System is running low on memory while app is running
                // Release non-critical resources and save state
                Log.w(TAG, "onTrimMemory: Running low/critical - saving state and clearing resources")
                SkeddyLogger.w(TAG, "Low memory while running - saving state")
                saveServiceState()
                clearNonCriticalResources()
            }

            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                // App UI is no longer visible, reduce memory footprint
                Log.d(TAG, "onTrimMemory: UI hidden - reducing memory footprint")
                reduceMemoryFootprint()
            }

            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                // App is in background, system needs memory
                Log.w(TAG, "onTrimMemory: Background/moderate - saving state")
                saveServiceState()
                clearNonCriticalResources()
            }

            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                // System is about to kill the process, save everything
                Log.e(TAG, "onTrimMemory: COMPLETE - emergency save, process may be killed")
                SkeddyLogger.e(TAG, "TRIM_MEMORY_COMPLETE - emergency state save")
                emergencySaveState()
            }
        }
    }

    /**
     * Called when the overall system is running low on memory.
     * This is a more severe situation than onTrimMemory.
     */
    override fun onLowMemory() {
        super.onLowMemory()
        Log.e(TAG, "onLowMemory: System critically low on memory - emergency save")
        SkeddyLogger.e(TAG, "onLowMemory: System critically low - emergency save")
        emergencySaveState()
        clearNonCriticalResources()
    }

    /**
     * Saves critical service state to SharedPreferences.
     * Called during low memory situations to preserve state across potential process death.
     */
    private fun saveServiceState() {
        val saveTime = System.currentTimeMillis()
        statePrefs.edit().apply {
            putBoolean(KEY_IS_MONITORING, isMonitoring)
            putInt(KEY_TOTAL_RIDES_FOUND, totalNewRidesFound)
            putInt(KEY_CONSECUTIVE_FAILURES, consecutiveFailures)
            putLong(KEY_LAST_SAVE_TIME, saveTime)
            apply()
        }
        Log.d(TAG, "saveServiceState: Saved state (isMonitoring=$isMonitoring, rides=$totalNewRidesFound, failures=$consecutiveFailures)")
    }

    /**
     * Emergency save - synchronous write for when process may be killed immediately.
     */
    private fun emergencySaveState() {
        val saveTime = System.currentTimeMillis()
        statePrefs.edit().apply {
            putBoolean(KEY_IS_MONITORING, isMonitoring)
            putInt(KEY_TOTAL_RIDES_FOUND, totalNewRidesFound)
            putInt(KEY_CONSECUTIVE_FAILURES, consecutiveFailures)
            putLong(KEY_LAST_SAVE_TIME, saveTime)
            commit() // Use commit() for synchronous write
        }
        Log.w(TAG, "emergencySaveState: Synchronously saved state")
    }

    /**
     * Restores service state from SharedPreferences.
     * Called in onCreate to recover from low memory process death.
     */
    private fun restoreServiceState() {
        val lastSaveTime = statePrefs.getLong(KEY_LAST_SAVE_TIME, 0)
        if (lastSaveTime == 0L) {
            Log.d(TAG, "restoreServiceState: No saved state found")
            return
        }

        // Only restore if saved within last 5 minutes (state might be stale otherwise)
        val ageMs = System.currentTimeMillis() - lastSaveTime
        val maxAgeMs = 5 * 60 * 1000L // 5 minutes

        if (ageMs > maxAgeMs) {
            Log.d(TAG, "restoreServiceState: Saved state too old (${ageMs}ms), ignoring")
            clearSavedState()
            return
        }

        val wasMonitoring = statePrefs.getBoolean(KEY_IS_MONITORING, false)
        val savedRidesFound = statePrefs.getInt(KEY_TOTAL_RIDES_FOUND, 0)
        val savedFailures = statePrefs.getInt(KEY_CONSECUTIVE_FAILURES, 0)

        Log.i(TAG, "restoreServiceState: Restoring state from ${ageMs}ms ago (wasMonitoring=$wasMonitoring, rides=$savedRidesFound, failures=$savedFailures)")
        SkeddyLogger.i(TAG, "Restored state after low memory: wasMonitoring=$wasMonitoring, rides=$savedRidesFound")

        // Restore counters
        totalNewRidesFound = savedRidesFound
        consecutiveFailures = savedFailures

        // Clear saved state after restoration
        clearSavedState()
    }

    /**
     * Clears saved state from SharedPreferences.
     */
    private fun clearSavedState() {
        statePrefs.edit().clear().apply()
        Log.d(TAG, "clearSavedState: Cleared saved state")
    }

    /**
     * Releases non-critical resources to free memory.
     * Called during low memory situations.
     */
    private fun clearNonCriticalResources() {
        Log.d(TAG, "clearNonCriticalResources: Releasing non-critical resources")

        // Force garbage collection hint (system may ignore)
        System.gc()

        // Note: AccessibilityService manages its own node references
        // RideParser doesn't cache parsed data
        // Repository uses Room which manages its own cache

        Log.d(TAG, "clearNonCriticalResources: Resources released, GC requested")
    }

    /**
     * Reduces memory footprint when UI is hidden.
     * Less aggressive than clearNonCriticalResources.
     */
    private fun reduceMemoryFootprint() {
        Log.d(TAG, "reduceMemoryFootprint: Reducing memory usage (UI hidden)")
        // Currently no UI-specific caches to clear
        // Service is headless, minimal footprint already
    }

    // ==================== Public Methods ====================

    /**
     * Checks if monitoring is currently active.
     * @return true if monitoring cycle is running, false otherwise
     */
    fun isMonitoringActive(): Boolean {
        return isMonitoring
    }

    /**
     * Returns the last known search active state from the server.
     * Used by bound clients (e.g., MainActivity) to sync button state on bind.
     */
    fun isSearchActiveFromServer(): Boolean = lastSearchActiveFromServer

    /**
     * Returns the current search interval in seconds (from server ping response).
     * Used by bound clients (e.g., MainActivity) to display interval on bind.
     */
    fun getCurrentIntervalSeconds(): Int = currentInterval

    /**
     * Starts the monitoring cycle.
     * Monitoring will run every [monitoringInterval] milliseconds.
     * If monitoring is already active, this call is ignored.
     */
    fun startMonitoring() {
        if (!isMonitoring) {
            isMonitoring = true
            isInitialPingCompleted = false
            totalNewRidesFound = 0 // Reset counter for new session
            consecutiveFailures = 0 // Reset failure counter for new session
            lastSearchActiveFromServer = false
            performInitialPing()
            handler.post(cleanupRunnable)
            broadcastMonitoringStatus(
                isRunning = true,
                ridesCount = 0,
                searchState = SEARCH_STATE_WAITING
            )

            // Mark monitoring as active for restart mechanisms
            ServiceRestartJobService.markMonitoringActive(this, true)
            BootCompletedReceiver.setAutoStartEnabled(this, true)

            Log.i(TAG, "startMonitoring: Monitoring started, initial ping dispatched")
            SkeddyLogger.i(TAG, "Monitoring started with initial ping, restart mechanisms enabled")
        } else {
            Log.w(TAG, "startMonitoring: Monitoring already active, ignoring")
        }
    }

    /**
     * Stops the monitoring cycle.
     * Removes pending callbacks, cancels running coroutines, and resets state.
     */
    fun stopMonitoring() {
        if (isMonitoring) {
            isMonitoring = false
            isInitialPingCompleted = false
            handler.removeCallbacks(monitoringRunnable)
            handler.removeCallbacks(retryPingRunnable)
            handler.removeCallbacks(cleanupRunnable)
            cancelAlarmWakeup()
            coroutineScope.coroutineContext.cancelChildren()
            consecutiveFailures = 0 // Reset failure counter
            lastSearchActiveFromServer = false
            isServerOffline = false
            broadcastMonitoringStatus(
                isRunning = false,
                ridesCount = totalNewRidesFound,
                searchState = SEARCH_STATE_STOPPED
            )

            // Clear restart flags and cancel pending restart jobs
            cancelRestartJobs()
            BootCompletedReceiver.setAutoStartEnabled(this, false)

            Log.i(TAG, "stopMonitoring: Monitoring stopped")
            SkeddyLogger.i(TAG, "Monitoring stopped, restart mechanisms disabled")
        } else {
            Log.d(TAG, "stopMonitoring: Monitoring was not active")
        }
    }

    /**
     * Sets the monitoring interval.
     * Changes take effect after the current cycle completes.
     *
     * @param intervalMs New interval in milliseconds (minimum 5000ms)
     */
    fun setMonitoringInterval(intervalMs: Long) {
        val newInterval = intervalMs.coerceAtLeast(5000L)
        Log.i(TAG, "setMonitoringInterval: Changing interval from ${monitoringInterval}ms to ${newInterval}ms")
        monitoringInterval = newInterval
    }

    /**
     * Gets the current monitoring interval.
     * @return Current interval in milliseconds
     */
    fun getMonitoringInterval(): Long {
        return monitoringInterval
    }

    /**
     * Gets the current consecutive failure count.
     * @return Number of consecutive monitoring cycle failures
     */
    fun getConsecutiveFailures(): Int {
        return consecutiveFailures
    }

    /**
     * Checks if the device is currently in Doze mode.
     * @return true if device is in Doze mode, false otherwise
     */
    fun isDeviceInDozeMode(): Boolean {
        return powerManager.isDeviceIdleMode
    }

    /**
     * Handles force update flag from ping response.
     * Only broadcasts on state transitions (entering or leaving force update).
     * Does NOT broadcast on every ping to avoid redundant UI updates.
     *
     * @param forceUpdate Whether the server requires an app update
     * @param updateUrl URL for the app update page (may be null)
     */
    fun handlePingForceUpdate(forceUpdate: Boolean, updateUrl: String?) {
        if (forceUpdate && !isInForceUpdateState) {
            // Entering force update state
            isInForceUpdateState = true
            preferences.forceUpdateActive = true
            preferences.forceUpdateUrl = updateUrl
            broadcastForceUpdate(updateUrl)
        } else if (forceUpdate && isInForceUpdateState) {
            // Already in force update — silently update URL if changed
            preferences.forceUpdateUrl = updateUrl
        } else if (!forceUpdate && isInForceUpdateState) {
            // Leaving force update state
            isInForceUpdateState = false
            preferences.forceUpdateActive = false
            preferences.forceUpdateUrl = null
            broadcastForceUpdateCleared()
        }
        // !forceUpdate && !isInForceUpdateState → no-op
    }

    /**
     * Checks if the service is currently in force update state.
     * @return true if force update is active
     */
    fun isInForceUpdateState(): Boolean = isInForceUpdateState

    // ==================== Doze Mode and AlarmManager Methods ====================

    /**
     * Registers the Doze mode broadcast receiver.
     * Called during service creation to listen for idle mode changes.
     */
    private fun registerDozeReceiver() {
        val filter = IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(dozeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(dozeReceiver, filter)
        }
        Log.d(TAG, "registerDozeReceiver: Doze receiver registered")
    }

    /**
     * Unregisters the Doze mode broadcast receiver.
     * Called during service destruction.
     */
    private fun unregisterDozeReceiver() {
        try {
            unregisterReceiver(dozeReceiver)
            Log.d(TAG, "unregisterDozeReceiver: Doze receiver unregistered")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "unregisterDozeReceiver: Receiver was not registered: ${e.message}")
        }
    }

    /**
     * Registers the AlarmManager wakeup broadcast receiver.
     * Called during service creation to receive alarm wakeup intents.
     */
    private fun registerAlarmReceiver() {
        val filter = IntentFilter(ACTION_ALARM_WAKEUP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(alarmReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(alarmReceiver, filter)
        }
        Log.d(TAG, "registerAlarmReceiver: Alarm receiver registered")
    }

    /**
     * Unregisters the AlarmManager wakeup broadcast receiver.
     * Called during service destruction.
     */
    private fun unregisterAlarmReceiver() {
        try {
            unregisterReceiver(alarmReceiver)
            Log.d(TAG, "unregisterAlarmReceiver: Alarm receiver unregistered")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "unregisterAlarmReceiver: Receiver was not registered: ${e.message}")
        }
    }

    /**
     * Schedules the next ping cycle after [delayMs] milliseconds.
     *
     * Uses different scheduling mechanisms based on device state:
     * - Normal mode: Handler.postDelayed for efficiency
     * - Doze mode: AlarmManager.setExactAndAllowWhileIdle for reliability
     *
     * Called from ping response handlers with handler-specific delay
     * (e.g. 30s for network error, 60s for server error, interval_seconds for success).
     *
     * @param delayMs Delay in milliseconds before the next ping
     */
    internal fun scheduleNextPing(delayMs: Long) {
        if (!isMonitoring) {
            Log.d(TAG, "scheduleNextPing: Monitoring stopped, not scheduling")
            return
        }

        isInDozeMode = powerManager.isDeviceIdleMode

        if (isInDozeMode) {
            Log.d(TAG, "scheduleNextPing: Device in Doze mode, using AlarmManager (${delayMs}ms)")
            scheduleAlarmWakeup(delayMs)
        } else {
            Log.d(TAG, "scheduleNextPing: Normal mode, using Handler (${delayMs}ms)")
            handler.postDelayed(monitoringRunnable, delayMs)
        }
    }

    /**
     * Schedules a retry ping after a network/server error via [retryPingRunnable].
     *
     * Cancels any previously scheduled [retryPingRunnable] to prevent duplicate
     * callbacks when called multiple times (e.g. rapid consecutive errors).
     *
     * When the retry fires, [monitoringCycleWithPing] runs a new ping attempt.
     * If the ping succeeds, [handlePingSuccess] restores the search state
     * automatically (auto-resume). If it fails again, another retry is scheduled.
     *
     * @param delayMs Delay in milliseconds before the retry ping
     */
    internal fun scheduleRetryPing(delayMs: Long) {
        if (!isMonitoring) {
            Log.d(TAG, "scheduleRetryPing: Monitoring stopped, not scheduling")
            return
        }

        handler.removeCallbacks(retryPingRunnable)
        handler.postDelayed(retryPingRunnable, delayMs)
        Log.i(TAG, "scheduleRetryPing: Scheduled retry in ${delayMs}ms")
        SkeddyLogger.i(TAG, "Retry ping scheduled in ${delayMs / 1000}s")
    }

    /**
     * Schedules an alarm wakeup for the next ping cycle.
     * Uses setExactAndAllowWhileIdle to work during Doze mode.
     *
     * @param delayMs Delay in milliseconds before the alarm fires
     */
    private fun scheduleAlarmWakeup(delayMs: Long) {
        val intent = Intent(ACTION_ALARM_WAKEUP).apply {
            setPackage(packageName)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val triggerTime = System.currentTimeMillis() + delayMs

        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerTime,
            pendingIntent
        )

        Log.d(TAG, "scheduleAlarmWakeup: Alarm scheduled for ${delayMs}ms from now")
        SkeddyLogger.d(TAG, "Alarm wakeup scheduled (Doze mode active)")
    }

    /**
     * Cancels any pending alarm wakeup.
     * Called when stopping monitoring or when service is destroyed.
     */
    private fun cancelAlarmWakeup() {
        val intent = Intent(ACTION_ALARM_WAKEUP).apply {
            setPackage(packageName)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
        )

        pendingIntent?.let {
            alarmManager.cancel(it)
            it.cancel()
            Log.d(TAG, "cancelAlarmWakeup: Alarm cancelled")
        }
    }

    // ==================== Screen State Methods ====================

    /**
     * Registers the screen state broadcast receiver.
     * Listens for ACTION_SCREEN_OFF and ACTION_SCREEN_ON intents.
     * Called during service creation.
     */
    private fun registerScreenStateReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenStateReceiver, filter)
        }
        Log.d(TAG, "registerScreenStateReceiver: Screen state receiver registered")
    }

    /**
     * Unregisters the screen state broadcast receiver.
     * Called during service destruction.
     */
    private fun unregisterScreenStateReceiver() {
        try {
            unregisterReceiver(screenStateReceiver)
            Log.d(TAG, "unregisterScreenStateReceiver: Screen state receiver unregistered")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "unregisterScreenStateReceiver: Receiver was not registered: ${e.message}")
        }
    }

    /**
     * Handles screen turning off.
     * Pauses monitoring to save battery since Lyft UI is unavailable when screen is off.
     */
    private fun handleScreenOff() {
        isScreenOff = true

        if (isMonitoring) {
            wasMonitoringBeforeScreenOff = true
            Log.i(TAG, "handleScreenOff: Pausing monitoring (screen off, Lyft UI unavailable)")
            SkeddyLogger.i(TAG, "Screen OFF - pausing monitoring to save battery")

            // Remove pending Handler callbacks
            handler.removeCallbacks(monitoringRunnable)
            handler.removeCallbacks(retryPingRunnable)

            // Cancel any pending alarm wakeups
            cancelAlarmWakeup()

            // Cancel running coroutines
            coroutineScope.coroutineContext.cancelChildren()

            Log.d(TAG, "handleScreenOff: Monitoring paused, will resume when screen turns on")
        } else {
            wasMonitoringBeforeScreenOff = false
            Log.d(TAG, "handleScreenOff: Monitoring was not active, nothing to pause")
        }
    }

    /**
     * Handles screen turning on.
     * Resumes monitoring if it was active before screen was turned off.
     */
    private fun handleScreenOn() {
        isScreenOff = false

        if (wasMonitoringBeforeScreenOff && isMonitoring) {
            Log.i(TAG, "handleScreenOn: Resuming monitoring (screen on)")
            SkeddyLogger.i(TAG, "Screen ON - resuming monitoring")

            // Schedule the next monitoring cycle immediately
            handler.post(monitoringRunnable)

            Log.d(TAG, "handleScreenOn: Monitoring resumed")
        } else if (wasMonitoringBeforeScreenOff && !isMonitoring) {
            // This shouldn't happen normally, but handle gracefully
            Log.w(TAG, "handleScreenOn: Was monitoring before screen off but isMonitoring is false")
        } else {
            Log.d(TAG, "handleScreenOn: Monitoring was not active before screen off, not resuming")
        }

        // Reset the flag
        wasMonitoringBeforeScreenOff = false
    }

    /**
     * Checks if monitoring is currently paused due to screen being off.
     * @return true if screen is off and monitoring was paused, false otherwise
     */
    fun isMonitoringPausedForScreenOff(): Boolean {
        return isScreenOff && wasMonitoringBeforeScreenOff
    }

    /**
     * Checks if screen is currently off.
     * @return true if screen is off, false otherwise
     */
    fun isScreenCurrentlyOff(): Boolean {
        return isScreenOff
    }

    // ==================== Monitoring Methods ====================

    /**
     * Performs blacklist cleanup: removes expired entries from the database.
     * Called periodically by [cleanupRunnable] every [CLEANUP_INTERVAL_MS].
     * Exceptions are caught and logged — they must not break the cleanup schedule.
     */
    private suspend fun performBlacklistCleanup() {
        try {
            val count = blacklistRepository.cleanupExpiredRides()
            if (count > 0) {
                Log.i(TAG, "Blacklist cleanup: removed $count expired entries")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Blacklist cleanup failed", e)
        }
    }

    /**
     * Builds a [PingRequest] with current device state for the server.
     *
     * Uses [DeviceHealthCollector] for device health (accessibility, Lyft, screen),
     * real values for timezone, app version, and accumulated stats.
     */
    internal suspend fun buildPingRequest(): PingRequest {
        return PingRequest(
            timezone = java.util.TimeZone.getDefault().id,
            appVersion = BuildConfig.VERSION_NAME,
            deviceHealth = deviceHealthCollector.collect(),
            stats = pendingStats.toPingStats(),
            lastCycleDurationMs = lastCycleDurationMs?.toInt(),
            rideStatuses = pendingVerification.toRideStatusList(),
            location = locationCollector.collect()
        )
    }

    // ==================== Ping Cycle Methods ====================

    /**
     * Performs an initial ping to synchronize service state with the server.
     *
     * Called at service startup (DEF-16) to determine whether the server expects
     * search to be active. This eliminates the behavior where the service always
     * starts in "Stopped" state regardless of server-side is_active flag.
     *
     * If the server responds with search=true, [handlePingSuccess] starts the
     * search cycle immediately. If search=false, only the next ping is scheduled.
     *
     * When the ping fails (network error, server error, or unexpected exception),
     * falls back to [restoreSearchStateFromPreferences] to restore the last known
     * search state from SharedPreferences (task 2.3).
     *
     * Delegates all response handling to existing handlers for consistent behavior.
     * Does not check Accessibility Service — that is verified in [monitoringCycleWithPing]
     * during subsequent monitoring cycles.
     *
     * Requires a valid device token. If the device is not paired, skips the ping
     * and schedules the next monitoring cycle with the default interval.
     */
    internal fun performInitialPing() {
        if (!deviceTokenManager.isLoggedIn()) {
            Log.w(TAG, "performInitialPing: No device token, skipping initial ping")
            isInitialPingCompleted = true
            scheduleNextPing(currentInterval * 1000L)
            return
        }

        Log.i(TAG, "performInitialPing: Sending initial ping to synchronize with server")
        coroutineScope.launch {
            var pingSucceeded = false
            try {
                val pingRequest = buildPingRequest()
                val pingResult = serverClient.ping(pingRequest)

                when (pingResult) {
                    is ApiResult.Success -> {
                        handlePingSuccess(pingResult.data)
                        pingSucceeded = true
                    }
                    is ApiResult.Unauthorized -> handleUnauthorized()
                    is ApiResult.NetworkError -> handleNetworkError()
                    is ApiResult.ValidationError -> handleValidationError(pingResult.message)
                    is ApiResult.RateLimited -> handleRateLimited(pingResult.retryAfterSeconds)
                    is ApiResult.ServiceUnavailable -> handleServiceUnavailable()
                    is ApiResult.ServerError -> handleServerError()
                    is ApiResult.LoginError -> handleServerError()
                }
            } catch (e: Exception) {
                Log.e(TAG, "performInitialPing: Unexpected error", e)
                SkeddyLogger.e(TAG, "Unexpected error during initial ping: ${e.message}")
                scheduleNextPing(currentInterval * 1000L)
            } finally {
                if (!pingSucceeded && isMonitoring) {
                    restoreSearchStateFromPreferences()
                }
                isInitialPingCompleted = true
                Log.d(TAG, "performInitialPing: Initial ping completed (success=$pingSucceeded)")
            }
        }
    }

    /**
     * Restores search state from SharedPreferences as a fallback when the initial
     * ping fails to reach the server (DEF-16, task 2.3).
     *
     * Only restores if:
     * - The cached state indicates search was active ([SkeddyPreferences.wasSearching])
     * - The cached state is not older than 24 hours
     *
     * When restored, updates [lastSearchActiveFromServer] and broadcasts
     * [SEARCH_STATE_SEARCHING] so the UI reflects the cached state. The next
     * successful ping will sync the actual state from the server.
     */
    internal fun restoreSearchStateFromPreferences() {
        val wasSearching = preferences.wasSearching
        val lastStateTime = preferences.lastSearchStateTime
        val ageMs = if (lastStateTime > 0) System.currentTimeMillis() - lastStateTime else Long.MAX_VALUE
        val maxAgeMs = 24 * 60 * 60 * 1000L // 24 hours

        Log.d(TAG, "restoreSearchStateFromPreferences: wasSearching=$wasSearching, age=${ageMs / 3_600_000}h")

        if (wasSearching && lastStateTime > 0 && ageMs < maxAgeMs) {
            Log.i(TAG, "restoreSearchStateFromPreferences: Restoring search state (age=${ageMs / 3_600_000}h)")
            SkeddyLogger.i(TAG, "Initial ping failed — restoring search state from preferences (age=${ageMs / 3_600_000}h)")
            lastSearchActiveFromServer = true
            broadcastMonitoringStatus(
                isRunning = true,
                ridesCount = totalNewRidesFound,
                isSearchActive = true,
                searchState = SEARCH_STATE_SEARCHING
            )
        } else {
            Log.d(TAG, "restoreSearchStateFromPreferences: No valid cached search state to restore")
        }
    }

    /**
     * Main monitoring cycle entry point: pings the server before each search cycle.
     *
     * Flow:
     * 1. Check Accessibility Service — stop if disabled (NOT_CONFIGURED state)
     * 2. Build and send ping request to server
     * 3. Dispatch based on [ApiResult] to appropriate handler
     *
     * Each handler is responsible for scheduling the next ping via [scheduleNextPing].
     * Unexpected exceptions are caught to prevent monitoring from stopping silently.
     */
    internal suspend fun monitoringCycleWithPing() {
        // Step 1: Check Accessibility Service — stop if disabled
        if (!PermissionUtils.isAccessibilityServiceEnabled(this, SkeddyAccessibilityService::class.java)) {
            Log.w(TAG, "monitoringCycleWithPing: Accessibility Service disabled, stopping service (NOT_CONFIGURED)")
            SkeddyLogger.w(TAG, "Accessibility Service disabled — stopping service (NOT_CONFIGURED state)")
            stopMonitoring()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        // Step 2: Build and send ping request
        try {
            val pingRequest = buildPingRequest()
            val pingResult = serverClient.ping(pingRequest)

            // Step 3: Dispatch based on result
            when (pingResult) {
                is ApiResult.Success -> handlePingSuccess(pingResult.data)
                is ApiResult.Unauthorized -> handleUnauthorized()
                is ApiResult.NetworkError -> handleNetworkError()
                is ApiResult.ValidationError -> handleValidationError(pingResult.message)
                is ApiResult.RateLimited -> handleRateLimited(pingResult.retryAfterSeconds)
                is ApiResult.ServiceUnavailable -> handleServiceUnavailable()
                is ApiResult.ServerError -> handleServerError()
                is ApiResult.LoginError -> handleServerError() // Should not happen for ping; safety fallback
            }
        } catch (e: Exception) {
            Log.e(TAG, "monitoringCycleWithPing: Unexpected error during ping cycle", e)
            SkeddyLogger.e(TAG, "Unexpected error during ping cycle: ${e.message}")
            // Schedule retry to prevent monitoring from stopping silently
            scheduleNextPing(currentInterval * 1000L)
        }
    }

    /**
     * Handles successful ping response.
     *
     * Updates server-provided state (interval, min_price), resets stats accumulator,
     * drains pending ride queue, updates foreground notification, and decides
     * whether to execute a search cycle based on server response.
     *
     * [processPendingRideQueue] is called on **every** successful ping regardless
     * of force_update or search state, ensuring eventual delivery of queued ride
     * reports even when search is paused.
     */
    internal suspend fun handlePingSuccess(response: PingResponse) {
        Log.i(TAG, "handlePingSuccess: search=${response.search}, interval=${response.intervalSeconds}s, " +
                "minPrice=${response.filters.minPrice}, forceUpdate=${response.forceUpdate}")

        // Auto-resume: clear offline flag when server becomes reachable again
        if (isServerOffline) {
            Log.i(TAG, "handlePingSuccess: Server back online, clearing offline state")
            SkeddyLogger.i(TAG, "Server back online — auto-resuming")
            isServerOffline = false
        }

        // Update server-provided state
        currentInterval = response.intervalSeconds
        currentMinPrice = response.filters.minPrice
        monitoringInterval = currentInterval * 1000L

        // Handle force update state transitions
        handlePingForceUpdate(response.forceUpdate, response.updateUrl)

        // Reset stats and verification results after successful ping
        pendingStats.reset()
        pendingVerification.reset()

        // Store new verification hashes from server response
        if (response.verifyRides.isNotEmpty()) {
            pendingVerification.setPendingVerification(response.verifyRides.map { it.rideHash })
            Log.d(TAG, "handlePingSuccess: ${response.verifyRides.size} rides to verify")
        }

        // Log reason when search is stopped by server
        if (response.reason != null) {
            Log.w(TAG, "handlePingSuccess: search stopped, reason=${response.reason}")
        }

        // Drain pending ride reports on ANY successful ping (PRD: after next successful ping)
        processPendingRideQueue()

        // Update foreground notification with current search state
        val isSearching = response.search && !response.forceUpdate
        updateNotification(isSearching)

        // Persist search state for offline fallback (DEF-16, task 2.3)
        preferences.saveSearchState(isSearching)

        // Bring app to foreground when search transitions from active to stopped
        // (e.g., user pressed Stop on Main App, or schedule ended while Lyft was in foreground)
        if (lastSearchActiveFromServer && !isSearching) {
            bringAppToForeground()
        }

        // Track server search state for bound clients and broadcast to UI
        lastSearchActiveFromServer = isSearching
        lastCheckTime = System.currentTimeMillis()
        broadcastMonitoringStatus(
            isRunning = true,
            lastCheckTime = lastCheckTime,
            ridesCount = totalNewRidesFound,
            isSearchActive = isSearching,
            searchState = if (isSearching) SEARCH_STATE_SEARCHING else SEARCH_STATE_STOPPED
        )

        if (response.forceUpdate) {
            Log.i(TAG, "handlePingSuccess: Force update required, skipping search")
            scheduleNextPing(currentInterval * 1000L)
            return
        }

        if (!response.search) {
            Log.i(TAG, "handlePingSuccess: Server says no search, waiting ${currentInterval}s")
            scheduleNextPing(currentInterval * 1000L)
            return
        }

        // Resolve search flow strategy (server override > local preference > CLASSIC)
        resolveAndApplySearchFlow(response.searchFlow)

        // Execute search cycle via active strategy
        executeSearchCycleViaStrategy()
        scheduleNextPing(currentInterval * 1000L)
    }

    /**
     * Drains the [PendingRideQueue] by sending each queued ride report to the server.
     *
     * Called on every successful ping. Successfully sent reports are removed from
     * the queue; failed reports remain and will be retried on the next successful ping.
     *
     * Cleanup of expired entries happens before processing to avoid transmitting stale data.
     */
    internal suspend fun processPendingRideQueue() {
        pendingRideQueue.cleanupExpired()
        val pending = pendingRideQueue.dequeueAll()
        if (pending.isEmpty()) {
            return
        }

        Log.i(TAG, "processPendingRideQueue: Processing ${pending.size} pending ride report(s)")
        for (request in pending) {
            val result = serverClient.reportRide(request)
            if (result is ApiResult.Success) {
                pendingRideQueue.remove(request)
                Log.i(TAG, "processPendingRideQueue: Sent ride report key=${request.idempotencyKey}")
            } else {
                Log.w(TAG, "processPendingRideQueue: Failed to send key=${request.idempotencyKey}, will retry next ping")
            }
        }
    }

    /**
     * Handles 401/403 Unauthorized response from server.
     *
     * Clears device token, blacklist, and pending ride queue, then stops
     * the service and broadcasts [ACTION_UNPAIRED] so the UI layer
     * transitions to the login screen.
     * No retry — user must re-login.
     *
     * Note: [SkeddyServerClient] already calls [DeviceTokenManager.clearDeviceToken]
     * before returning [ApiResult.Unauthorized], so the call here is idempotent.
     */
    internal suspend fun handleUnauthorized() {
        Log.w(TAG, "handleUnauthorized: Server returned 401/403, clearing token")
        SkeddyLogger.w(TAG, "Unauthorized — clearing device token, stopping service")
        deviceTokenManager.clearDeviceToken()

        // Clear ride data from the old login context (DEF-23)
        try {
            blacklistRepository.clearAll()
            Log.d(TAG, "handleUnauthorized: blacklist cleared")
        } catch (e: Exception) {
            Log.e(TAG, "handleUnauthorized: failed to clear blacklist", e)
        }
        pendingRideQueue.clear()
        Log.d(TAG, "handleUnauthorized: pending ride queue cleared")

        stopMonitoring()
        notifyUnpaired()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Handles network errors (timeout, no connection).
     *
     * Stops searching (updates notification/broadcast) and schedules retry
     * after 30 seconds. Auto-resume: when the next ping succeeds,
     * [handlePingSuccess] restores the search state automatically.
     */
    internal fun handleNetworkError() {
        Log.w(TAG, "handleNetworkError: Network error, retrying in ${RETRY_PING_DELAY_MS / 1000}s")
        SkeddyLogger.w(TAG, "Network error during ping — retry in ${RETRY_PING_DELAY_MS / 1000}s")
        isServerOffline = true
        stopSearching()
        scheduleRetryPing(RETRY_PING_DELAY_MS)
    }

    /**
     * Handles 422 Validation Error response.
     *
     * Logs warning and schedules retry after 60 seconds.
     * Does NOT stop searching — validation errors relate to request data
     * (e.g. invalid timezone), not server availability.
     */
    internal fun handleValidationError(message: String) {
        Log.w(TAG, "handleValidationError: $message, retrying in 60s")
        SkeddyLogger.w(TAG, "Validation error during ping: $message — retry in 60s")
        scheduleNextPing(60_000L)
    }

    /**
     * Handles 429 Rate Limited response.
     *
     * Stops searching and schedules retry after server-specified delay
     * (from Retry-After header, parsed in [SkeddyServerClient]).
     */
    internal fun handleRateLimited(retryAfterSeconds: Int) {
        val retryMs = retryAfterSeconds * 1000L
        Log.w(TAG, "handleRateLimited: Rate limited, retrying in ${retryAfterSeconds}s")
        SkeddyLogger.w(TAG, "Rate limited during ping — retry in ${retryAfterSeconds}s")
        stopSearching()
        scheduleNextPing(retryMs)
    }

    /**
     * Handles 503 Service Unavailable response (e.g. Redis down).
     *
     * Sets server offline flag, stops searching, and schedules retry after 60 seconds.
     * Uses [scheduleRetryPing] (same as [handleServerError]) so that the retry
     * mechanism properly handles consecutive failures and auto-resume.
     */
    internal fun handleServiceUnavailable() {
        Log.w(TAG, "handleServiceUnavailable: Server unavailable, retrying in 60s")
        SkeddyLogger.w(TAG, "Service unavailable during ping — retry in 60s")
        isServerOffline = true
        stopSearching()
        scheduleRetryPing(60_000L)
    }

    /**
     * Handles 5xx Server Error response.
     *
     * Stops searching and schedules retry after 60 seconds.
     */
    internal fun handleServerError() {
        Log.w(TAG, "handleServerError: Server error, retrying in 60s")
        SkeddyLogger.w(TAG, "Server error during ping — retry in 60s")
        isServerOffline = true
        stopSearching()
        scheduleRetryPing(60_000L)
    }

    /**
     * Updates UI to reflect that search is paused due to a server/network error.
     *
     * Updates the foreground notification to "Stopped" and broadcasts
     * server-offline status so the Main Screen can display "Server offline".
     *
     * The service itself keeps running (isMonitoring remains true) —
     * the next successful ping in [handlePingSuccess] will automatically
     * resume search and restore the notification (auto-resume).
     */
    private fun stopSearching() {
        updateNotification(searching = false)
        broadcastMonitoringStatus(
            isRunning = true,
            ridesCount = totalNewRidesFound,
            serverOffline = true,
            searchState = SEARCH_STATE_OFFLINE
        )
    }

    /**
     * Broadcasts [ACTION_UNPAIRED] to notify the UI layer that the device token
     * has been invalidated (401/403). The UI should transition to the login screen.
     */
    private fun notifyUnpaired() {
        val intent = Intent(ACTION_UNPAIRED).apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)
        Log.i(TAG, "notifyUnpaired: Unpaired broadcast sent")
    }

    // ==================== Search Flow Strategy Methods ====================

    /**
     * Resolves the search flow type from server override or local preference,
     * and switches the active strategy if needed.
     *
     * Priority: server override > local preference > CLASSIC
     *
     * @param serverOverride server-provided flow type (null = use local)
     */
    private suspend fun resolveAndApplySearchFlow(serverOverride: String?) {
        val flowName = serverOverride ?: preferences.searchFlowType
        val targetType = try {
            SearchFlowType.valueOf(flowName.uppercase())
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "resolveAndApplySearchFlow: Unknown flow type '$flowName', defaulting to CLASSIC")
            SearchFlowType.CLASSIC
        }

        if (activeStrategy.type != targetType) {
            Log.i(TAG, "resolveAndApplySearchFlow: Switching from ${activeStrategy.type} to $targetType")
            SkeddyLogger.i(TAG, "Search flow switch: ${activeStrategy.type} -> $targetType")

            // Deactivate old strategy
            if (strategyActivated) {
                val context = buildSearchFlowContext()
                if (context != null) {
                    activeStrategy.onDeactivated(context)
                }
            }

            // Create new strategy
            activeStrategy = when (targetType) {
                SearchFlowType.CLASSIC -> ClassicSearchFlow()
                SearchFlowType.OPTIMIZED -> OptimizedSearchFlow()
            }
            strategyActivated = false
        }
    }

    /**
     * Builds a [SearchFlowContext] with all dependencies for the active strategy.
     *
     * @return context or null if AccessibilityService is unavailable
     */
    private fun buildSearchFlowContext(): SearchFlowContext? {
        val accessibilityService = SkeddyAccessibilityService.getInstance() ?: return null
        val navigator = LyftNavigator(accessibilityService)

        return SearchFlowContext(
            appContext = this,
            accessibilityService = accessibilityService,
            navigator = navigator,
            rideFilter = rideFilter,
            serverClient = serverClient,
            blacklistRepository = blacklistRepository,
            pendingRideQueue = pendingRideQueue,
            pendingStats = pendingStats,
            pendingVerification = pendingVerification,
            preferences = preferences,
            currentMinPrice = currentMinPrice,
            handler = handler
        )
    }

    /**
     * Executes the search cycle via the active strategy, with retry and fallback logic.
     *
     * On success: resets failure counter, updates stats.
     * On failure with shouldFallback: switches to ClassicSearchFlow and retries.
     * On repeated failures: pauses monitoring.
     */
    private suspend fun executeSearchCycleViaStrategy() {
        try {
            val context = buildSearchFlowContext()
            if (context == null) {
                // No AccessibilityService — treat as no-op success (same as legacy behavior).
                // Legacy executeSearchCycle() returned early without exception,
                // which caused executeSearchCycleWithRetry() to reset consecutiveFailures.
                Log.e(TAG, "executeSearchCycleViaStrategy: AccessibilityService not available")
                consecutiveFailures = 0
                pendingStats.incrementCycles()
                return
            }

            // Activate strategy if not yet activated
            if (!strategyActivated) {
                activeStrategy.onActivated(context)
                strategyActivated = true
            }

            val result = activeStrategy.execute(context)

            when (result) {
                is SearchFlowResult.Success -> {
                    if (consecutiveFailures > 0) {
                        Log.i(TAG, "executeSearchCycleViaStrategy: Cycle succeeded after $consecutiveFailures failures, resetting counter")
                    }
                    consecutiveFailures = 0
                    totalNewRidesFound += result.acceptedRidesCount

                    // Update notification
                    updateNotification(searching = true)

                    // Broadcast status update
                    lastCheckTime = System.currentTimeMillis()
                    broadcastMonitoringStatus(
                        isRunning = true,
                        lastCheckTime = lastCheckTime,
                        ridesCount = totalNewRidesFound,
                        isSearchActive = true,
                        searchState = SEARCH_STATE_SEARCHING
                    )

                    lastCycleDurationMs = System.currentTimeMillis() - (lastCheckTime - 1)
                }
                is SearchFlowResult.Failure -> {
                    Log.e(TAG, "executeSearchCycleViaStrategy: ${activeStrategy.name} failed: ${result.reason}")
                    SkeddyLogger.e(TAG, "${activeStrategy.name} failed: ${result.reason}")

                    // Fallback to ClassicSearchFlow if the optimized flow suggests it
                    if (result.shouldFallback && activeStrategy.type != SearchFlowType.CLASSIC) {
                        Log.w(TAG, "executeSearchCycleViaStrategy: Falling back to ClassicSearchFlow")
                        SkeddyLogger.w(TAG, "Falling back to ClassicSearchFlow")
                        activeStrategy = ClassicSearchFlow()
                        strategyActivated = false

                        // Retry with classic flow
                        activeStrategy.onActivated(context)
                        strategyActivated = true
                        val retryResult = activeStrategy.execute(context)

                        when (retryResult) {
                            is SearchFlowResult.Success -> {
                                consecutiveFailures = 0
                                totalNewRidesFound += retryResult.acceptedRidesCount
                                updateNotification(searching = true)
                                lastCheckTime = System.currentTimeMillis()
                                broadcastMonitoringStatus(
                                    isRunning = true,
                                    lastCheckTime = lastCheckTime,
                                    ridesCount = totalNewRidesFound,
                                    isSearchActive = true,
                                    searchState = SEARCH_STATE_SEARCHING
                                )
                            }
                            is SearchFlowResult.Failure -> {
                                consecutiveFailures++
                                Log.e(TAG, "executeSearchCycleViaStrategy: Fallback also failed: ${retryResult.reason}")
                                handleConsecutiveFailures()
                            }
                        }
                    } else {
                        consecutiveFailures++
                        handleConsecutiveFailures()
                    }
                }
            }
        } catch (e: Exception) {
            consecutiveFailures++
            val error = categorizeError(e)
            Log.e(TAG, "executeSearchCycleViaStrategy: Exception ($consecutiveFailures/$maxConsecutiveFailures) - ${error.message}", e)
            handleConsecutiveFailures()
            tryRecovery()
        }
        pendingStats.incrementCycles()
    }

    /**
     * Handles consecutive failure count — pauses monitoring if threshold exceeded.
     */
    private suspend fun handleConsecutiveFailures() {
        if (consecutiveFailures >= maxConsecutiveFailures) {
            Log.e(TAG, "handleConsecutiveFailures: Too many consecutive failures, pausing monitoring")
            notifyError("Моніторинг призупинено через ${consecutiveFailures} помилок підряд")
            delay(monitoringInterval * 2)
            consecutiveFailures = 0
            Log.i(TAG, "handleConsecutiveFailures: Resuming after pause, counter reset")
        }
    }

    // ==================== Legacy Search Cycle Methods ====================

    /**
     * Executes a single search cycle: navigates Lyft, parses rides, filters, and auto-accepts.
     *
     * Called by [handlePingSuccess] after a successful ping with `search: true`.
     * Accessibility Service availability is checked in [monitoringCycleWithPing] before
     * the ping is sent. Force update state is checked in [handlePingSuccess].
     *
     * Full cycle includes:
     * 1. Auto-recovery: ensure Lyft is active and on main screen
     * 2. Navigate to side menu
     * 3. Navigate to Scheduled Rides
     * 4. Capture UI hierarchy
     * 5. Parse rides from UI
     * 6. Filter high-value rides (cascading: $10 hardcoded → server min_price → blacklist)
     * 7. Save new rides to database / auto-accept
     * 8. Navigate back to main screen
     * 9. Update notification with new rides count
     */
    private suspend fun executeSearchCycle() {
        val cycleStartTime = System.currentTimeMillis()
        Log.i(TAG, "executeSearchCycle: ========== STARTING CYCLE ==========")
        SkeddyLogger.i(TAG, "========== STARTING SEARCH CYCLE ==========")

        // Step 1: Auto-recovery - ensure Lyft is active and on main screen
        val recoveryManager = AutoRecoveryManager.create(this)
        if (recoveryManager == null) {
            Log.e(TAG, "executeSearchCycle: AutoRecoveryManager not available (AccessibilityService inactive)")
            SkeddyLogger.e(TAG, "AutoRecoveryManager not available - AccessibilityService inactive")
            return
        }

        Log.d(TAG, "executeSearchCycle: Step 1 - Running auto-recovery...")
        SkeddyLogger.d(TAG, "Step 1 - Running auto-recovery (prepareForMonitoring)")

        val prepareResult = recoveryManager.prepareForMonitoring()
        if (prepareResult.isFailure) {
            val failure = prepareResult as com.skeddy.util.RetryResult.Failure
            Log.e(TAG, "executeSearchCycle: Auto-recovery failed: ${failure.error.message}")
            SkeddyLogger.e(TAG, "Auto-recovery failed: ${failure.error.code} - ${failure.error.message}")
            return
        }

        Log.d(TAG, "executeSearchCycle: Step 1 completed - Ready for monitoring")
        SkeddyLogger.i(TAG, "Step 1 completed - Lyft active and on main screen")

        // Get AccessibilityService for navigation
        val accessibilityService = SkeddyAccessibilityService.getInstance()
        if (accessibilityService == null) {
            Log.e(TAG, "executeSearchCycle: AccessibilityService not available after recovery")
            return
        }

        val navigator = LyftNavigator(accessibilityService)

        // Step 2: Navigate to side menu
        Log.d(TAG, "executeSearchCycle: Step 2 - Opening side menu...")
        SkeddyLogger.d(TAG, "Step 2 - Opening side menu")
        if (!navigator.safeNavigateToMenu()) {
            Log.e(TAG, "executeSearchCycle: Failed to open menu, aborting cycle")
            SkeddyLogger.e(TAG, "Failed to open menu, aborting cycle")
            return
        }

        // Step 3: Wait for side menu and navigate to Scheduled Rides
        if (!navigator.waitForScreen(LyftScreen.SIDE_MENU, timeout = 3000)) {
            Log.e(TAG, "executeSearchCycle: Side menu didn't appear, aborting cycle")
            SkeddyLogger.e(TAG, "Side menu didn't appear, aborting cycle")
            navigator.safeNavigateBack()
            return
        }
        Log.d(TAG, "executeSearchCycle: Step 3 - Side menu opened")
        SkeddyLogger.d(TAG, "Step 3 - Side menu opened")

        Log.d(TAG, "executeSearchCycle: Step 4 - Navigating to Scheduled Rides...")
        SkeddyLogger.d(TAG, "Step 4 - Navigating to Scheduled Rides")
        if (!navigator.safeNavigateToScheduledRides()) {
            Log.e(TAG, "executeSearchCycle: Failed to navigate to Scheduled Rides, aborting cycle")
            SkeddyLogger.e(TAG, "Failed to navigate to Scheduled Rides, aborting cycle")
            navigator.safeNavigateBack()
            return
        }

        // Step 5: Wait for Scheduled Rides screen
        if (!navigator.waitForScreen(LyftScreen.SCHEDULED_RIDES, timeout = 5000)) {
            Log.e(TAG, "executeSearchCycle: Scheduled Rides screen didn't load, aborting cycle")
            SkeddyLogger.e(TAG, "Scheduled Rides screen didn't load, aborting cycle")
            navigator.safeNavigateBack()
            return
        }
        Log.d(TAG, "executeSearchCycle: Step 5 - On Scheduled Rides screen")
        SkeddyLogger.d(TAG, "Step 5 - On Scheduled Rides screen")

        // Wait for UI to fully load before parsing
        delay(5000)
        Log.d(TAG, "executeSearchCycle: Waited 5s for UI to stabilize")

        // Step 6: Parse rides with scrolling
        Log.d(TAG, "executeSearchCycle: Step 6 - Parsing rides with scrolling...")
        SkeddyLogger.d(TAG, "Step 6 - Parsing rides with scrolling")
        val parsedRides = parseRidesWithScrolling(accessibilityService)
        Log.d(TAG, "executeSearchCycle: Successfully parsed ${parsedRides.size} rides total")
        SkeddyLogger.i(TAG, "Parsed ${parsedRides.size} rides total")

        // Step 7: Cascading filter ($10 hardcoded → server min_price → blacklist)
        Log.d(TAG, "executeSearchCycle: Step 7 - Filtering rides (cascade)...")
        val filteredRides = rideFilter.filterRides(parsedRides, currentMinPrice)
        Log.d(TAG, "executeSearchCycle: ${filteredRides.size} rides passed filter (serverMinPrice=$currentMinPrice)")
        SkeddyLogger.i(TAG, "${filteredRides.size} rides passed filter (serverMinPrice=$currentMinPrice)")

        // Track rides that passed all filters for ping stats (PRD: rides_found)
        if (filteredRides.isNotEmpty()) {
            pendingStats.addRidesFound(filteredRides.size)
        }

        // Show temporary debug toast with ride stats
        handler.post {
            android.widget.Toast.makeText(
                this,
                "Rides: ${parsedRides.size} total, ${filteredRides.size} passed filter (≥$${"%.0f".format(currentMinPrice)})",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        // Step 7.5: Auto-accept high-value rides (always enabled per PRD)
        // Track accepted rides count for this cycle
        var acceptedRidesCount = 0

        if (filteredRides.isNotEmpty()) {
            // SAFETY FILTER TEMPORARILY DISABLED FOR TESTING
            // TODO: Re-enable Tomorrow filter before production
            val tomorrowRides = filteredRides // Accept all filtered rides for now
            // val tomorrowRides = filteredRides.filter { ride ->
            //     ride.pickupTime.contains("Tomorrow", ignoreCase = true)
            // }

            if (tomorrowRides.isEmpty()) {
                Log.i(TAG, "executeSearchCycle: Step 7.5 - No rides to auto-accept")
                SkeddyLogger.i(TAG, "Auto-accept: No rides found among ${filteredRides.size} filtered rides")
            } else {
                Log.i(TAG, "executeSearchCycle: Step 7.5 - Auto-accepting ${tomorrowRides.size} rides (safety filter disabled for testing)...")
                SkeddyLogger.i(TAG, "Auto-accept: attempting ${tomorrowRides.size} rides (no Tomorrow filter)")
            }

            val autoAcceptManager = AutoAcceptManager.create(serverClient, blacklistRepository, pendingRideQueue)
            if (autoAcceptManager != null && tomorrowRides.isNotEmpty()) {
                for (ride in tomorrowRides) {
                    // Re-capture UI hierarchy to find the card for this ride
                    val rootNode = accessibilityService.captureLyftUIHierarchy()
                    if (rootNode == null) {
                        Log.w(TAG, "executeSearchCycle: Failed to capture UI for auto-accept")
                        break
                    }

                    val rideCards = RideParser.findRideCards(rootNode)
                    val targetCard = findRideCardByRide(rideCards, ride, accessibilityService)

                    if (targetCard != null) {
                        val result = autoAcceptManager.autoAcceptRide(targetCard, ride)
                        when (result) {
                            is AutoAcceptResult.Success -> {
                                Log.i(TAG, "Auto-accept SUCCESS: ${ride.pickupTime} \$${ride.price}")
                                SkeddyLogger.i(TAG, "Auto-accept SUCCESS: ${ride.id}")
                                acceptedRidesCount++
                                preferences.incrementRidesFoundToday()
                            }
                            is AutoAcceptResult.RideNotFound -> {
                                Log.w(TAG, "Auto-accept: Ride not found - ${result.reason}")
                                SkeddyLogger.w(TAG, "Auto-accept RideNotFound: ${result.reason}")
                            }
                            is AutoAcceptResult.AcceptButtonNotFound -> {
                                Log.w(TAG, "Auto-accept: Accept button not found - ${result.reason}")
                                SkeddyLogger.w(TAG, "Auto-accept AcceptButtonNotFound: ${result.reason}")
                            }
                            is AutoAcceptResult.ClickFailed -> {
                                Log.w(TAG, "Auto-accept: Click failed - ${result.reason}")
                                SkeddyLogger.w(TAG, "Auto-accept ClickFailed: ${result.reason}")
                            }
                            is AutoAcceptResult.ConfirmationTimeout -> {
                                Log.w(TAG, "Auto-accept: Confirmation timeout - ${result.reason}")
                                SkeddyLogger.w(TAG, "Auto-accept ConfirmationTimeout: ${result.reason}")
                            }
                        }

                        // Record accept failure stats for the next ping
                        if (result !is AutoAcceptResult.Success) {
                            val reason = result::class.simpleName ?: "Unknown"
                            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                            sdf.timeZone = TimeZone.getTimeZone("UTC")
                            pendingStats.addAcceptFailure(
                                AcceptFailure(
                                    reason = reason,
                                    ridePrice = ride.price,
                                    pickupTime = ride.pickupTime,
                                    timestamp = sdf.format(Date())
                                )
                            )
                        }

                        // Wait for return to SCHEDULED_RIDES before processing next ride
                        navigator.waitForScreen(LyftScreen.SCHEDULED_RIDES, 3000)
                        delay(500) // Small delay to ensure UI is stable
                    } else {
                        Log.w(TAG, "executeSearchCycle: Could not find card for ride ${ride.id}")
                        SkeddyLogger.w(TAG, "Auto-accept: Card not found for ride ${ride.id}")
                    }
                }
            } else if (autoAcceptManager == null) {
                Log.w(TAG, "executeSearchCycle: AutoAcceptManager not available")
                SkeddyLogger.w(TAG, "Auto-accept: AutoAcceptManager not available")
            }

            totalNewRidesFound += acceptedRidesCount
            Log.i(TAG, "executeSearchCycle: Auto-accepted $acceptedRidesCount rides")
            SkeddyLogger.i(TAG, "Auto-accepted $acceptedRidesCount rides")
        }

        // Step 9: Navigate back to main screen
        Log.d(TAG, "executeSearchCycle: Step 9 - Navigating back...")
        SkeddyLogger.d(TAG, "Step 9 - Navigating back to main screen")
        navigator.safeNavigateBack()

        // Update notification (search is active during executeSearchCycle)
        updateNotification(searching = true)

        // Broadcast status update with last check time
        lastCheckTime = System.currentTimeMillis()
        broadcastMonitoringStatus(
            isRunning = true,
            lastCheckTime = lastCheckTime,
            ridesCount = totalNewRidesFound,
            isSearchActive = true,
            searchState = SEARCH_STATE_SEARCHING
        )

        val cycleDuration = System.currentTimeMillis() - cycleStartTime
        lastCycleDurationMs = cycleDuration
        Log.i(TAG, "executeSearchCycle: ========== CYCLE COMPLETED in ${cycleDuration}ms ==========")
        SkeddyLogger.i(TAG, "========== CYCLE COMPLETED in ${cycleDuration}ms (parsed: ${parsedRides.size}, filtered: ${filteredRides.size}, total saved: $totalNewRidesFound) ==========")
    }

    /**
     * Parses rides from UI with scrolling to capture all visible rides.
     * Scrolls through the ride list and collects all unique rides.
     *
     * @param accessibilityService The accessibility service for UI interaction
     * @return List of parsed ScheduledRide objects (deduplicated by ID)
     */
    private suspend fun parseRidesWithScrolling(
        accessibilityService: SkeddyAccessibilityService
    ): List<ScheduledRide> {
        val allRides = mutableMapOf<String, ScheduledRide>() // Use ID as key for deduplication
        val maxScrolls = 5 // Maximum number of scrolls to prevent infinite loop
        var scrollCount = 0
        var previousRideCount = -1

        val screen = accessibilityService.getScreenSize()

        while (scrollCount <= maxScrolls) {
            // Capture current UI state
            val rootNode = accessibilityService.captureLyftUIHierarchy()
            if (rootNode == null) {
                Log.w(TAG, "parseRidesWithScrolling: Failed to capture UI, stopping")
                break
            }

            // Find and parse ride cards
            val rideCards = RideParser.findRideCards(rootNode)
            Log.d(TAG, "parseRidesWithScrolling: Scroll $scrollCount - Found ${rideCards.size} ride cards")

            for (card in rideCards) {
                val ride = RideParser.parseRideCard(card)
                if (ride != null && !allRides.containsKey(ride.id)) {
                    allRides[ride.id] = ride
                    Log.d(TAG, "parseRidesWithScrolling: New ride: ${ride.pickupTime} - \$${ride.price}")
                }
            }

            Log.d(TAG, "parseRidesWithScrolling: Total unique rides so far: ${allRides.size}")

            // Check if we found new rides
            if (allRides.size == previousRideCount) {
                Log.d(TAG, "parseRidesWithScrolling: No new rides found, stopping scroll")
                break
            }
            previousRideCount = allRides.size

            // Scroll down to see more rides (only if not first iteration and we found rides)
            if (scrollCount < maxScrolls && rideCards.isNotEmpty()) {
                Log.d(TAG, "parseRidesWithScrolling: Scrolling down...")
                accessibilityService.performScrollDown(screen.x, screen.y)
                delay(800) // Wait for scroll animation
                scrollCount++
            } else {
                break
            }
        }

        Log.i(TAG, "parseRidesWithScrolling: Completed with ${allRides.size} total rides after $scrollCount scrolls")
        return allRides.values.toList()
    }

    // ==================== Error Handling Methods ====================

    /**
     * Executes search cycle with retry logic and error handling.
     *
     * On success: resets consecutive failure counter.
     * On failure: increments failure counter, attempts recovery.
     * If max failures reached: notifies user and pauses with extended delay.
     */
    private suspend fun executeSearchCycleWithRetry() {
        try {
            executeSearchCycle()
            // Success - reset failure counter
            if (consecutiveFailures > 0) {
                Log.i(TAG, "executeSearchCycleWithRetry: Cycle succeeded after $consecutiveFailures failures, resetting counter")
            }
            consecutiveFailures = 0
        } catch (e: Exception) {
            consecutiveFailures++
            val error = categorizeError(e)
            Log.e(TAG, "executeSearchCycleWithRetry: Cycle failed ($consecutiveFailures/$maxConsecutiveFailures) - ${error.message}", e)

            if (consecutiveFailures >= maxConsecutiveFailures) {
                Log.e(TAG, "executeSearchCycleWithRetry: Too many consecutive failures, pausing monitoring")
                notifyError("Моніторинг призупинено через ${consecutiveFailures} помилок підряд")

                // Pause with doubled interval before next attempt
                delay(monitoringInterval * 2)
                consecutiveFailures = 0
                Log.i(TAG, "executeSearchCycleWithRetry: Resuming after pause, counter reset")
            }

            // Attempt auto-recovery
            tryRecovery()
        }
        // Count this cycle attempt for ping stats (PRD: cycles_since_last_ping)
        pendingStats.incrementCycles()
    }

    /**
     * Categorizes an exception into a MonitoringError type.
     *
     * @param e The exception to categorize
     * @return Appropriate MonitoringError subclass
     */
    internal fun categorizeError(e: Exception): MonitoringError {
        return when {
            e.message?.contains("Accessibility", ignoreCase = true) == true ->
                MonitoringError.AccessibilityUnavailable
            e.message?.contains("navigation", ignoreCase = true) == true ||
            e.message?.contains("navigate", ignoreCase = true) == true ->
                MonitoringError.NavigationFailed
            e.message?.contains("parse", ignoreCase = true) == true ->
                MonitoringError.ParseFailed
            else ->
                MonitoringError.UnexpectedError(e)
        }
    }

    /**
     * Attempts auto-recovery after a monitoring cycle failure.
     *
     * Uses AutoRecoveryManager to ensure Lyft is active and on main screen.
     * Falls back to basic navigation if AutoRecoveryManager is not available.
     */
    private suspend fun tryRecovery() {
        Log.d(TAG, "tryRecovery: Attempting auto-recovery...")
        SkeddyLogger.i(TAG, "tryRecovery: Attempting auto-recovery after failure")

        val recoveryManager = AutoRecoveryManager.create(this)
        if (recoveryManager != null) {
            // Use AutoRecoveryManager for comprehensive recovery
            val result = recoveryManager.prepareForMonitoring()
            if (result.isSuccess) {
                Log.i(TAG, "tryRecovery: Auto-recovery successful via AutoRecoveryManager")
                SkeddyLogger.i(TAG, "tryRecovery: Auto-recovery successful")
            } else {
                val failure = result as com.skeddy.util.RetryResult.Failure
                Log.w(TAG, "tryRecovery: Auto-recovery failed: ${failure.error.message}")
                SkeddyLogger.w(TAG, "tryRecovery: Auto-recovery failed: ${failure.error.code} - ${failure.error.message}")
            }
        } else {
            // Fallback: basic back navigation
            Log.w(TAG, "tryRecovery: AutoRecoveryManager not available, using fallback")
            SkeddyLogger.w(TAG, "tryRecovery: Fallback to basic navigation")

            val accessibilityService = SkeddyAccessibilityService.getInstance()
            if (accessibilityService != null) {
                val navigator = LyftNavigator(accessibilityService)
                repeat(3) { attempt ->
                    Log.d(TAG, "tryRecovery: Back navigation attempt ${attempt + 1}/3")
                    navigator.safeNavigateBack()
                    delay(500)
                }
            }
        }

        Log.d(TAG, "tryRecovery: Recovery attempt completed")
        SkeddyLogger.d(TAG, "tryRecovery: Recovery attempt completed")
    }

    /**
     * Shows an error notification to the user.
     *
     * Uses the monitoring notification channel to display error information.
     *
     * @param message Error message to display
     */
    private fun notifyError(message: String) {
        Log.w(TAG, "notifyError: $message")

        val notification = NotificationCompat.Builder(this, SkeddyNotificationManager.CHANNEL_MONITORING)
            .setContentTitle("Skeddy: Помилка")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .build()

        // Use a different notification ID to not replace the persistent one
        notificationManager.notify(SkeddyNotificationManager.NOTIFICATION_MONITORING_ID + 1, notification)
    }

    // ==================== Notification Methods ====================

    /**
     * Creates the persistent foreground notification.
     *
     * Notification text follows PRD format:
     * - Searching: "Skeddy Search" / "Searching (30s)"
     * - Stopped:   "Skeddy Search" / "Stopped"
     *
     * @param searching Whether search is currently active
     * @return Notification instance ready to be used with startForeground()
     */
    internal fun createNotification(searching: Boolean = true): Notification {
        val stopIntent = Intent(this, MonitoringForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val contentText = if (searching) {
            getString(R.string.notification_searching, currentInterval)
        } else {
            getString(R.string.notification_stopped)
        }

        return NotificationCompat.Builder(this, SkeddyNotificationManager.CHANNEL_MONITORING)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .addAction(R.drawable.ic_stop, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    /**
     * Updates the persistent foreground notification with current search state.
     *
     * @param searching Whether search is currently active
     */
    fun updateNotification(searching: Boolean = true) {
        if (isMonitoring) {
            val notification = createNotification(searching)
            notificationManager.notify(SkeddyNotificationManager.NOTIFICATION_MONITORING_ID, notification)
            Log.d(TAG, "updateNotification: searching=$searching, interval=${currentInterval}s")
        }
    }

    // ==================== Broadcast Methods ====================

    /**
     * Broadcasts the current monitoring status to registered receivers.
     *
     * @param isRunning Whether monitoring is currently active
     * @param lastCheckTime Timestamp of the last monitoring cycle (null if not started)
     * @param ridesCount Total number of rides found in current session
     * @param serverOffline Whether the server is currently unreachable (network/server error)
     * @param isSearchActive Whether the server has search enabled
     * @param searchState One of SEARCH_STATE_* constants for UI status display
     */
    private fun broadcastMonitoringStatus(
        isRunning: Boolean,
        lastCheckTime: Long? = null,
        ridesCount: Int = 0,
        serverOffline: Boolean = false,
        isSearchActive: Boolean = false,
        searchState: String = SEARCH_STATE_STOPPED
    ) {
        val intent = Intent(ACTION_MONITORING_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_IS_MONITORING, isRunning)
            lastCheckTime?.let { putExtra(EXTRA_LAST_CHECK_TIME, it) }
            putExtra(EXTRA_RIDES_COUNT, ridesCount)
            putExtra(EXTRA_SERVER_OFFLINE, serverOffline)
            putExtra(EXTRA_IS_SEARCH_ACTIVE, isSearchActive)
            putExtra(EXTRA_CURRENT_INTERVAL, currentInterval)
            putExtra(EXTRA_SEARCH_STATE, searchState)
        }
        sendBroadcast(intent)
        Log.d(TAG, "broadcastMonitoringStatus: isRunning=$isRunning, ridesCount=$ridesCount, serverOffline=$serverOffline, isSearchActive=$isSearchActive, searchState=$searchState")
    }

    /**
     * Broadcasts the current service state on demand (DEF-7).
     *
     * Called when MainActivity sends ACTION_REQUEST_STATE in onResume to get
     * current state that may have been missed while the receiver was unregistered
     * (e.g., when Lyft was in foreground).
     */
    private fun broadcastCurrentState() {
        val searchState = when {
            !isMonitoring -> SEARCH_STATE_STOPPED
            isServerOffline -> SEARCH_STATE_OFFLINE
            lastSearchActiveFromServer -> SEARCH_STATE_SEARCHING
            isInitialPingCompleted -> SEARCH_STATE_STOPPED
            else -> SEARCH_STATE_WAITING
        }
        broadcastMonitoringStatus(
            isRunning = isMonitoring,
            lastCheckTime = if (lastCheckTime > 0L) lastCheckTime else null,
            ridesCount = totalNewRidesFound,
            serverOffline = isServerOffline,
            isSearchActive = lastSearchActiveFromServer,
            searchState = searchState
        )
        Log.d(TAG, "broadcastCurrentState: Responded to state request")
    }

    /**
     * Brings MainActivity to the foreground.
     *
     * Called when search transitions from active to stopped so the user sees
     * the Search App UI instead of Lyft (which is no longer needed).
     */
    private fun bringAppToForeground() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        startActivity(intent)
        Log.i(TAG, "bringAppToForeground: Search stopped, bringing MainActivity to front")
    }

    /**
     * Broadcasts when a new high-value ride is found.
     *
     * @param ride The newly found ride
     */
    private fun broadcastNewRideFound(ride: ScheduledRide) {
        val intent = Intent(ACTION_NEW_RIDE_FOUND).apply {
            setPackage(packageName)
            putExtra(EXTRA_RIDE_PRICE, ride.price)
            putExtra(EXTRA_RIDE_PICKUP, ride.pickupLocation)
            putExtra(EXTRA_RIDE_DROPOFF, ride.dropoffLocation)
            putExtra(EXTRA_RIDES_COUNT, totalNewRidesFound)
        }
        sendBroadcast(intent)
        Log.d(TAG, "broadcastNewRideFound: price=${ride.price}, pickup=${ride.pickupLocation}")
    }

    /**
     * Broadcasts force update action to UI layer.
     * Triggers ForceUpdateActivity display.
     *
     * @param updateUrl URL for the app update page
     */
    private fun broadcastForceUpdate(updateUrl: String?) {
        val intent = Intent(ACTION_FORCE_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_UPDATE_URL, updateUrl)
        }
        sendBroadcast(intent)
        Log.i(TAG, "broadcastForceUpdate: Force update broadcast sent (url=$updateUrl)")
    }

    /**
     * Broadcasts force update cleared action to UI layer.
     * Triggers ForceUpdateActivity auto-close.
     */
    private fun broadcastForceUpdateCleared() {
        val intent = Intent(ACTION_FORCE_UPDATE_CLEARED).apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)
        Log.i(TAG, "broadcastForceUpdateCleared: Force update cleared broadcast sent")
    }

    /**
     * Finds the AccessibilityNodeInfo card that corresponds to the given ScheduledRide.
     *
     * Matches by comparing parsed ride ID from each card with the target ride's ID.
     *
     * @param rideCards List of AccessibilityNodeInfo ride cards from UI
     * @param targetRide The ScheduledRide to find
     * @param accessibilityService Service for parsing card content
     * @return AccessibilityNodeInfo of the matching card, or null if not found
     */
    private fun findRideCardByRide(
        rideCards: List<AccessibilityNodeInfo>,
        targetRide: ScheduledRide,
        accessibilityService: SkeddyAccessibilityService
    ): AccessibilityNodeInfo? {
        for (card in rideCards) {
            val parsedRide = RideParser.parseRideCard(card)
            if (parsedRide != null && parsedRide.id == targetRide.id) {
                Log.d(TAG, "findRideCardByRide: Found matching card for ride ${targetRide.id}")
                return card
            }
        }
        Log.d(TAG, "findRideCardByRide: No matching card found for ride ${targetRide.id}")
        return null
    }
}
