package com.example.syncstagereceiver.network

import android.content.Context
import android.content.SharedPreferences
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import timber.log.Timber

class NetworkServiceAdvertiser(
    context: Context,
    private val sharedPreferences: SharedPreferences,
    private val port: Int
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var serviceName: String? = null

    fun registerService() {
        if (registrationListener != null) return // Already registered

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
            // "SyncStageReceiver" is the type the Controller looks for
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
                Timber.e("NSD Registration failed: Error $errorCode")
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
            Timber.e(e, "Failed to register NSD service")
        }
    }

    fun unregisterService() {
        if (registrationListener != null) {
            try {
                nsdManager.unregisterService(registrationListener)
            } catch (e: Exception) {
                Timber.e(e, "Error unregistering NSD service")
            } finally {
                registrationListener = null
            }
        }
    }

    fun updateAdvertisement() {
        // NSD doesn't support dynamic updates easily. We must unregister and re-register.
        unregisterService()
        // Small delay to ensure cleanup
        Thread.sleep(100)
        registerService()
    }
}