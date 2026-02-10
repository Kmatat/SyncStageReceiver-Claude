package com.example.syncstagereceiver.util

import android.content.Context
import timber.log.Timber
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * ==================== LOCAL PLAYBACK LOGGER ====================
 *
 * Writes playback events to a local file on the device for diagnostics.
 * Independent of the PlaybackReportFeedback sent to the Controller.
 *
 * Features:
 * - Logs video starts, transitions, errors, freezes, WiFi events, connections
 * - Stores as simple JSON-lines in internal storage
 * - Auto-deletes logs older than 60 days
 * - Caps total log storage at ~5MB
 * - Runs cleanup check once per day
 */
class LocalPlaybackLogger(private val context: Context) {

    private val logDir: File by lazy {
        File(context.filesDir, "playback_logs").also { it.mkdirs() }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)

    private val MAX_LOG_SIZE_BYTES = 5 * 1024 * 1024L  // 5MB total cap
    private val MAX_LOG_AGE_DAYS = 60
    private val CLEANUP_INTERVAL_MS = 24 * 60 * 60 * 1000L  // Once per day

    private var lastCleanupTime: Long = 0L

    /**
     * Initialize the logger and run initial cleanup.
     */
    fun init() {
        executor.execute {
            try {
                runCleanup()
                Timber.i("LocalPlaybackLogger initialized. Log dir: ${logDir.absolutePath}")
            } catch (e: Exception) {
                Timber.e(e, "LocalPlaybackLogger init failed")
            }
        }
    }

    // ==================== PUBLIC LOGGING METHODS ====================

    fun logVideoStart(filename: String, playlistIndex: Int, playlistTotal: Int) {
        writeLog("VIDEO_START", mapOf(
            "filename" to filename,
            "playlistIndex" to playlistIndex.toString(),
            "playlistTotal" to playlistTotal.toString()
        ))
    }

    fun logVideoTransition(fromFilename: String, toFilename: String, reason: String) {
        writeLog("VIDEO_TRANSITION", mapOf(
            "from" to fromFilename,
            "to" to toFilename,
            "reason" to reason
        ))
    }

    fun logPlaybackError(filename: String, error: String) {
        writeLog("PLAYBACK_ERROR", mapOf(
            "filename" to filename,
            "error" to error
        ))
    }

    fun logFreezeDetected(filename: String, positionMs: Long, stuckCount: Int) {
        writeLog("FREEZE_DETECTED", mapOf(
            "filename" to filename,
            "positionMs" to positionMs.toString(),
            "stuckCount" to stuckCount.toString()
        ))
    }

    fun logRecoveryAction(action: String, success: Boolean, details: String = "") {
        writeLog("RECOVERY_ACTION", mapOf(
            "action" to action,
            "success" to success.toString(),
            "details" to details
        ))
    }

    fun logWifiEvent(event: String, details: String = "") {
        writeLog("WIFI_EVENT", mapOf(
            "event" to event,
            "details" to details
        ))
    }

    fun logConnectionEvent(event: String, remoteAddress: String = "") {
        writeLog("CONNECTION_EVENT", mapOf(
            "event" to event,
            "remoteAddress" to remoteAddress
        ))
    }

    fun logHeartbeat(source: String) {
        writeLog("HEARTBEAT", mapOf(
            "source" to source
        ))
    }

    // ==================== INTERNAL ====================

    private fun writeLog(event: String, data: Map<String, String>) {
        executor.execute {
            try {
                maybeRunCleanup()

                val today = dateFormat.format(Date())
                val logFile = File(logDir, "log_$today.jsonl")
                val timestamp = timestampFormat.format(Date())

                // Build simple JSON line
                val dataEntries = data.entries.joinToString(", ") { (k, v) ->
                    "\"$k\":\"${v.replace("\"", "\\\"")}\""
                }
                val line = "{\"ts\":\"$timestamp\",\"event\":\"$event\",$dataEntries}\n"

                FileWriter(logFile, true).use { it.write(line) }
            } catch (e: Exception) {
                Timber.e(e, "LocalPlaybackLogger: Failed to write log")
            }
        }
    }

    private fun maybeRunCleanup() {
        val now = System.currentTimeMillis()
        if (now - lastCleanupTime > CLEANUP_INTERVAL_MS) {
            runCleanup()
            lastCleanupTime = now
        }
    }

    private fun runCleanup() {
        try {
            val files = logDir.listFiles() ?: return

            // Delete files older than 60 days
            val cutoff = System.currentTimeMillis() - (MAX_LOG_AGE_DAYS * 24 * 60 * 60 * 1000L)
            var totalSize = 0L

            val sortedFiles = files.sortedByDescending { it.lastModified() }
            for (file in sortedFiles) {
                if (file.lastModified() < cutoff) {
                    Timber.d("LocalPlaybackLogger: Deleting old log: ${file.name}")
                    file.delete()
                    continue
                }
                totalSize += file.length()
            }

            // If total size exceeds cap, delete oldest files first
            if (totalSize > MAX_LOG_SIZE_BYTES) {
                val filesToDelete = sortedFiles.reversed()  // Oldest first
                for (file in filesToDelete) {
                    if (totalSize <= MAX_LOG_SIZE_BYTES) break
                    totalSize -= file.length()
                    Timber.d("LocalPlaybackLogger: Deleting for size cap: ${file.name}")
                    file.delete()
                }
            }

            Timber.d("LocalPlaybackLogger: Cleanup done. ${logDir.listFiles()?.size ?: 0} files, ${totalSize / 1024}KB")
        } catch (e: Exception) {
            Timber.e(e, "LocalPlaybackLogger: Cleanup failed")
        }
    }

    fun shutdown() {
        executor.shutdown()
    }
}
