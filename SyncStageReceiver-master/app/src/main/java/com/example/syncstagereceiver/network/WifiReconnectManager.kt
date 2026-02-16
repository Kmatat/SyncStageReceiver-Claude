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
import com.example.syncstagereceiver.util.Constants
import timber.log.Timber

/**
 * ==================== WIFI RECONNECT MANAGER ====================
 *
 * Monitors WiFi connectivity and automatically attempts reconnection
 * when the connection drops. This replaces the manual process of
 * going to Settings and pressing "Connect".
 *
 * Recovery strategy:
 * 1. On start, ensure the target WiFi network (from Constants) is configured
 * 2. Monitor WiFi state every 10 seconds
 * 3. On disconnect, attempt to connect to the target network
 * 4. After 3 failed reconnection attempts, toggle WiFi off/on
 * 5. Log every disconnect/reconnect event with timestamps
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
    private var targetNetworkId: Int = -1

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
     * Also ensures the target WiFi network is configured for auto-connection.
     */
    fun start() {
        if (isRunning) return
        isRunning = true
        Timber.i("WiFi Monitor: Started (target SSID: ${Constants.WIFI_SSID})")

        // Ensure the target WiFi network is configured
        ensureTargetNetworkConfigured()

        // Register network callback for immediate notifications
        try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            Timber.e(e, "WiFi Monitor: Failed to register network callback")
        }

        // If not connected, attempt to connect to the target network immediately
        if (!isWifiConnected()) {
            Timber.i("WiFi Monitor: Not connected on start, attempting to connect to ${Constants.WIFI_SSID}")
            connectToTargetNetwork()
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

    /**
     * Ensure the target WiFi network (SSID/password from Constants) is
     * configured on the device so it can auto-connect.
     *
     * - API 28 (Android 9): Uses WifiConfiguration + addNetwork
     * - API 29+ (Android 10+): Uses WifiNetworkSuggestion
     */
    private fun ensureTargetNetworkConfigured() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ — use WifiNetworkSuggestion
                addNetworkSuggestion()
            } else {
                // Android 9 — use legacy WifiConfiguration
                addNetworkLegacy()
            }
        } catch (e: Exception) {
            Timber.e(e, "WiFi Monitor: Failed to configure target network")
        }
    }

    /**
     * Android 10+ (API 29): Add the target network as a suggestion.
     * The system will auto-connect when the network is available.
     */
    private fun addNetworkSuggestion() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        val suggestion = WifiNetworkSuggestion.Builder()
            .setSsid(Constants.WIFI_SSID)
            .setWpa2Passphrase(Constants.WIFI_PASSWORD)
            .setIsAppInteractionRequired(false)
            .build()

        // Remove any previous suggestions from this app, then add fresh
        wifiManager.removeNetworkSuggestions(listOf(suggestion))
        val status = wifiManager.addNetworkSuggestions(listOf(suggestion))

        if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
            Timber.i("WiFi Monitor: Network suggestion added for ${Constants.WIFI_SSID}")
        } else {
            Timber.w("WiFi Monitor: Failed to add network suggestion, status=$status")
        }
    }

    /**
     * Android 9 (API 28): Add the target network using the legacy API.
     * This directly adds and enables the network configuration.
     */
    @Suppress("DEPRECATION")
    private fun addNetworkLegacy() {
        val ssid = Constants.WIFI_SSID
        val password = Constants.WIFI_PASSWORD

        // Check if this network is already configured
        val existingConfigs = wifiManager.configuredNetworks
        val quotedSsid = "\"$ssid\""
        val existingConfig = existingConfigs?.find { it.SSID == quotedSsid }

        if (existingConfig != null) {
            targetNetworkId = existingConfig.networkId
            Timber.i("WiFi Monitor: Target network already configured (id=$targetNetworkId)")
            return
        }

        // Create new WifiConfiguration for WPA2
        val wifiConfig = WifiConfiguration().apply {
            SSID = quotedSsid
            preSharedKey = "\"$password\""
            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
        }

        targetNetworkId = wifiManager.addNetwork(wifiConfig)
        if (targetNetworkId != -1) {
            Timber.i("WiFi Monitor: Target network added (id=$targetNetworkId, SSID=$ssid)")
        } else {
            Timber.e("WiFi Monitor: Failed to add target network $ssid")
        }
    }

    /**
     * Actively connect to the target WiFi network.
     */
    @Suppress("DEPRECATION")
    private fun connectToTargetNetwork() {
        try {
            if (!wifiManager.isWifiEnabled) {
                Timber.w("WiFi Monitor: WiFi is disabled, enabling...")
                wifiManager.isWifiEnabled = true
                // Give WiFi a moment to enable, then retry
                handler.postDelayed({ connectToTargetNetwork() }, 2000)
                return
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                // Android 9: Use enableNetwork to force connection to our target
                if (targetNetworkId == -1) {
                    // Try to find it in configured networks
                    val quotedSsid = "\"${Constants.WIFI_SSID}\""
                    val config = wifiManager.configuredNetworks?.find { it.SSID == quotedSsid }
                    targetNetworkId = config?.networkId ?: -1
                }

                if (targetNetworkId != -1) {
                    wifiManager.disconnect()
                    wifiManager.enableNetwork(targetNetworkId, true)
                    wifiManager.reconnect()
                    Timber.i("WiFi Monitor: Connecting to target network (id=$targetNetworkId, SSID=${Constants.WIFI_SSID})")
                } else {
                    // Network not configured yet, add it first
                    addNetworkLegacy()
                    if (targetNetworkId != -1) {
                        wifiManager.enableNetwork(targetNetworkId, true)
                        wifiManager.reconnect()
                        Timber.i("WiFi Monitor: Added and connecting to ${Constants.WIFI_SSID}")
                    }
                }
            } else {
                // Android 10+: Suggestions are passive — trigger a reconnect
                // and the system should prefer our suggested network
                wifiManager.reconnect()
                Timber.i("WiFi Monitor: Reconnect triggered (suggestion-based for ${Constants.WIFI_SSID})")
            }
        } catch (e: Exception) {
            Timber.e(e, "WiFi Monitor: Failed to connect to target network")
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
     * Attempt to reconnect to WiFi — specifically to the target network.
     */
    @Suppress("DEPRECATION")
    private fun attemptReconnection() {
        try {
            Timber.i("WiFi Monitor: Attempting reconnection to ${Constants.WIFI_SSID}...")
            connectToTargetNetwork()
        } catch (e: Exception) {
            Timber.e(e, "WiFi Monitor: Reconnection attempt failed")
        }
    }

    /**
     * Nuclear option: toggle WiFi off and back on.
     * After re-enabling, connect to the target network.
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

            // Wait briefly, then turn back on and connect to target
            handler.postDelayed({
                try {
                    wifiManager.isWifiEnabled = true
                    Timber.i("WiFi Monitor: WiFi re-enabled, connecting to ${Constants.WIFI_SSID}...")
                    // Give WiFi a moment to scan, then connect
                    handler.postDelayed({ connectToTargetNetwork() }, 3000)
                } catch (e: Exception) {
                    Timber.e(e, "WiFi Monitor: Failed to re-enable WiFi")
                }
            }, 2000)  // 2 second delay between off and on

        } catch (e: Exception) {
            Timber.e(e, "WiFi Monitor: WiFi toggle failed")
        }
    }
}
