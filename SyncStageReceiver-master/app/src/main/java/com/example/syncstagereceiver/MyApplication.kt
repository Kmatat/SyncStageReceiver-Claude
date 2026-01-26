package com.example.syncstagereceiver

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.instacart.library.truetime.TrueTime
import androidx.media3.common.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

class MyApplication : Application() {

    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // UPDATED: Create the notification channel here
        // This guarantees it exists *before* any service is started.
        createNotificationChannel()

        // UPDATED: Initialize TrueTime on app startup
        initTrueTime()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "CommandReceiverChannel", // This ID must match the service
                "Command Receiver Service",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            channel.description = "Notification for the main receiver service"

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
            Timber.i("Notification channel 'CommandReceiverChannel' created in Application.")
        }
    }

    private fun initTrueTime() {
        appScope.launch {
            try {
                if (!TrueTime.isInitialized()) {
                    Timber.d("MyApplication: Initializing TrueTime...")
                    TrueTime.build()
                        .withSharedPreferencesCache(this@MyApplication)
                        .withConnectionTimeout(30_000)
                        .withLoggingEnabled(true)
                        .initialize()
                    Timber.i("MyApplication: TrueTime initialized successfully.")
                }
            } catch (e: Exception) {
                Timber.e(e, "MyApplication: TrueTime init failed.")
                // Note: TimeManager will handle retries if this fails
            }
        }
    }
}