package com.example.syncstagereceiver.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Handler
import android.os.Looper
import timber.log.Timber

/**
 * ==================== WIFI RECONNECT MANAGER ====================
 *
 * Monitors WiFi connectivity and automatically connects to the
 * designated train WiFi network (T2L2MFG). If disconnected, the
 * manager will programmatically add and connect to the network.
 *
 * Recovery strategy:
 * 1. On start, ensure the target network is saved and connect
 * 2. Monitor WiFi state every 10 seconds
 * 3. On disconnect, force reconnect to the target SSID
 * 4. After 3 failed attempts, toggle WiFi off/on and reconnect
 * 5. On Android 10+, use WifiNetworkSuggestion as fallback
 */
class WifiReconnectManager(private val context: Context) {

    companion object {
        private const val TARGET_SSID = "T2L2MFG"
        private const val TARGET_PASSWORD = "suhso1-bevsov-zechEp"
    }

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
    private var suggestionAdded = false

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
        Timber.i("WiFi Monitor: Started (target SSID: $TARGET_SSID)")

        // Register network callback for immediate notifications
        try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            Timber.e(e, "WiFi Monitor: Failed to register network callback")
        }

        // Ensure network is configured and connect
        ensureTargetNetworkAndConnect()

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
     * Ensure WiFi is enabled, the target network is saved, and we are connected to it.
     */
    @Suppress("DEPRECATION")
    private fun ensureTargetNetworkAndConnect() {
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
                handler.postDelayed({ ensureTargetNetworkAndConnect() }, 3000)
                return
            }

            // Check if already connected to the target SSID
            if (isConnectedToTargetSsid()) {
                Timber.i("WiFi Monitor: Already connected to $TARGET_SSID")
                return
            }

            // Try legacy API first (works on Android 9 / API 28)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                connectLegacy()
            } else {
                // Android 10+: try legacy first (may work on some OEM ROMs like Xiaomi)
                // then fall back to WifiNetworkSuggestion
                if (!connectLegacy()) {
                    connectWithSuggestion()
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "WiFi Monitor: Failed to ensure target network")
        }
    }

    /**
     * Legacy connection method (pre-Android 10).
     * Works reliably on Android TV / Xiaomi TV sticks.
     * Returns true if connection was triggered.
     */
    @Suppress("DEPRECATION")
    private fun connectLegacy(): Boolean {
        try {
            val quotedSsid = "\"$TARGET_SSID\""

            // Check if network is already saved
            val existingConfig = wifiManager.configuredNetworks?.find {
                it.SSID == quotedSsid
            }

            val networkId: Int
            if (existingConfig != null) {
                networkId = existingConfig.networkId
                Timber.i("WiFi Monitor: Found existing config for $TARGET_SSID (id=$networkId)")
            } else {
                // Add the network
                val config = WifiConfiguration().apply {
                    SSID = quotedSsid
                    preSharedKey = "\"$TARGET_PASSWORD\""
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                }
                networkId = wifiManager.addNetwork(config)
                if (networkId == -1) {
                    Timber.e("WiFi Monitor: Failed to add network $TARGET_SSID (addNetwork returned -1)")
                    return false
                }
                Timber.i("WiFi Monitor: Added network $TARGET_SSID (id=$networkId)")
            }

            // Disconnect from current network and connect to target
            wifiManager.disconnect()
            val enabled = wifiManager.enableNetwork(networkId, true)
            val reconnected = wifiManager.reconnect()
            Timber.i("WiFi Monitor: Connecting to $TARGET_SSID (enabled=$enabled, reconnect=$reconnected)")
            return enabled
        } catch (e: SecurityException) {
            Timber.w("WiFi Monitor: Legacy connect failed (SecurityException): ${e.message}")
            return false
        } catch (e: Exception) {
            Timber.e(e, "WiFi Monitor: Legacy connect failed")
            return false
        }
    }

    /**
     * Android 10+ fallback: use WifiNetworkSuggestion.
     * The system will auto-connect when it sees the SSID.
     */
    private fun connectWithSuggestion() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (suggestionAdded) return

        try {
            val suggestion = WifiNetworkSuggestion.Builder()
                .setSsid(TARGET_SSID)
                .setWpa2Passphrase(TARGET_PASSWORD)
                .setIsAppInteractionRequired(false)
                .build()

            // Remove any existing suggestions first
            wifiManager.removeNetworkSuggestions(listOf(suggestion))

            val status = wifiManager.addNetworkSuggestions(listOf(suggestion))
            if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                suggestionAdded = true
                Timber.i("WiFi Monitor: Network suggestion added for $TARGET_SSID")
            } else {
                Timber.w("WiFi Monitor: Failed to add network suggestion (status=$status)")
            }
        } catch (e: Exception) {
            Timber.e(e, "WiFi Monitor: Network suggestion failed")
        }
    }

    /**
     * Check if currently connected to the target SSID.
     */
    @Suppress("DEPRECATION")
    private fun isConnectedToTargetSsid(): Boolean {
        if (!isWifiConnected()) return false
        try {
            val connectionInfo = wifiManager.connectionInfo ?: return false
            val currentSsid = connectionInfo.ssid?.replace("\"", "") ?: return false
            return currentSsid == TARGET_SSID
        } catch (e: Exception) {
            return false
        }
    }

    private val checkRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return

            try {
                val connectedToTarget = isConnectedToTargetSsid()
                val isWifiOn = isWifiConnected()

                if (!connectedToTarget) {
                    consecutiveFailures++
                    if (isWifiOn) {
                        Timber.w("WiFi Monitor: Connected to wrong network, switching to $TARGET_SSID (failure count: $consecutiveFailures)")
                    } else {
                        Timber.w("WiFi Monitor: WiFi disconnected (failure count: $consecutiveFailures)")
                    }

                    if (consecutiveFailures >= MAX_FAILURES_BEFORE_TOGGLE) {
                        toggleWifiAndReconnect()
                        consecutiveFailures = 0
                    } else {
                        ensureTargetNetworkAndConnect()
                    }
                } else {
                    if (consecutiveFailures > 0) {
                        Timber.i("WiFi Monitor: Connected to $TARGET_SSID after $consecutiveFailures failures")
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
     * Nuclear option: toggle WiFi off and back on, then reconnect to target.
     */
    @Suppress("DEPRECATION")
    private fun toggleWifiAndReconnect() {
        val now = System.currentTimeMillis()
        if (now - lastReconnectTime < TOGGLE_COOLDOWN_MS) {
            Timber.d("WiFi Monitor: Toggle cooldown active, skipping")
            return
        }

        try {
            Timber.w("WiFi Monitor: Toggling WiFi OFF/ON and reconnecting to $TARGET_SSID")

            try {
                wifiManager.isWifiEnabled = false
            } catch (e: SecurityException) {
                Timber.w("WiFi Monitor: Cannot toggle WiFi programmatically: ${e.message}")
                return
            }

            handler.postDelayed({
                try {
                    wifiManager.isWifiEnabled = true
                    Timber.i("WiFi Monitor: WiFi re-enabled, connecting to $TARGET_SSID")
                    // Give WiFi a moment to scan, then connect
                    handler.postDelayed({ ensureTargetNetworkAndConnect() }, 3000)
                } catch (e: Exception) {
                    Timber.e(e, "WiFi Monitor: Failed to re-enable WiFi")
                }
            }, 2000)

        } catch (e: Exception) {
            Timber.e(e, "WiFi Monitor: WiFi toggle failed")
        }
    }
}
