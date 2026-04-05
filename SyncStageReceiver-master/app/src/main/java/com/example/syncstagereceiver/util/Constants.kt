package com.example.syncstagereceiver.util

import android.os.Build

object Constants {
    // SharedPreferences Constants
    const val PREFS_NAME = "SyncStageReceiverPrefs"
    const val PREF_KEY_DEVICE_NAME = "device_name"

    // Default fallback name if no name is set
    val DEFAULT_DEVICE_NAME: String = "SyncStage-${Build.MODEL.replace(" ", "")}"

    // WiFi Credentials — loaded from BuildConfig (set in local.properties, not committed)
    val WIFI_SSID: String = com.example.syncstagereceiver.BuildConfig.WIFI_SSID
    val WIFI_PASSWORD: String = com.example.syncstagereceiver.BuildConfig.WIFI_PASSWORD

    // Broadcast Action Constants
    // --- FIX: Define the action string here ---
    const val ACTION_NAME_UPDATED = "com.example.syncstagereceiver.NAME_UPDATED"
}