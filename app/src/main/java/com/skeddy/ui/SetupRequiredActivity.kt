package com.skeddy.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.skeddy.accessibility.SkeddyAccessibilityService
import com.skeddy.databinding.ActivitySetupRequiredBinding
import com.skeddy.utils.PermissionUtils

/**
 * Screen shown when Accessibility Service is disabled (NOT_CONFIGURED state).
 *
 * Displays a warning icon, localized title/message, and a button that
 * opens Android Accessibility Settings via [PermissionUtils.openAccessibilitySettings].
 *
 */
class SetupRequiredActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupRequiredBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivitySetupRequiredBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.openSettingsButton.setOnClickListener {
            PermissionUtils.openAccessibilitySettings(this)
        }
    }

    override fun onResume() {
        super.onResume()
        // Auto-transition: when user returns from Settings with Accessibility Service enabled,
        // navigate to MainActivity and finish this screen.
        if (PermissionUtils.isAccessibilityServiceEnabled(
                this,
                SkeddyAccessibilityService::class.java
            )
        ) {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
            finish()
        }
    }

    companion object {
        /**
         * Creates an [Intent] to launch [SetupRequiredActivity].
         */
        fun createIntent(context: Context): Intent {
            return Intent(context, SetupRequiredActivity::class.java)
        }
    }
}
