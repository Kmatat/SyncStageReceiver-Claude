package com.example.syncstagereceiver.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import timber.log.Timber

/**
 * ==================== WIFI RECONNECT MANAGER ====================
 *
 * Monitors WiFi connectivity and automatically attempts reconnection
 * when the connection drops. This replaces the manual process of
 * going to Settings and pressing "Connect".
 *
 * Recovery strategy:
 * 1. Monitor WiFi state every 10 seconds
 * 2. On disconnect, attempt programmatic reconnection
 * 3. After 3 failed reconnection attempts, toggle WiFi off/on
 * 4. Log every disconnect/reconnect event with timestamps
 */
class WifiReconnectManager(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val wifiManager: WifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }
    private val connectivityManager: ConnectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private var isRunning = false
    private var consecutiveFailures = 0
    private var lastDisconnectTime: Long = 0L
    private var lastReconnectTime: Long = 0L

    private val CHECK_INTERVAL_MS = 10_000L       // Check every 10 seconds
    private val MAX_FAILURES_BEFORE_TOGGLE = 3     // Toggle WiFi after 3 failures
    private val TOGGLE_COOLDOWN_MS = 30_000L       // Wait 30s between WiFi toggles

    // Network callback for immediate disconnect detection
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            lastDisconnectTime = System.currentTimeMillis()
            Timber.w("WiFi Monitor: Network lost at $lastDisconnectTime")
        }

        override fun onAvailable(network: Network) {
            if (lastDisconnectTime > 0) {
                val downtime = System.currentTimeMillis() - lastDisconnectTime
                Timber.i("WiFi Monitor: Network restored after ${downtime}ms")
                lastReconnectTime = System.currentTimeMillis()
                consecutiveFailures = 0
            }
        }
    }

    /**
     * Start monitoring WiFi connectivity.
     */
    fun start() {
        if (isRunning) return
        isRunning = true
        Timber.i("WiFi Monitor: Started")

        // Register network callback for immediate notifications
        try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            Timber.e(e, "WiFi Monitor: Failed to register network callback")
        }

        // Start periodic check
        handler.postDelayed(checkRunnable, CHECK_INTERVAL_MS)
    }

    /**
     * Stop monitoring WiFi connectivity.
     */
    fun stop() {
        isRunning = false
        handler.removeCallbacks(checkRunnable)
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            // Callback may not have been registered
        }
        Timber.i("WiFi Monitor: Stopped")
    }

    private val checkRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return

            try {
                val isWifiConnected = isWifiConnected()

                if (!isWifiConnected) {
                    consecutiveFailures++
                    Timber.w("WiFi Monitor: WiFi disconnected (failure count: $consecutiveFailures)")

                    if (consecutiveFailures >= MAX_FAILURES_BEFORE_TOGGLE) {
                        toggleWifi()
                        consecutiveFailures = 0
                    } else {
                        attemptReconnection()
                    }
                } else {
                    if (consecutiveFailures > 0) {
                        Timber.i("WiFi Monitor: WiFi reconnected after $consecutiveFailures failures")
                        consecutiveFailures = 0
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "WiFi Monitor: Error during check")
            }

            if (isRunning) {
                handler.postDelayed(this, CHECK_INTERVAL_MS)
            }
        }
    }

    /**
     * Check if WiFi is currently connected with internet capability.
     */
    private fun isWifiConnected(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * Attempt to reconnect to WiFi by reassociating with the current network.
     */
    @Suppress("DEPRECATION")
    private fun attemptReconnection() {
        try {
            Timber.i("WiFi Monitor: Attempting reconnection...")

            if (!wifiManager.isWifiEnabled) {
                Timber.w("WiFi Monitor: WiFi is disabled, enabling...")
                wifiManager.isWifiEnabled = true
                return
            }

            // Trigger reconnection to the saved/configured network
            wifiManager.reconnect()
            Timber.i("WiFi Monitor: Reconnection triggered at ${System.currentTimeMillis()}")
        } catch (e: Exception) {
            Timber.e(e, "WiFi Monitor: Reconnection attempt failed")
        }
    }

    /**
     * Nuclear option: toggle WiFi off and back on.
     * This is equivalent to manually going to Settings and toggling WiFi.
     */
    @Suppress("DEPRECATION")
    private fun toggleWifi() {
        val now = System.currentTimeMillis()
        if (now - lastReconnectTime < TOGGLE_COOLDOWN_MS) {
            Timber.d("WiFi Monitor: Toggle cooldown active, skipping")
            return
        }

        try {
            Timber.w("WiFi Monitor: Toggling WiFi OFF/ON (nuclear reconnection)")

            // Turn WiFi off
            wifiManager.isWifiEnabled = false

            // Wait briefly, then turn back on
            handler.postDelayed({
                try {
                    wifiManager.isWifiEnabled = true
                    Timber.i("WiFi Monitor: WiFi re-enabled, waiting for connection...")
                } catch (e: Exception) {
                    Timber.e(e, "WiFi Monitor: Failed to re-enable WiFi")
                }
            }, 2000)  // 2 second delay between off and on

        } catch (e: Exception) {
            Timber.e(e, "WiFi Monitor: WiFi toggle failed")
        }
    }
}
