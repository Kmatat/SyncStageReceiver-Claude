package com.example.syncstagereceiver.util

import android.content.Context
import com.instacart.library.truetime.TrueTime
import kotlinx.coroutines.*
import timber.log.Timber
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class TimeManager(context: Context) {

    private val isInitialized = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val appContext = context.applicationContext

    // --- NEW: Persistence for Time Offset ---
    private val prefs = appContext.getSharedPreferences("SyncStageReceiverPrefs", Context.MODE_PRIVATE)

    // Initial offset loaded from disk
    private val localOffset = AtomicLong(prefs.getLong("saved_time_offset", 0L))

    init {
        Timber.i("TimeManager initialized. Loaded saved offset: ${localOffset.get()}")
        startSyncLoop()
    }

    private fun startSyncLoop() {
        scope.launch {
            while (!isInitialized.get()) {
                try {
                    if (!TrueTime.isInitialized()) {
                        Timber.d("TimeManager: Attempting TrueTime init...")
                        TrueTime.build()
                            .withSharedPreferencesCache(appContext)
                            .withConnectionTimeout(5000)
                            .withLoggingEnabled(true)
                            .initialize()
                    }
                    isInitialized.set(true)
                    Timber.i("TimeManager: TrueTime initialized successfully.")
                } catch (e: Exception) {
                    Timber.w("TimeManager: Internet time failed. Running on Local Offset mode.")
                    delay(60_000)
                }
            }
        }
    }

    fun updateServerTimeOffset(serverTime: Long) {
        val systemTime = System.currentTimeMillis()
        val newOffset = serverTime - systemTime
        localOffset.set(newOffset)

        // --- NEW: Save to Disk ---
        prefs.edit().putLong("saved_time_offset", newOffset).apply()
        Timber.i("TimeManager: Updated & Saved local offset to $newOffset ms")
    }

    fun getSynchronizedTime(): Long {
        return if (TrueTime.isInitialized()) {
            try {
                TrueTime.now().time
            } catch (e: Exception) {
                System.currentTimeMillis() + localOffset.get()
            }
        } else {
            System.currentTimeMillis() + localOffset.get()
        }
    }

    fun getSynchronizedDate(): Date {
        return Date(getSynchronizedTime())
    }
}