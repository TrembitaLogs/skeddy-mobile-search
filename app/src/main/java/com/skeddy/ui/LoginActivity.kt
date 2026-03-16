package com.skeddy.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Patterns
import android.view.View
import android.view.WindowManager
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.skeddy.R
import com.skeddy.data.BlacklistDatabase
import com.skeddy.data.BlacklistRepository
import com.skeddy.databinding.ActivityLoginBinding
import com.skeddy.logging.SkeddyLogger
import com.skeddy.network.ApiResult
import com.skeddy.network.DeviceTokenManager
import com.skeddy.network.LoginErrorReason
import com.skeddy.network.NetworkModule
import com.skeddy.network.SkeddyApi
import com.skeddy.network.SkeddyServerClient
import com.skeddy.network.models.SearchLoginRequest
import com.skeddy.network.models.SearchLoginResponse
import com.skeddy.service.PendingRideQueue
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.TimeZone

/**
 * Screen for logging into Skeddy Search using email and password credentials.
 *
 * Handles input validation, UI state transitions (loading, error, success),
 * and server communication for authentication.
 *
 * On successful login, saves the device token and navigates to [MainActivity].
 * On re-login (device already logged in), clears blacklist and pending ride queue
 * before saving the new token.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    @VisibleForTesting
    internal lateinit var serverClient: SkeddyServerClient

    @VisibleForTesting
    internal lateinit var deviceTokenManager: DeviceTokenManager

    @VisibleForTesting
    internal lateinit var blacklistRepository: BlacklistRepository

    @VisibleForTesting
    internal lateinit var pendingRideQueue: PendingRideQueue

    /**
     * Holds the current login coroutine job.
     * Cancelled in [onDestroy] to prevent leaks.
     */
    @VisibleForTesting
    internal var loginJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initDependencies()
        setupInputValidation()
        setupClickListeners()
    }

    /**
     * Initializes dependencies using the project's singleton/lazy patterns.
     * Separate method for testability — tests can set fields before calling [onLoginClicked].
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
        binding.loginButton.isEnabled = false

        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                binding.loginButton.isEnabled = isFormValid(
                    binding.emailInput.text?.toString(),
                    binding.passwordInput.text?.toString()
                )
                if (binding.errorText.visibility == View.VISIBLE) {
                    binding.errorText.visibility = View.INVISIBLE
                }
            }
        }

        binding.emailInput.addTextChangedListener(textWatcher)
        binding.passwordInput.addTextChangedListener(textWatcher)
    }

    private fun setupClickListeners() {
        binding.loginButton.setOnClickListener {
            onLoginClicked()
        }
    }

    /**
     * Called when the Login button is tapped with valid email and password.
     *
     * Launches a coroutine that:
     * 1. Shows loading state
     * 2. Builds [SearchLoginRequest] with email, password, deviceId, timezone, deviceModel
     * 3. Calls [SkeddyServerClient.searchLogin]
     * 4. On success: handles re-login cleanup, saves token, navigates to MainActivity
     * 5. On error: shows appropriate localized error message
     */
    @VisibleForTesting
    internal fun onLoginClicked() {
        val email = binding.emailInput.text?.toString() ?: return
        val password = binding.passwordInput.text?.toString() ?: return
        if (!isFormValid(email, password)) return

        showLoading()

        val wasLoggedIn = deviceTokenManager.isLoggedIn()

        loginJob = lifecycleScope.launch {
            val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".take(255)

            val request = SearchLoginRequest(
                email = email,
                password = password,
                deviceId = deviceTokenManager.getDeviceId(),
                deviceModel = deviceModel,
                timezone = TimeZone.getDefault().id
            )

            SkeddyLogger.i(TAG, "Login: attempting with email=****** (wasLoggedIn=$wasLoggedIn)")

            val result = serverClient.searchLogin(request)
            handleLoginResult(result, wasLoggedIn)
        }
    }

    /**
     * Processes the login API result.
     *
     * On success: if this is a re-login, clears blacklist and pending ride queue
     * first, then saves the new device token and navigates to [MainActivity].
     *
     * On error: maps each [ApiResult] subtype to an appropriate localized string.
     */
    @VisibleForTesting
    internal suspend fun handleLoginResult(
        result: ApiResult<SearchLoginResponse>,
        wasLoggedIn: Boolean
    ) {
        when (result) {
            is ApiResult.Success -> {
                val response = result.data

                if (wasLoggedIn) {
                    SkeddyLogger.i(TAG, "Re-login: clearing blacklist and pending ride queue")
                    blacklistRepository.clearAll()
                    pendingRideQueue.clear()
                }

                deviceTokenManager.saveDeviceToken(response.deviceToken)
                SkeddyLogger.i(TAG, "Login: success (userId=${response.userId})")
                showSuccess()
            }

            is ApiResult.LoginError -> when (result.reason) {
                LoginErrorReason.INVALID_CREDENTIALS -> {
                    SkeddyLogger.w(TAG, "Login: invalid credentials")
                    showError(getString(R.string.login_error_invalid_credentials))
                }
            }

            is ApiResult.NetworkError -> {
                SkeddyLogger.w(TAG, "Login: network error")
                showError(getString(R.string.login_error_network))
            }

            else -> {
                SkeddyLogger.w(TAG, "Login: unexpected error: $result")
                showError(getString(R.string.login_error_unknown))
            }
        }
    }

    /**
     * Transitions UI to loading state:
     * disables and hides the Login button, shows progress indicator,
     * hides any error message, and disables input fields.
     */
    @VisibleForTesting
    internal fun showLoading() {
        binding.loginButton.isEnabled = false
        binding.loginButton.visibility = View.INVISIBLE
        binding.progressBar.visibility = View.VISIBLE
        binding.errorText.visibility = View.INVISIBLE
        binding.emailInput.isEnabled = false
        binding.passwordInput.isEnabled = false
    }

    /**
     * Transitions UI to error state:
     * hides progress indicator, shows Login button (enabled if form is valid),
     * displays the error [message], and re-enables input fields.
     */
    @VisibleForTesting
    internal fun showError(message: String) {
        binding.progressBar.visibility = View.INVISIBLE
        binding.loginButton.visibility = View.VISIBLE
        binding.loginButton.isEnabled = isFormValid(
            binding.emailInput.text?.toString(),
            binding.passwordInput.text?.toString()
        )
        binding.errorText.text = message
        binding.errorText.visibility = View.VISIBLE
        binding.emailInput.isEnabled = true
        binding.passwordInput.isEnabled = true
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
        loginJob?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LoginActivity"

        /**
         * Creates an [Intent] to launch [LoginActivity].
         */
        fun createIntent(context: Context): Intent {
            return Intent(context, LoginActivity::class.java)
        }

        /**
         * Validates that the email is a valid format and password is non-empty.
         */
        @VisibleForTesting
        internal fun isFormValid(email: String?, password: String?): Boolean {
            if (email.isNullOrBlank() || password.isNullOrBlank()) return false
            return Patterns.EMAIL_ADDRESS.matcher(email).matches()
        }
    }
}
