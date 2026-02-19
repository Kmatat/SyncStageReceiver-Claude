package com.example.syncstagereceiver.kiosk

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.BatteryManager
import android.provider.Settings
import timber.log.Timber

class KioskManager(private val context: Context) {

    private val devicePolicyManager: DevicePolicyManager? = try {
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
    } catch (e: Exception) {
        Timber.e(e, "Failed to get DevicePolicyManager")
        null
    }

    /**
     * Sets the "Stay On While Plugged In" feature.
     * All operations are wrapped in try-catch to prevent crashes on devices
     * (e.g. Xiaomi MIUI) where device owner permission is not granted or
     * DevicePolicyManager behaves differently.
     */
    fun enableKioskMode(activity: Activity) {
        Timber.d("Initializing Kiosk Mode (Setting 'Stay On While Plugged In')")
        setStayOnWhilePluggedIn()

        try {
            val isOwner = devicePolicyManager?.isDeviceOwnerApp(context.packageName) ?: false
            if (isOwner) {
                Timber.i("Device is owner, but 'startLockTask' is disabled in code.")
            } else {
                Timber.w("Not Device Owner. Full Kiosk mode unavailable. App will run as default HOME launcher only.")
            }
        } catch (e: SecurityException) {
            Timber.w("SecurityException checking device owner status (common on Xiaomi/MIUI): ${e.message}")
        } catch (e: Exception) {
            Timber.w(e, "Could not check device owner status")
        }
    }

    private fun setStayOnWhilePluggedIn() {
        try {
            Settings.Global.putInt(
                context.contentResolver,
                Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                BatteryManager.BATTERY_PLUGGED_AC or BatteryManager.BATTERY_PLUGGED_USB or BatteryManager.BATTERY_PLUGGED_WIRELESS
            )
            Timber.d("Set STAY_ON_WHILE_PLUGGED_IN")
        } catch (e: SecurityException) {
            Timber.e(e, "Missing permission to set STAY_ON_WHILE_PLUGGED_IN. Requires WRITE_SECURE_SETTINGS.")
        } catch (e: Exception) {
            Timber.w("Could not set STAY_ON_WHILE_PLUGGED_IN: ${e.message}")
        }
    }
}