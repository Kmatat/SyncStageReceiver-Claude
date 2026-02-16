package com.example.syncstagereceiver.util

import android.os.Build

object Constants {
    // SharedPreferences Constants
    const val PREFS_NAME = "SyncStageReceiverPrefs"
    const val PREF_KEY_DEVICE_NAME = "device_name"

    // Default fallback name if no name is set
    val DEFAULT_DEVICE_NAME: String = "SyncStage-${Build.MODEL.replace(" ", "")}"

    // WiFi Credentials — device will auto-connect to this network
    const val WIFI_SSID = "T2L2MFG"
    const val WIFI_PASSWORD = "suhso1-bevsov-zechEp"

    // Broadcast Action Constants
    // --- FIX: Define the action string here ---
    const val ACTION_NAME_UPDATED = "com.example.syncstagereceiver.NAME_UPDATED"
}