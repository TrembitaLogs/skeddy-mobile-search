package com.skeddy.util

import android.content.Context
import android.os.PowerManager
import com.skeddy.accessibility.SkeddyAccessibilityService
import com.skeddy.network.models.DeviceHealth
import com.skeddy.utils.PermissionUtils

/**
 * Collects device health information for ping requests.
 *
 * Gathers accessibility service status, Lyft Driver foreground state,
 * and screen-on status into a [DeviceHealth] object.
 */
class DeviceHealthCollector(private val context: Context) {

    /**
     * Collects current device health state.
     *
     * @return [DeviceHealth] with accessibility, Lyft, and screen status
     */
    fun collect(): DeviceHealth {
        return DeviceHealth(
            accessibilityEnabled = PermissionUtils.isAccessibilityServiceEnabled(
                context,
                SkeddyAccessibilityService::class.java
            ),
            lyftRunning = LyftAppMonitor.isLyftInForeground(context),
            screenOn = isScreenOn()
        )
    }

    private fun isScreenOn(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return powerManager.isInteractive
    }
}
