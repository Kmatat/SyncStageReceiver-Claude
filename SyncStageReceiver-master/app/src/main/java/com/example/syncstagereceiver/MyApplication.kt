package com.example.syncstagereceiver

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import com.example.syncstagereceiver.ui.MainActivity
import com.instacart.library.truetime.TrueTime
import androidx.media3.common.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.system.exitProcess

class MyApplication : Application() {

    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // Install global crash handler to auto-restart the app on fatal crashes.
        // This is critical for Xiaomi/MIUI devices where permission issues can
        // cause unexpected crashes that would leave the device stuck.
        installCrashRecoveryHandler()

        // Create the notification channel here.
        // This guarantees it exists *before* any service is started.
        createNotificationChannel()

        // Initialize TrueTime on app startup
        initTrueTime()
    }

    /**
     * Installs a global uncaught exception handler that:
     * 1. Logs the crash via Timber
     * 2. Schedules an app restart via AlarmManager (2-second delay)
     * 3. Kills the current process
     *
     * This ensures the receiver app recovers from crashes automatically,
     * which is essential for unattended Xiaomi TV boxes.
     */
    private fun installCrashRecoveryHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Timber.e(throwable, "FATAL CRASH on thread '${thread.name}' — scheduling restart")

                // Schedule restart via AlarmManager
                val restartIntent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                val pendingIntent = PendingIntent.getActivity(
                    this, 0, restartIntent,
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                )
                val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                alarmManager?.set(
                    AlarmManager.RTC,
                    System.currentTimeMillis() + 2000, // Restart after 2 seconds
                    pendingIntent
                )
            } catch (restartError: Exception) {
                // If we can't schedule restart, at least try the default handler
                defaultHandler?.uncaughtException(thread, throwable)
            }

            // Kill the process to allow clean restart
            Process.killProcess(Process.myPid())
            exitProcess(1)
        }
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