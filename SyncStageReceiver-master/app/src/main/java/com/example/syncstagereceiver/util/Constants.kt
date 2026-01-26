package com.example.syncstagereceiver.util

import android.os.Build

object Constants {
    // SharedPreferences Constants
    const val PREFS_NAME = "SyncStageReceiverPrefs"
    const val PREF_KEY_DEVICE_NAME = "device_name"

    // Default fallback name if no name is set
    val DEFAULT_DEVICE_NAME: String = "SyncStage-${Build.MODEL.replace(" ", "")}"

    // Broadcast Action Constants
    // --- FIX: Define the action string here ---
    const val ACTION_NAME_UPDATED = "com.example.syncstagereceiver.NAME_UPDATED"
}