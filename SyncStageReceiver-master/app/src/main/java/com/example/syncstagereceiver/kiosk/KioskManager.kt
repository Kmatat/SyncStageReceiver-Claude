package com.example.syncstagereceiver.kiosk

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.BatteryManager
import android.provider.Settings
import com.example.syncstagereceiver.DeviceAdminReceiver
import timber.log.Timber

class KioskManager(private val context: Context) {

    private val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)

    /**
     * UPDATED:
     * This method now only sets the "Stay On While Plugged In" feature.
     * The call to 'startLockTask()' has been removed to prevent the crash.
     * The app's "kiosk" behavior is now handled *only* by it being the default
     * Home app in the AndroidManifest.xml.
     */
    fun enableKioskMode(activity: Activity) {
        Timber.d("Initializing Kiosk Mode (Setting 'Stay On While Plugged In')")
        setStayOnWhilePluggedIn()

        if (devicePolicyManager.isDeviceOwnerApp(context.packageName)) {
            // devicePolicyManager.setLockTaskPackages(adminComponent, arrayOf(context.packageName))
            // try {
            //     activity.startLockTask() // <-- REMOVED THIS LINE TO PREVENT CRASH
            //     Timber.i("Kiosk Mode (Lock Task) activated.")
            // } catch (e: Exception) {
            //     Timber.e(e, "Failed to start lock task.")
            // }
            Timber.i("Device is owner, but 'startLockTask' is disabled in code.")
        } else {
            Timber.w("Not Device Owner. Full Kiosk mode unavailable.")
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