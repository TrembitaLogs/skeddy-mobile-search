package com.skeddy.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.skeddy.R
import com.skeddy.accessibility.SkeddyAccessibilityService
import com.skeddy.data.SkeddyPreferences
import com.skeddy.databinding.ActivityMainBinding
import com.skeddy.error.SkeddyError
import com.skeddy.network.DeviceTokenManager
import com.skeddy.network.NetworkModule
import com.skeddy.network.SkeddyApi
import com.skeddy.network.SkeddyServerClient
import com.skeddy.notification.NotificationPermissionHelper
import com.skeddy.service.MonitoringForegroundService
import com.skeddy.ui.error.ErrorDisplayHelper
import com.skeddy.ui.error.ErrorEvent
import com.skeddy.util.BatteryOptimizationHelper
import com.skeddy.utils.PermissionUtils
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Main screen of the app with test UI for Accessibility Service.
 *
 * Purpose:
 * - Display Accessibility Service status
 * - Provide buttons for testing service functionality
 * - Show log of test results
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var notificationPermissionHelper: NotificationPermissionHelper
    private lateinit var viewModel: MainViewModel
    private lateinit var appStateDeterminer: AppStateDeterminer

    private val logBuilder = StringBuilder()
    private lateinit var dateFormat: DateFormat

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.i(TAG, "POST_NOTIFICATIONS permission granted")
            appendLog("Notification permission granted")
        } else {
            Log.w(TAG, "POST_NOTIFICATIONS permission denied")
            appendLog("Notification permission denied - notifications will be disabled")
        }
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.i(TAG, "ACCESS_FINE_LOCATION permission granted")
            appendLog("Location permission granted")
            dismissLocationDialogIfShowing()
            // Continue permission sequence
            checkPermissionsInSequence()
        } else {
            Log.w(TAG, "ACCESS_FINE_LOCATION permission denied")
            appendLog("Location permission denied - required for app operation")
            showLocationPermissionDialog()
        }
    }

    private var monitoringService: MonitoringForegroundService? = null
    private var isServiceBound = false
    private var isMonitoringActive = false
    private var accessibilityDialog: AlertDialog? = null
    private var batteryOptimizationDialog: AlertDialog? = null
    private var locationDialog: AlertDialog? = null

    /**
     * BroadcastReceiver for monitoring status updates and new ride notifications.
     */
    private val monitoringReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MonitoringForegroundService.ACTION_MONITORING_STATUS -> {
                    val isRunning = intent.getBooleanExtra(
                        MonitoringForegroundService.EXTRA_IS_MONITORING, false
                    )
                    val lastCheckTime = intent.getLongExtra(
                        MonitoringForegroundService.EXTRA_LAST_CHECK_TIME, 0L
                    )
                    val ridesCount = intent.getIntExtra(
                        MonitoringForegroundService.EXTRA_RIDES_COUNT, 0
                    )
                    val isSearchActive = intent.getBooleanExtra(
                        MonitoringForegroundService.EXTRA_IS_SEARCH_ACTIVE, false
                    )
                    val serverOffline = intent.getBooleanExtra(
                        MonitoringForegroundService.EXTRA_SERVER_OFFLINE, false
                    )
                    val currentIntervalSeconds = intent.getIntExtra(
                        MonitoringForegroundService.EXTRA_CURRENT_INTERVAL,
                        MonitoringForegroundService.DEFAULT_INTERVAL_SECONDS
                    )
                    val searchState = intent.getStringExtra(
                        MonitoringForegroundService.EXTRA_SEARCH_STATE
                    ) ?: MonitoringForegroundService.SEARCH_STATE_STOPPED

                    Log.d(TAG, "Received MONITORING_STATUS: isRunning=$isRunning, ridesCount=$ridesCount, isSearchActive=$isSearchActive, searchState=$searchState")
                    viewModel.updateFromBroadcast(
                        isRunning = isRunning,
                        isSearchActive = isSearchActive,
                        serverOffline = serverOffline,
                        lastCheckTime = if (lastCheckTime > 0) lastCheckTime else null,
                        ridesCount = ridesCount,
                        currentIntervalSeconds = currentIntervalSeconds,
                        searchState = searchState
                    )
                }

                MonitoringForegroundService.ACTION_NEW_RIDE_FOUND -> {
                    val price = intent.getDoubleExtra(
                        MonitoringForegroundService.EXTRA_RIDE_PRICE, 0.0
                    )
                    val pickup = intent.getStringExtra(
                        MonitoringForegroundService.EXTRA_RIDE_PICKUP
                    ) ?: ""
                    val dropoff = intent.getStringExtra(
                        MonitoringForegroundService.EXTRA_RIDE_DROPOFF
                    ) ?: ""

                    Log.d(TAG, "Received NEW_RIDE_FOUND: price=$price, pickup=$pickup")
                    showNewRideSnackbar(price, pickup, dropoff)
                }

                MonitoringForegroundService.ACTION_FORCE_UPDATE -> {
                    val updateUrl = intent.getStringExtra(MonitoringForegroundService.EXTRA_UPDATE_URL)
                    Log.i(TAG, "Received FORCE_UPDATE: url=$updateUrl")
                    viewModel.setForceUpdateState(updateUrl)
                    startActivity(ForceUpdateActivity.createIntent(this@MainActivity, updateUrl))
                }

                MonitoringForegroundService.ACTION_FORCE_UPDATE_CLEARED -> {
                    Log.i(TAG, "Received FORCE_UPDATE_CLEARED")
                    viewModel.clearForceUpdate()
                }

                MonitoringForegroundService.ACTION_UNPAIRED -> {
                    Log.i(TAG, "Received UNPAIRED: device token invalidated (401/403)")
                    startActivity(LoginActivity.createIntent(this@MainActivity).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    })
                    finish()
                }
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? MonitoringForegroundService.LocalBinder ?: return
            monitoringService = binder.getService()
            isServiceBound = true
            updateMonitoringStatus()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            monitoringService = null
            isServiceBound = false
            viewModel.updateServiceStatus(false) // Update UI to show 'Stopped' status
            Log.d(TAG, "Service disconnected, status set to Stopped")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        dateFormat = if (android.text.format.DateFormat.is24HourFormat(this)) {
            SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        } else {
            SimpleDateFormat("h:mm:ss a", Locale.getDefault())
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        notificationPermissionHelper = NotificationPermissionHelper(this)

        setupViewModel()
        setupTabLayout()
        setupClickListeners()
        updateServiceStatus()
        updateAccessibilityStatusInMonitorTab()
        // Permissions are checked in onResume in sequence:
        // 1. Accessibility -> 2. Battery Optimization -> 3. Notifications
        observeViewModel()
    }

    private fun setupViewModel() {
        val deviceTokenManager = DeviceTokenManager(this)
        val preferences = SkeddyPreferences(this)
        NetworkModule.initialize(deviceTokenManager)
        val api = NetworkModule.createService<SkeddyApi>()
        val serverClient = SkeddyServerClient(api, deviceTokenManager)
        val factory = MainViewModelFactory(serverClient, deviceTokenManager)
        viewModel = ViewModelProvider(this, factory)[MainViewModel::class.java]
        appStateDeterminer = AppStateDeterminer(deviceTokenManager, preferences)
    }

    private fun observeViewModel() {
        viewModel.serviceStatus.observe(this) { isRunning ->
            isMonitoringActive = isRunning
        }

        viewModel.searchStatus.observe(this) { status ->
            updateSearchStatusUI(status)
        }

        viewModel.lastCheckTime.observe(this) { timestamp ->
            timestamp?.let {
                val formattedTime = dateFormat.format(Date(it))
                binding.tvLastPingTime.text = formattedTime
                appendLog("Last check: $formattedTime")
            }
        }

        viewModel.currentInterval.observe(this) { intervalSeconds ->
            binding.tvCurrentInterval.text = if (intervalSeconds != null) {
                getString(R.string.current_interval_value, intervalSeconds)
            } else {
                "--"
            }
        }

        viewModel.ridesTodayCount.observe(this) { count ->
            binding.tvRidesFoundToday.text = count.toString()
        }

        viewModel.accessibilityEnabled.observe(this) { enabled ->
            updateAccessibilityStatusInMonitorTab(enabled)
        }

        viewModel.errorEvent.observe(this) { event ->
            event?.getErrorIfNotHandled()?.let { error ->
                handleErrorEvent(error)
            }
        }

        viewModel.lastError.observe(this) { event ->
            updateErrorStatusCard(event)
        }

        // Toggle search observers
        viewModel.isToggleLoading.observe(this) { isLoading ->
            binding.btnStartStop.isEnabled = !isLoading
        }

        viewModel.toastMessage.observe(this) { messageResId ->
            messageResId?.let {
                Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
                viewModel.clearToastMessage()
            }
        }

        viewModel.navigateToLogin.observe(this) { shouldNavigate ->
            if (shouldNavigate) {
                viewModel.clearNavigateToLogin()
                startActivity(LoginActivity.createIntent(this).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
            }
        }

        viewModel.serviceControlEvent.observe(this) { shouldStart ->
            shouldStart?.let {
                viewModel.clearServiceControlEvent()
                if (it) {
                    startMonitoringService()
                }
                // Stop: we don't stop the service — it continues the ping cycle.
                // Server will respond with search:false and the service won't start searching.
            }
        }

    }

    private fun handleErrorEvent(error: SkeddyError) {
        ErrorDisplayHelper.showError(
            context = this,
            rootView = binding.root,
            error = error,
            onRetry = {
                appendLog("Retry requested for error: ${error.code}")
                if (isMonitoringActive) {
                    monitoringService?.let { service ->
                        service.stopMonitoring()
                        binding.root.postDelayed({
                            service.startMonitoring()
                            appendLog("Monitoring restarted")
                        }, 500)
                    }
                } else {
                    startMonitoringService()
                }
            },
            onOpenLyft = {
                appendLog("Opening Lyft...")
                openLyftApp()
            }
        )
    }

    private fun updateErrorStatusCard(event: ErrorEvent?) {
        if (event == null) {
            binding.errorStatusCard.visibility = View.GONE
            return
        }

        val error = event.peekError()
        binding.errorStatusCard.visibility = View.VISIBLE
        binding.tvErrorMessage.text = error.getUserMessage(this)
        binding.tvErrorTime.text = dateFormat.format(Date(event.timestamp))

        binding.btnDismissError.setOnClickListener {
            viewModel.clearLastError()
        }
    }

    private fun openLyftApp() {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage("com.lyft.android.driver")
            if (launchIntent != null) {
                startActivity(launchIntent)
            } else {
                viewModel.postError(SkeddyError.LyftAppNotFound)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Lyft app", e)
            viewModel.postError(SkeddyError.LyftAppNotFound)
        }
    }

    private fun updateSearchStatusUI(status: SearchStatus) {
        when (status) {
            is SearchStatus.Searching -> {
                binding.tvMonitoringStatus.text =
                    getString(R.string.search_status_searching, status.intervalSeconds)
                binding.tvMonitoringStatus.setTextColor(
                    ContextCompat.getColor(this, R.color.status_running)
                )
                binding.ivStatusIndicator.setImageResource(R.drawable.service_indicator_running)
                binding.btnStartStop.text = getString(R.string.btn_stop)
            }
            is SearchStatus.Stopped -> {
                binding.tvMonitoringStatus.text = getString(R.string.search_status_stopped)
                binding.tvMonitoringStatus.setTextColor(
                    ContextCompat.getColor(this, R.color.status_stopped)
                )
                binding.ivStatusIndicator.setImageResource(R.drawable.service_indicator_stopped)
                binding.btnStartStop.text = getString(R.string.btn_start)
            }
            is SearchStatus.WaitingForServer -> {
                binding.tvMonitoringStatus.text = getString(R.string.search_status_waiting)
                binding.tvMonitoringStatus.setTextColor(
                    ContextCompat.getColor(this, R.color.status_stopped)
                )
                binding.ivStatusIndicator.setImageResource(R.drawable.service_indicator_stopped)
                binding.btnStartStop.text = getString(R.string.btn_stop)
            }
            is SearchStatus.ServerOffline -> {
                binding.tvMonitoringStatus.text = getString(R.string.search_status_offline)
                binding.tvMonitoringStatus.setTextColor(
                    ContextCompat.getColor(this, R.color.status_stopped)
                )
                binding.ivStatusIndicator.setImageResource(R.drawable.service_indicator_stopped)
                binding.btnStartStop.text = getString(R.string.btn_start)
            }
        }
    }

    private fun setupTabLayout() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        binding.monitorContent.visibility = View.VISIBLE
                        binding.debugContent.visibility = View.GONE
                    }
                    1 -> {
                        binding.monitorContent.visibility = View.GONE
                        binding.debugContent.visibility = View.VISIBLE
                    }
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun checkNotificationPermission() {
        notificationPermissionHelper.logPermissionStatus()

        if (!notificationPermissionHelper.shouldRequestPermission(this)) {
            return
        }

        if (notificationPermissionHelper.shouldShowRationale(this)) {
            showNotificationPermissionRationale()
        } else {
            requestNotificationPermission()
        }
    }

    private fun showNotificationPermissionRationale() {
        AlertDialog.Builder(this)
            .setTitle(R.string.notification_permission_title)
            .setMessage(R.string.notification_permission_rationale)
            .setPositiveButton(R.string.btn_grant_permission) { _, _ ->
                requestNotificationPermission()
            }
            .setNegativeButton(R.string.btn_not_now) { dialog, _ ->
                dialog.dismiss()
                appendLog("Notification permission skipped - notifications will be disabled")
            }
            .setCancelable(false)
            .show()
    }

    private fun requestNotificationPermission() {
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun showAccessibilityPermissionDialog() {
        // Don't show if already showing
        if (accessibilityDialog?.isShowing == true) {
            return
        }

        accessibilityDialog = AlertDialog.Builder(this)
            .setTitle(R.string.accessibility_permission_title)
            .setMessage(R.string.accessibility_permission_message)
            .setPositiveButton(R.string.btn_open_settings) { _, _ ->
                PermissionUtils.openAccessibilitySettings(this)
            }
            .setNegativeButton(R.string.btn_exit) { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun dismissAccessibilityDialogIfShowing() {
        accessibilityDialog?.let {
            if (it.isShowing) {
                it.dismiss()
            }
        }
        accessibilityDialog = null
    }

    override fun onResume() {
        super.onResume()

        val isAccessibilityEnabled = PermissionUtils.isAccessibilityServiceEnabled(
            this, SkeddyAccessibilityService::class.java
        )

        when (val state = appStateDeterminer.determine(isAccessibilityEnabled)) {
            is AppState.NotLoggedIn -> {
                startActivity(LoginActivity.createIntent(this).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
                return
            }
            is AppState.NotConfigured -> {
                startActivity(SetupRequiredActivity.createIntent(this))
                finish()
                return
            }
            is AppState.ForceUpdate -> {
                startActivity(ForceUpdateActivity.createIntent(this, state.updateUrl))
                return
            }
            is AppState.LoggedIn -> {
                // Continue with main screen setup below
            }
        }

        updateServiceStatus()
        updateAccessibilityStatusInMonitorTab()
        bindMonitoringService()
        ensureMonitoringServiceStarted()
        registerMonitoringReceiver()
        requestServiceState()

        // Check permissions in sequence: Battery Optimization -> Notifications
        // (Accessibility is already verified by AppStateDeterminer above)
        checkPermissionsInSequence()
    }

    /**
     * Checks required permissions in sequence.
     * Only proceeds to the next permission when the previous one is granted.
     * Order: 1. Accessibility Service -> 2. Battery Optimization -> 3. Location (mandatory) -> 4. Notifications
     */
    private fun checkPermissionsInSequence() {
        // Step 1: Check Accessibility Service
        val isAccessibilityEnabled = PermissionUtils.isAccessibilityServiceEnabled(
            this,
            SkeddyAccessibilityService::class.java
        )
        if (!isAccessibilityEnabled) {
            dismissBatteryOptimizationDialogIfShowing()
            dismissLocationDialogIfShowing()
            showAccessibilityPermissionDialog()
            return // Wait for accessibility to be granted
        }
        dismissAccessibilityDialogIfShowing()

        // Step 2: Check Battery Optimization
        val isBatteryOptimizationDisabled = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)
        if (!isBatteryOptimizationDisabled) {
            dismissLocationDialogIfShowing()
            showBatteryOptimizationPermissionDialog()
            return // Wait for battery optimization to be disabled
        }
        dismissBatteryOptimizationDialogIfShowing()

        // Step 3: Check Location Permission (mandatory)
        if (!isLocationPermissionGranted()) {
            requestLocationPermission()
            return // Wait for location to be granted
        }
        dismissLocationDialogIfShowing()

        // Step 4: Check Notification Permission (only after previous permissions are granted)
        checkNotificationPermission()
    }

    private fun isLocationPermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermission() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
            showLocationPermissionRationale()
        } else {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun showLocationPermissionRationale() {
        if (locationDialog?.isShowing == true) return

        locationDialog = AlertDialog.Builder(this)
            .setTitle(R.string.location_permission_title)
            .setMessage(R.string.location_permission_rationale)
            .setPositiveButton(R.string.btn_grant_permission) { _, _ ->
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            .setNegativeButton(R.string.btn_exit) { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun showLocationPermissionDialog() {
        if (locationDialog?.isShowing == true) return

        locationDialog = AlertDialog.Builder(this)
            .setTitle(R.string.location_permission_title)
            .setMessage(R.string.location_permission_message)
            .setPositiveButton(R.string.btn_grant_permission) { _, _ ->
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            .setNegativeButton(R.string.btn_exit) { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun dismissLocationDialogIfShowing() {
        locationDialog?.let {
            if (it.isShowing) {
                it.dismiss()
            }
        }
        locationDialog = null
    }

    override fun onPause() {
        super.onPause()
        unregisterMonitoringReceiver()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }

    private fun bindMonitoringService() {
        val intent = Intent(this, MonitoringForegroundService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    /**
     * Ensures the monitoring foreground service is started with ACTION_START.
     * Called from onResume() to cover Cold Start and After Login scenarios
     * where bindService alone doesn't trigger onStartCommand/startMonitoring.
     * Safe to call repeatedly — startMonitoring() guards with if(!isMonitoring).
     */
    private fun ensureMonitoringServiceStarted() {
        val intent = Intent(this, MonitoringForegroundService::class.java).apply {
            action = MonitoringForegroundService.ACTION_START
        }
        startForegroundService(intent)
    }

    /**
     * Requests the current state from MonitoringForegroundService (DEF-7).
     *
     * Sends ACTION_REQUEST_STATE so the service broadcasts its current state,
     * ensuring the UI receives up-to-date lastCheckTime even if previous
     * broadcasts were missed while the receiver was unregistered (e.g., Lyft in foreground).
     */
    private fun requestServiceState() {
        val intent = Intent(this, MonitoringForegroundService::class.java).apply {
            action = MonitoringForegroundService.ACTION_REQUEST_STATE
        }
        startService(intent)
    }

    private fun setupClickListeners() {
        // Monitor tab buttons
        binding.btnStartStop.setOnClickListener {
            viewModel.toggleSearch()
        }

        binding.btnOpenAccessibilitySettings.setOnClickListener {
            openAccessibilitySettings()
        }

        // Debug tab buttons
        binding.btnOpenAccessibilitySettingsDebug.setOnClickListener {
            openAccessibilitySettings()
        }

        binding.btnRefreshStatus.setOnClickListener {
            updateServiceStatus()
            updateAccessibilityStatusInMonitorTab()
            appendLog("Status refreshed")
        }

        binding.btnDumpUIHierarchy.setOnClickListener {
            dumpUIHierarchy()
        }

        binding.btnFindMenuButton.setOnClickListener {
            findMenuButton()
        }

        binding.btnTestClick.setOnClickListener {
            testClickOnNode()
        }

        binding.btnTestNotification.setOnClickListener {
            testNotification()
        }

        binding.btnClearLog.setOnClickListener {
            clearLog()
        }

        binding.btnStartMonitoring.setOnClickListener {
            startMonitoringService()
        }

        binding.btnStopMonitoring.setOnClickListener {
            stopMonitoringService()
        }

        binding.btnTestError.setOnClickListener {
            testErrorDisplay()
        }
    }

    private var testErrorIndex = 0

    private fun testErrorDisplay() {
        val testErrors = listOf(
            SkeddyError.ParseTimeout,
            SkeddyError.MenuButtonNotFound,
            SkeddyError.LyftAppNotFound,
            SkeddyError.AccessibilityNotEnabled,
            SkeddyError.ServiceKilled
        )
        val error = testErrors[testErrorIndex % testErrors.size]
        testErrorIndex++

        appendLog("Testing error: ${error.code} - ${error.message}")
        viewModel.postError(error)
    }

    private fun startMonitoringService() {
        appendLog("Starting Monitoring Service...")
        val intent = Intent(this, MonitoringForegroundService::class.java).apply {
            action = MonitoringForegroundService.ACTION_START
        }
        startForegroundService(intent)
        appendLog("Service start requested")

        // Update status after a short delay to allow service to start
        binding.root.postDelayed({ updateMonitoringStatus() }, 500)
    }

    private fun stopMonitoringService() {
        appendLog("Stopping Monitoring Service...")
        val intent = Intent(this, MonitoringForegroundService::class.java).apply {
            action = MonitoringForegroundService.ACTION_STOP
        }
        startService(intent)
        appendLog("Service stop requested")

        binding.root.postDelayed({ updateMonitoringStatus() }, 500)
    }

    private fun testNotification() {
        appendLog("Triggering test notification...")
        val intent = Intent(this, MonitoringForegroundService::class.java).apply {
            action = MonitoringForegroundService.ACTION_TEST_NOTIFICATION
        }
        // Use startService() instead of startForegroundService() because
        // ACTION_TEST_NOTIFICATION doesn't require foreground service
        startService(intent)
        appendLog("Test notification requested")
    }

    private fun updateMonitoringStatus() {
        val service = monitoringService ?: run {
            viewModel.updateServiceStatus(false)
            return
        }
        val isRunning = service.isMonitoringActive()
        viewModel.updateServiceStatus(isRunning)
        if (isRunning) {
            val isSearchActive = service.isSearchActiveFromServer()
            val intervalSeconds = service.getCurrentIntervalSeconds()
            viewModel.updateSearchActive(isSearchActive)
            viewModel.updateCurrentInterval(intervalSeconds)
            viewModel.updateSearchStatus(
                if (isSearchActive) SearchStatus.Searching(intervalSeconds)
                else SearchStatus.WaitingForServer
            )
        }
    }

    private fun updateAccessibilityStatusInMonitorTab() {
        val isEnabled = PermissionUtils.isAccessibilityServiceEnabled(
            this,
            SkeddyAccessibilityService::class.java
        )
        viewModel.updateAccessibilityEnabled(isEnabled)
    }

    private fun updateAccessibilityStatusInMonitorTab(isEnabled: Boolean) {
        if (isEnabled) {
            binding.tvAccessibilityStatus.text = getString(R.string.status_enabled)
            binding.btnOpenAccessibilitySettings.visibility = View.GONE
            binding.btnStartStop.isEnabled = true
        } else {
            binding.tvAccessibilityStatus.text = getString(R.string.status_disabled)
            binding.btnOpenAccessibilitySettings.visibility = View.VISIBLE
            // Block Start button if Accessibility Service is not enabled
            binding.btnStartStop.isEnabled = false
        }
    }

    /**
     * Shows a mandatory dialog requiring the user to disable battery optimization.
     * User must either disable it or exit the app.
     */
    private fun showBatteryOptimizationPermissionDialog() {
        // Don't show if already showing
        if (batteryOptimizationDialog?.isShowing == true) {
            return
        }

        batteryOptimizationDialog = AlertDialog.Builder(this)
            .setTitle(R.string.battery_optimization_dialog_title)
            .setMessage(R.string.battery_optimization_dialog_message)
            .setPositiveButton(R.string.btn_disable_battery_optimization) { _, _ ->
                appendLog("Requesting battery optimization exemption...")
                BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(this)
            }
            .setNegativeButton(R.string.btn_exit) { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Dismisses the battery optimization dialog if it's currently showing.
     */
    private fun dismissBatteryOptimizationDialogIfShowing() {
        batteryOptimizationDialog?.let {
            if (it.isShowing) {
                it.dismiss()
            }
        }
        batteryOptimizationDialog = null
    }

    private fun updateServiceStatus() {
        val isEnabled = SkeddyAccessibilityService.isServiceEnabled()

        if (isEnabled) {
            binding.tvServiceStatus.text = getString(R.string.status_enabled)
            binding.tvServiceStatus.setTextColor(getColor(android.R.color.holo_green_dark))
        } else {
            binding.tvServiceStatus.text = getString(R.string.status_disabled)
            binding.tvServiceStatus.setTextColor(getColor(android.R.color.holo_red_dark))
        }

        // Enable/disable test buttons based on service status
        binding.btnDumpUIHierarchy.isEnabled = isEnabled
        binding.btnFindMenuButton.isEnabled = isEnabled
        binding.btnTestClick.isEnabled = isEnabled
    }

    private fun openAccessibilitySettings() {
        appendLog("Opening Accessibility Settings...")
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    private fun dumpUIHierarchy() {
        appendLog("--- Dumping UI Hierarchy ---")

        val service = SkeddyAccessibilityService.getInstance()
        if (service == null) {
            appendLog("ERROR: Service not available")
            return
        }

        val root = service.captureUIHierarchy()
        if (root == null) {
            appendLog("ERROR: Cannot capture UI hierarchy")
            appendLog("Make sure another app is in foreground")
            return
        }

        appendLog("Root package: ${root.packageName}")
        appendLog("Root class: ${root.className}")
        appendLog("Children count: ${root.childCount}")

        // Dump first 3 levels
        dumpNodeInfo(root, 0, maxDepth = 3)

        appendLog("--- Full dump logged to Logcat ---")
        service.logUITreeDump()
    }

    private fun dumpNodeInfo(node: AccessibilityNodeInfo?, depth: Int, maxDepth: Int) {
        if (node == null || depth > maxDepth) return

        val indent = "  ".repeat(depth)
        val viewId = node.viewIdResourceName?.substringAfterLast("/") ?: "N/A"
        val text = node.text?.toString()?.take(30) ?: ""
        val desc = node.contentDescription?.toString()?.take(30) ?: ""

        val info = buildString {
            append("$indent[${node.className?.toString()?.substringAfterLast(".")}]")
            if (viewId != "N/A") append(" id=$viewId")
            if (text.isNotEmpty()) append(" text=\"$text\"")
            if (desc.isNotEmpty()) append(" desc=\"$desc\"")
            if (node.isClickable) append(" [clickable]")
        }
        appendLog(info)

        for (i in 0 until node.childCount) {
            dumpNodeInfo(node.getChild(i), depth + 1, maxDepth)
        }
    }

    private fun findMenuButton() {
        appendLog("--- Finding Menu Button ---")

        val service = SkeddyAccessibilityService.getInstance()
        if (service == null) {
            appendLog("ERROR: Service not available")
            return
        }

        // Try to find menu button by common patterns
        val searchPatterns = listOf(
            "menu" to "contentDesc",
            "navigation" to "contentDesc",
            "hamburger" to "contentDesc",
            "drawer" to "contentDesc"
        )

        for ((pattern, type) in searchPatterns) {
            val node = service.findNodeByContentDesc(pattern, exactMatch = false)
            if (node != null) {
                appendLog("FOUND by $type containing '$pattern':")
                logNodeDetails(node)
                return
            }
        }

        // Try by resource ID patterns
        val idPatterns = listOf(
            "menu_button",
            "nav_button",
            "toolbar_menu",
            "hamburger"
        )

        for (pattern in idPatterns) {
            val node = service.findNodeById(pattern)
            if (node != null) {
                appendLog("FOUND by id '$pattern':")
                logNodeDetails(node)
                return
            }
        }

        appendLog("Menu button not found")
        appendLog("Try opening Lyft Driver app first")
    }

    private fun logNodeDetails(node: AccessibilityNodeInfo) {
        appendLog("  Class: ${node.className}")
        appendLog("  ID: ${node.viewIdResourceName ?: "N/A"}")
        appendLog("  Text: ${node.text ?: "N/A"}")
        appendLog("  Desc: ${node.contentDescription ?: "N/A"}")
        appendLog("  Clickable: ${node.isClickable}")
        appendLog("  Enabled: ${node.isEnabled}")
        appendLog("  Visible: ${node.isVisibleToUser}")

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        appendLog("  Bounds: $bounds")
    }

    private fun testClickOnNode() {
        appendLog("--- Test Click ---")

        val service = SkeddyAccessibilityService.getInstance()
        if (service == null) {
            appendLog("ERROR: Service not available")
            return
        }

        // Find any clickable, visible node
        val clickableNode = service.findNodeByPredicate { node ->
            node.isClickable && node.isVisibleToUser && node.isEnabled
        }

        if (clickableNode == null) {
            appendLog("No clickable node found")
            return
        }

        appendLog("Found clickable node:")
        logNodeDetails(clickableNode)

        // Get coordinates for potential click
        val bounds = Rect()
        clickableNode.getBoundsInScreen(bounds)
        appendLog("Would click at: (${bounds.centerX()}, ${bounds.centerY()})")

        // Note: We don't actually perform the click to avoid disrupting user's UI
        appendLog("Click NOT performed (safety)")
        appendLog("To perform real click, use ADB broadcast:")
        appendLog("adb shell am broadcast -a ${SkeddyAccessibilityService.ACTION_TEST_CLICK}")
    }

    private fun appendLog(message: String) {
        val timestamp = dateFormat.format(Date())
        val logLine = "[$timestamp] $message\n"
        logBuilder.append(logLine)
        binding.tvLogOutput.text = logBuilder.toString()

        // Auto-scroll to bottom
        binding.tvLogOutput.post {
            val scrollView = binding.tvLogOutput.parent as? android.widget.ScrollView
            scrollView?.fullScroll(android.widget.ScrollView.FOCUS_DOWN)
        }
    }

    private fun clearLog() {
        logBuilder.clear()
        binding.tvLogOutput.text = getString(R.string.log_empty)
    }

    /**
     * Shows a Snackbar notification when a new high-value ride is found.
     */
    private fun showNewRideSnackbar(price: Double, pickup: String, dropoff: String) {
        val message = String.format("New ride: $%.2f • %s → %s", price, pickup, dropoff)
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setAction("View") {
                // Switch to Monitor tab if not already there
                binding.tabLayout.getTabAt(0)?.select()
            }
            .show()
        appendLog("New ride found: \$$price from $pickup")
    }

    private fun registerMonitoringReceiver() {
        val filter = IntentFilter().apply {
            addAction(MonitoringForegroundService.ACTION_MONITORING_STATUS)
            addAction(MonitoringForegroundService.ACTION_NEW_RIDE_FOUND)
            addAction(MonitoringForegroundService.ACTION_FORCE_UPDATE)
            addAction(MonitoringForegroundService.ACTION_FORCE_UPDATE_CLEARED)
            addAction(MonitoringForegroundService.ACTION_UNPAIRED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(monitoringReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(monitoringReceiver, filter)
        }
        Log.d(TAG, "Monitoring receiver registered")
    }

    private fun unregisterMonitoringReceiver() {
        try {
            unregisterReceiver(monitoringReceiver)
            Log.d(TAG, "Monitoring receiver unregistered")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Receiver was not registered: ${e.message}")
        }
    }
}
