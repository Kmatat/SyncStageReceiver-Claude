package com.example.syncstagereceiver.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import timber.log.Timber

/**
 * ==================== WIFI RECONNECT MANAGER ====================
 *
 * Monitors WiFi connectivity and automatically attempts reconnection
 * when the connection drops. Uses the device's already-saved WiFi networks
 * (configured during initial device setup) — does NOT store credentials.
 *
 * Recovery strategy:
 * 1. Monitor WiFi state every 10 seconds
 * 2. On disconnect, trigger reconnect to saved networks
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

    fun start() {
        if (isRunning) return
        isRunning = true
        Timber.i("WiFi Monitor: Started (reconnects to saved networks)")

        // Register network callback for immediate notifications
        try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            Timber.e(e, "WiFi Monitor: Failed to register network callback")
        }

        // If not connected, attempt reconnection immediately
        if (!isWifiConnected()) {
            Timber.i("WiFi Monitor: Not connected on start, triggering reconnect")
            reconnectToSavedNetwork()
        }

        // Start periodic check
        handler.postDelayed(checkRunnable, CHECK_INTERVAL_MS)
    }

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

    /**
     * Trigger reconnection to saved WiFi networks.
     * The device already has WiFi networks configured from initial setup.
     * We just tell the system to reconnect.
     */
    @Suppress("DEPRECATION")
    private fun reconnectToSavedNetwork() {
        try {
            if (!wifiManager.isWifiEnabled) {
                Timber.w("WiFi Monitor: WiFi is disabled, enabling...")
                try {
                    wifiManager.isWifiEnabled = true
                } catch (e: SecurityException) {
                    Timber.w("WiFi Monitor: Cannot enable WiFi programmatically: ${e.message}")
                    return
                }
                // Give WiFi a moment to enable, then retry
                handler.postDelayed({ reconnectToSavedNetwork() }, 2000)
                return
            }

            // Trigger reconnect — system will pick the best saved network
            wifiManager.reconnect()
            Timber.i("WiFi Monitor: Reconnect triggered (using saved networks)")
        } catch (e: Exception) {
            Timber.e(e, "WiFi Monitor: Failed to trigger reconnection")
        }
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
                        reconnectToSavedNetwork()
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

    private fun isWifiConnected(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * Nuclear option: toggle WiFi off and back on.
     * After re-enabling, the system auto-connects to saved networks.
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

            try {
                wifiManager.isWifiEnabled = false
            } catch (e: SecurityException) {
                Timber.w("WiFi Monitor: Cannot toggle WiFi programmatically: ${e.message}")
                return
            }

            handler.postDelayed({
                try {
                    wifiManager.isWifiEnabled = true
                    Timber.i("WiFi Monitor: WiFi re-enabled, system will auto-connect to saved networks")
                } catch (e: Exception) {
                    Timber.e(e, "WiFi Monitor: Failed to re-enable WiFi")
                }
            }, 2000)

        } catch (e: Exception) {
            Timber.e(e, "WiFi Monitor: WiFi toggle failed")
        }
    }
}
