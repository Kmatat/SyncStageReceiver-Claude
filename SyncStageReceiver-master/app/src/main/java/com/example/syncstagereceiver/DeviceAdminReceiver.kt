package com.example.syncstagereceiver

import android.app.admin.DeviceAdminReceiver

/**
 * This receiver is required for the app to become a device owner.
 * It handles administrative events sent by the system. For basic kiosk mode,
 * this class can be left empty.
 */
class DeviceAdminReceiver : DeviceAdminReceiver()
