package com.skeddy.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.skeddy.data.SkeddyPreferences
import com.skeddy.databinding.ActivityForceUpdateBinding
import com.skeddy.service.MonitoringForegroundService

/**
 * Screen shown when app version is outdated and server requires update (FORCE_UPDATE state).
 *
 * Displays a warning icon, localized title/message, and an "Update" button that opens
 * the [updateUrl] via [Intent.ACTION_VIEW]. Back navigation is blocked so the user
 * cannot dismiss this screen.
 *
 * The background ping cycle continues while this screen is shown. When the server
 * clears the force_update flag, the app automatically transitions to PAIRED state.
 */
class ForceUpdateActivity : AppCompatActivity() {

    private lateinit var binding: ActivityForceUpdateBinding
    private lateinit var preferences: SkeddyPreferences

    private val forceUpdateClearedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MonitoringForegroundService.ACTION_FORCE_UPDATE_CLEARED) {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityForceUpdateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferences = SkeddyPreferences(this)

        val updateUrl = intent.getStringExtra(EXTRA_UPDATE_URL)

        binding.updateButton.setOnClickListener {
            if (!updateUrl.isNullOrEmpty()) {
                val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl))
                startActivity(viewIntent)
            }
        }

        // Block back navigation — user must update the app
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Intentionally empty — back navigation is blocked
            }
        })
    }

    override fun onResume() {
        super.onResume()
        checkForceUpdateStatus()
        val filter = IntentFilter(MonitoringForegroundService.ACTION_FORCE_UPDATE_CLEARED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(forceUpdateClearedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(forceUpdateClearedReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(forceUpdateClearedReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver was not registered
        }
    }

    /**
     * Checks if force update is still active via persisted preferences.
     * If the server cleared force update while this Activity was paused
     * (e.g., user was in Chrome after tapping Update), the broadcast
     * would have been missed. This method ensures the Activity closes.
     */
    internal fun checkForceUpdateStatus() {
        if (!preferences.forceUpdateActive) {
            Log.d(TAG, "Force update cleared, returning to main")
            finish()
        }
    }

    companion object {
        private const val TAG = "ForceUpdateActivity"
        const val EXTRA_UPDATE_URL = "extra_update_url"

        /**
         * Creates an [Intent] to launch [ForceUpdateActivity] with the given [updateUrl].
         */
        fun createIntent(context: Context, updateUrl: String?): Intent {
            return Intent(context, ForceUpdateActivity::class.java).apply {
                putExtra(EXTRA_UPDATE_URL, updateUrl)
            }
        }
    }
}
