package com.skeddy.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.view.WindowManager
import android.text.TextWatcher
import android.view.View
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.skeddy.R
import com.skeddy.data.BlacklistDatabase
import com.skeddy.data.BlacklistRepository
import com.skeddy.databinding.ActivityPairingBinding
import com.skeddy.logging.SkeddyLogger
import com.skeddy.network.ApiResult
import com.skeddy.network.DeviceTokenManager
import com.skeddy.network.NetworkModule
import com.skeddy.network.PairingErrorReason
import com.skeddy.network.SkeddyApi
import com.skeddy.network.SkeddyServerClient
import com.skeddy.network.models.PairingRequest
import com.skeddy.network.models.PairingResponse
import com.skeddy.service.PendingRideQueue
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.TimeZone

/**
 * Screen for pairing this search device with the main Skeddy app
 * using a 6-digit numeric code.
 *
 * Handles input validation, UI state transitions (loading, error, success),
 * and server communication for pairing confirmation.
 *
 * On successful pairing, saves the device token and navigates to [MainActivity].
 * On re-pairing (device already paired), clears blacklist and pending ride queue
 * before saving the new token.
 */
class PairingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPairingBinding

    @VisibleForTesting
    internal lateinit var serverClient: SkeddyServerClient

    @VisibleForTesting
    internal lateinit var deviceTokenManager: DeviceTokenManager

    @VisibleForTesting
    internal lateinit var blacklistRepository: BlacklistRepository

    @VisibleForTesting
    internal lateinit var pendingRideQueue: PendingRideQueue

    /**
     * Holds the current pairing coroutine job.
     * Cancelled in [onDestroy] to prevent leaks.
     */
    @VisibleForTesting
    internal var pairingJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityPairingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initDependencies()
        setupInputValidation()
        setupClickListeners()
    }

    /**
     * Initializes dependencies using the project's singleton/lazy patterns.
     * Separate method for testability — tests can set fields before calling [onPairClicked].
     */
    @VisibleForTesting
    internal fun initDependencies() {
        if (::deviceTokenManager.isInitialized) return

        deviceTokenManager = DeviceTokenManager(this)
        val api = NetworkModule.createService<SkeddyApi>()
        serverClient = SkeddyServerClient(api, deviceTokenManager)

        val blacklistDb = BlacklistDatabase.getInstance(this)
        blacklistRepository = BlacklistRepository(blacklistDb.blacklistDao())
        pendingRideQueue = PendingRideQueue(this)
    }

    private fun setupInputValidation() {
        binding.pairButton.isEnabled = false

        binding.codeInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                binding.pairButton.isEnabled = isCodeValid(s?.toString())
                if (binding.errorText.visibility == View.VISIBLE) {
                    binding.errorText.visibility = View.INVISIBLE
                }
            }
        })
    }

    private fun setupClickListeners() {
        binding.pairButton.setOnClickListener {
            onPairClicked()
        }
    }

    /**
     * Called when the Pair button is tapped with a valid 6-digit code.
     *
     * Launches a coroutine that:
     * 1. Shows loading state
     * 2. Builds [PairingRequest] with code, deviceId, and timezone
     * 3. Calls [SkeddyServerClient.confirmPairing]
     * 4. On success: handles re-pairing cleanup, saves token, navigates to MainActivity
     * 5. On error: shows appropriate localized error message
     */
    @VisibleForTesting
    internal fun onPairClicked() {
        val code = binding.codeInput.text?.toString() ?: return
        if (!isCodeValid(code)) return

        showLoading()

        val wasPaired = deviceTokenManager.isPaired()

        pairingJob = lifecycleScope.launch {
            val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".take(255)

            val request = PairingRequest(
                code = code,
                deviceId = deviceTokenManager.getDeviceId(),
                deviceModel = deviceModel,
                timezone = TimeZone.getDefault().id
            )

            SkeddyLogger.i(TAG, "Pairing: attempting with code=****** (wasPaired=$wasPaired)")

            val result = serverClient.confirmPairing(request)
            handlePairingResult(result, wasPaired)
        }
    }

    /**
     * Processes the pairing API result.
     *
     * On success: if this is a re-pairing, clears blacklist and pending ride queue
     * first, then saves the new device token and navigates to [MainActivity].
     *
     * On error: maps each [ApiResult] subtype to an appropriate localized string.
     */
    @VisibleForTesting
    internal suspend fun handlePairingResult(
        result: ApiResult<PairingResponse>,
        wasPaired: Boolean
    ) {
        when (result) {
            is ApiResult.Success -> {
                val response = result.data

                if (wasPaired) {
                    SkeddyLogger.i(TAG, "Re-pairing: clearing blacklist and pending ride queue")
                    blacklistRepository.clearAll()
                    pendingRideQueue.clear()
                }

                deviceTokenManager.saveDeviceToken(response.deviceToken)
                SkeddyLogger.i(TAG, "Pairing: success (userId=${response.userId})")
                showSuccess()
            }

            is ApiResult.PairingError -> when (result.reason) {
                PairingErrorReason.INVALID_OR_EXPIRED -> {
                    SkeddyLogger.w(TAG, "Pairing: invalid or expired code")
                    showError(getString(R.string.pairing_error_invalid))
                }
                PairingErrorReason.ALREADY_USED -> {
                    SkeddyLogger.w(TAG, "Pairing: code already used")
                    showError(getString(R.string.pairing_error_already_used))
                }
            }

            is ApiResult.NetworkError -> {
                SkeddyLogger.w(TAG, "Pairing: network error")
                showError(getString(R.string.pairing_error_network))
            }

            else -> {
                SkeddyLogger.w(TAG, "Pairing: unexpected error: $result")
                showError(getString(R.string.pairing_error_unknown))
            }
        }
    }

    /**
     * Transitions UI to loading state:
     * disables and hides the Pair button, shows progress indicator,
     * hides any error message, and disables input field.
     */
    @VisibleForTesting
    internal fun showLoading() {
        binding.pairButton.isEnabled = false
        binding.pairButton.visibility = View.INVISIBLE
        binding.progressBar.visibility = View.VISIBLE
        binding.errorText.visibility = View.INVISIBLE
        binding.codeInput.isEnabled = false
    }

    /**
     * Transitions UI to error state:
     * hides progress indicator, shows Pair button (enabled if code is valid),
     * displays the error [message], and re-enables input field.
     */
    @VisibleForTesting
    internal fun showError(message: String) {
        binding.progressBar.visibility = View.INVISIBLE
        binding.pairButton.visibility = View.VISIBLE
        binding.pairButton.isEnabled = isCodeValid(binding.codeInput.text?.toString())
        binding.errorText.text = message
        binding.errorText.visibility = View.VISIBLE
        binding.codeInput.isEnabled = true
    }

    /**
     * Transitions UI to success state:
     * navigates to [MainActivity] and finishes this activity.
     */
    @VisibleForTesting
    internal fun showSuccess() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        pairingJob?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PairingActivity"

        @VisibleForTesting
        internal const val CODE_LENGTH = 6

        /**
         * Creates an [Intent] to launch [PairingActivity].
         */
        fun createIntent(context: Context): Intent {
            return Intent(context, PairingActivity::class.java)
        }

        /**
         * Validates that the pairing code is exactly [CODE_LENGTH] digits.
         */
        @VisibleForTesting
        internal fun isCodeValid(code: String?): Boolean {
            return code != null && code.length == CODE_LENGTH && code.all { it.isDigit() }
        }
    }
}
