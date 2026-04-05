package com.example.syncstagereceiver.network

import com.google.gson.annotations.SerializedName

/**
 * ==================== FEEDBACK MODELS ====================
 *
 * Data classes for all feedback messages sent from Receiver to Controller.
 *
 * UPDATES:
 * - Added PlaybackReportFeedback for detailed playback logging to Firebase
 * - Added disk free space reporting
 * - Fixed: Removed duplicate action field overrides that caused Gson serialization issues
 */

/**
 * Base class for all feedback messages sent from Receiver to Controller.
 * The action field is set via the constructor and should NOT be overridden
 * in subclasses to avoid duplicate @SerializedName fields in the class hierarchy.
 */
sealed class Feedback(
    @SerializedName("action") val action: String = "STATUS_REPORT"
)

/**
 * Sync status feedback - reports playlist sync progress
 * (e.g., "SYNCING", "COMPLETED", "ERROR")
 *
 * UPDATED: Added diskFreeSpace to monitor device storage health
 */
data class SyncStatusFeedback(
    @SerializedName("type") val type: String = "SYNC_STATUS",
    @SerializedName("status") val status: String,
    @SerializedName("syncedPlaylistId") val syncedPlaylistId: String?,
    @SerializedName("fileCount") val fileCount: Int,
    @SerializedName("diskFreeSpace") val diskFreeSpace: Long = 0L // Bytes
) : Feedback()

/**
 * Playback status feedback - basic playback state reporting
 * (e.g., "PLAYING", "PAUSED", "COMPLETED")
 */
data class PlaybackStatusFeedback(
    @SerializedName("type") val type: String = "PLAYBACK_STATUS",
    @SerializedName("status") val status: String,
    @SerializedName("filename") val filename: String,
    @SerializedName("position") val position: Long
) : Feedback()

/**
 * ==================== PLAYBACK REPORT FEEDBACK ====================
 *
 * Detailed playback report sent to Controller for Firebase logging.
 * This provides visibility into what each screen is actually playing.
 *
 * The Controller receives this and forwards to Firebase's playback_logs collection.
 */
data class PlaybackReportFeedback(
    @SerializedName("deviceId") val deviceId: String,
    @SerializedName("deviceName") val deviceName: String,
    @SerializedName("videoFilename") val videoFilename: String,
    @SerializedName("playlistIndex") val playlistIndex: Int,
    @SerializedName("playlistTotal") val playlistTotal: Int,
    @SerializedName("positionMs") val positionMs: Long,
    @SerializedName("status") val status: String,  // "PLAYING", "PAUSED", "ERROR"
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
) : Feedback("PLAYBACK_REPORT")

/**
 * Status report feedback - full device status in response to REQUEST_STATUS.
 * Includes device identification and playlist context so the Controller
 * can match this report to a specific screen on the dashboard.
 *
 * UPDATED: Added diskFreeSpace to monitor device storage health
 */
data class StatusReportFeedback(
    @SerializedName("deviceId") val deviceId: String,
    @SerializedName("deviceName") val deviceName: String,
    @SerializedName("status") val status: String,
    @SerializedName("videoFilename") val videoFilename: String,
    @SerializedName("playlistIndex") val playlistIndex: Int,
    @SerializedName("playlistTotal") val playlistTotal: Int,
    @SerializedName("positionMs") val positionMs: Long,
    @SerializedName("diskFreeSpace") val diskFreeSpace: Long = 0L,
    @SerializedName("versionCode") val versionCode: Int = 0,
    @SerializedName("versionName") val versionName: String = "",
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
) : Feedback("STATUS_REPORT")
