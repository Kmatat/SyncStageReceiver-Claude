package com.example.syncstagereceiver.network

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import timber.log.Timber

class NetworkServiceAdvertiser(
    context: Context,
    private val sharedPreferences: SharedPreferences,
    private val port: Int
) {
    private val nsdManager: NsdManager? = try {
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    } catch (e: Exception) {
        Timber.e(e, "Failed to get NsdManager")
        null
    }
    private val connectivityManager: ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val handler = Handler(Looper.getMainLooper())
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var serviceName: String? = null

    // Auto-retry registration when the network comes back. Covers the first-boot
    // race where the device has no IP yet when registerService() is first called.
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (registrationListener == null) {
                Timber.i("Network available — (re)registering NSD service")
                handler.post { registerService() }
            }
        }
    }
    private var networkCallbackRegistered = false

    init {
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager?.registerNetworkCallback(request, networkCallback)
            networkCallbackRegistered = true
        } catch (e: Exception) {
            Timber.e(e, "Failed to register ConnectivityManager callback")
        }
    }

    fun registerService() {
        if (registrationListener != null || nsdManager == null) return

        // 1. Get Device Name (or Default)
        val storedName = sharedPreferences.getString("device_name", null)
        val finalName = if (storedName.isNullOrEmpty()) {
            val defaultName = "Receiver-${(1000..9999).random()}"
            sharedPreferences.edit().putString("device_name", defaultName).apply()
            defaultName
        } else {
            storedName
        }
        serviceName = finalName

        // 2. Build Service Info
        val serviceInfo = NsdServiceInfo().apply {
            serviceType = "_syncstage._tcp."
            this.serviceName = finalName
            this.port = this@NetworkServiceAdvertiser.port
        }

        // 3. Create Listener
        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                serviceName = NsdServiceInfo.serviceName
                Timber.i("NSD Service registered: $serviceName")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Timber.e("NSD Registration failed: Error $errorCode — will retry on next network-available")
                registrationListener = null
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                Timber.i("NSD Service unregistered.")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Timber.e("NSD Unregistration failed: Error $errorCode")
            }
        }

        // 4. Register
        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Timber.e(e, "Failed to register NSD service — will retry on next network-available")
            registrationListener = null
        }
    }

    fun unregisterService() {
        val listener = registrationListener ?: return
        registrationListener = null
        try {
            nsdManager?.unregisterService(listener)
        } catch (e: Exception) {
            Timber.e(e, "Error unregistering NSD service")
        }
    }

    /**
     * Re-register NSD advertisement. Uses a delayed post instead of Thread.sleep()
     * to avoid blocking the calling thread (which may be the main thread and cause ANR).
     */
    fun updateAdvertisement() {
        unregisterService()
        // Use handler delay instead of Thread.sleep to avoid blocking main thread
        handler.postDelayed({ registerService() }, 150)
    }

    /**
     * Full teardown — unregisters the NSD service and the network callback.
     * After this the advertiser will not auto-retry on network-available.
     */
    fun release() {
        unregisterService()
        if (networkCallbackRegistered) {
            try {
                connectivityManager?.unregisterNetworkCallback(networkCallback)
            } catch (e: Exception) {
                Timber.w(e, "Error unregistering ConnectivityManager callback")
            }
            networkCallbackRegistered = false
        }
    }
}
