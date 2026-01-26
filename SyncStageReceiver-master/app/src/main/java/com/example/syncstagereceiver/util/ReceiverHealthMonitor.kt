package com.example.syncstagereceiver.util

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import timber.log.Timber
import java.io.File

/**
 * ==================== RECEIVER HEALTH MONITOR ====================
 * 
 * Monitors the health of the Receiver device and triggers
 * auto-recovery actions when issues are detected.
 * 
 * Features:
 * - Memory monitoring and cleanup
 * - Disk space monitoring
 * - Playback health monitoring
 * - Auto-recovery for common issues
 */
class ReceiverHealthMonitor(
    private val context: Context
) {
    private val handler = Handler(Looper.getMainLooper())
    
    @Volatile
    private var isMonitoring = false
    
    // Thresholds
    private val LOW_MEMORY_THRESHOLD_MB = 50L      // 50MB
    private val LOW_DISK_THRESHOLD_MB = 100L      // 100MB
    private val HEALTH_CHECK_INTERVAL_MS = 30_000L // 30 seconds
    
    // Listeners
    var onLowMemory: (() -> Unit)? = null
    var onLowDiskSpace: (() -> Unit)? = null
    var onCriticalState: (() -> Unit)? = null

    /**
     * Start health monitoring
     */
    fun start() {
        if (isMonitoring) return
        
        isMonitoring = true
        Timber.i("ReceiverHealthMonitor started")
        handler.postDelayed(healthCheckRunnable, HEALTH_CHECK_INTERVAL_MS)
    }
    
    /**
     * Stop health monitoring
     */
    fun stop() {
        isMonitoring = false
        handler.removeCallbacks(healthCheckRunnable)
        Timber.i("ReceiverHealthMonitor stopped")
    }
    
    private val healthCheckRunnable = object : Runnable {
        override fun run() {
            if (!isMonitoring) return
            
            try {
                performHealthCheck()
            } catch (e: Exception) {
                Timber.e(e, "Error during health check")
            }
            
            handler.postDelayed(this, HEALTH_CHECK_INTERVAL_MS)
        }
    }
    
    /**
     * Perform comprehensive health check
     */
    private fun performHealthCheck() {
        val memoryStatus = checkMemoryStatus()
        val diskStatus = checkDiskStatus()
        
        // Log status
        Timber.v("Health: Memory=${memoryStatus.availableMb}MB, Disk=${diskStatus.availableMb}MB")
        
        // Handle low memory
        if (memoryStatus.isLow) {
            Timber.w("Low memory detected: ${memoryStatus.availableMb}MB available")
            triggerMemoryCleanup()
            onLowMemory?.invoke()
        }
        
        // Handle low disk space
        if (diskStatus.isLow) {
            Timber.w("Low disk space detected: ${diskStatus.availableMb}MB available")
            onLowDiskSpace?.invoke()
        }
        
        // Handle critical state (both low)
        if (memoryStatus.isCritical || diskStatus.isCritical) {
            Timber.e("CRITICAL: System resources dangerously low!")
            onCriticalState?.invoke()
        }
    }
    
    /**
     * Check memory status
     */
    private fun checkMemoryStatus(): MemoryStatus {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        
        val availableMb = memInfo.availMem / (1024 * 1024)
        val totalMb = memInfo.totalMem / (1024 * 1024)
        val usedPercent = ((totalMb - availableMb).toFloat() / totalMb * 100).toInt()
        
        return MemoryStatus(
            availableMb = availableMb,
            totalMb = totalMb,
            usedPercent = usedPercent,
            isLow = availableMb < LOW_MEMORY_THRESHOLD_MB,
            isCritical = availableMb < LOW_MEMORY_THRESHOLD_MB / 2
        )
    }
    
    /**
     * Check disk space status
     */
    private fun checkDiskStatus(): DiskStatus {
        val videosDir = File(context.filesDir, "videos")
        if (!videosDir.exists()) videosDir.mkdirs()
        
        val availableMb = videosDir.freeSpace / (1024 * 1024)
        val totalMb = videosDir.totalSpace / (1024 * 1024)
        val usedPercent = if (totalMb > 0) {
            ((totalMb - availableMb).toFloat() / totalMb * 100).toInt()
        } else 0
        
        return DiskStatus(
            availableMb = availableMb,
            totalMb = totalMb,
            usedPercent = usedPercent,
            isLow = availableMb < LOW_DISK_THRESHOLD_MB,
            isCritical = availableMb < LOW_DISK_THRESHOLD_MB / 2
        )
    }
    
    /**
     * Trigger memory cleanup
     */
    private fun triggerMemoryCleanup() {
        Timber.i("Triggering memory cleanup...")
        
        // Request garbage collection
        System.gc()
        
        // Clear any cached bitmaps (if applicable)
        try {
            val runtime = Runtime.getRuntime()
            runtime.gc()
        } catch (e: Exception) {
            Timber.w(e, "Error during GC")
        }
        
        Timber.i("Memory cleanup completed")
    }
    
    /**
     * Clean up old/temporary files to free disk space
     */
    fun cleanupDiskSpace(): Long {
        var freedBytes = 0L
        
        val videosDir = File(context.filesDir, "videos")
        
        // Delete .tmp files (incomplete downloads)
        videosDir.listFiles { file -> file.extension == "tmp" }?.forEach { file ->
            val size = file.length()
            if (file.delete()) {
                freedBytes += size
                Timber.d("Deleted temp file: ${file.name}")
            }
        }
        
        // Delete any corrupted video files (< 1KB)
        videosDir.listFiles { file -> file.extension == "mp4" && file.length() < 1000 }?.forEach { file ->
            val size = file.length()
            if (file.delete()) {
                freedBytes += size
                Timber.d("Deleted corrupted file: ${file.name}")
            }
        }
        
        Timber.i("Disk cleanup freed ${freedBytes / 1024}KB")
        return freedBytes
    }
    
    /**
     * Get current health status
     */
    fun getHealthStatus(): HealthStatus {
        return HealthStatus(
            memory = checkMemoryStatus(),
            disk = checkDiskStatus()
        )
    }
    
    // Data classes
    data class MemoryStatus(
        val availableMb: Long,
        val totalMb: Long,
        val usedPercent: Int,
        val isLow: Boolean,
        val isCritical: Boolean
    )
    
    data class DiskStatus(
        val availableMb: Long,
        val totalMb: Long,
        val usedPercent: Int,
        val isLow: Boolean,
        val isCritical: Boolean
    )
    
    data class HealthStatus(
        val memory: MemoryStatus,
        val disk: DiskStatus
    ) {
        val isHealthy: Boolean
            get() = !memory.isLow && !disk.isLow
        
        val isCritical: Boolean
            get() = memory.isCritical || disk.isCritical
    }
}
