package com.example.syncstagereceiver.playback

import com.example.syncstagereceiver.player.PlayerManager
import com.example.syncstagereceiver.services.FeedbackSender
import com.example.syncstagereceiver.util.FileHandler
import com.google.gson.Gson
import com.google.gson.JsonObject
import timber.log.Timber

/**
 * ==================== PLAYBACK HANDLER ====================
 * 
 * Handles playback commands from the Controller.
 * 
 * UPDATES:
 * - PAUSE now triggers black screen overlay
 * - Added file validation before playback
 * - Improved error handling and logging
 */
class PlaybackHandler(
    private val playerManager: PlayerManager,
    private val fileHandler: FileHandler,
    private val gson: Gson
) {
    var feedbackSender: FeedbackSender? = null

    fun handleCommand(jsonCommand: String) {
        try {
            val command = gson.fromJson(jsonCommand, JsonObject::class.java)
            val action = command.get("action")?.asString

            when (action) {
                "PLAY" -> handlePlayCommand(command)
                "PAUSE" -> handlePauseCommand()
                "PLAY_TIMELINE" -> handlePlayTimelineCommand(command)
                "PAUSE_TIMELINE" -> handlePauseTimelineCommand(command)
                "STOP" -> handleStopCommand()
                "REQUEST_STATUS" -> handleRequestStatus()
                "HEARTBEAT" -> {
                    // Keep-alive - no action needed
                    Timber.v("Heartbeat received")
                }
                else -> {
                    Timber.w("Unknown playback action: $action")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error handling playback command")
            feedbackSender?.sendPlaybackStatus("ERROR", "PARSE_ERROR", 0)
        }
    }
    
    /**
     * Handle PLAY command - start or sync video playback.
     * Ignored when timeline sync is active (PLAY_TIMELINE takes priority).
     */
    private fun handlePlayCommand(command: JsonObject) {
        if (playerManager.isTimelineActive()) {
            Timber.d("Ignoring legacy PLAY — timeline sync is active")
            return
        }
        // 1. Extract Playlist
        val playlistArray = command.getAsJsonArray("playlist")
        val playlist = if (playlistArray != null) {
            playlistArray.map { it.asString }
        } else {
            val singleFile = command.get("filename")?.asString
            if (singleFile != null) listOf(singleFile) else emptyList()
        }

        // 2. Extract Timeline Data
        val index = command.get("index")?.asInt ?: 0
        val position = command.get("position")?.asLong ?: 0L
        val timestamp = command.get("startTime")?.asLong 
            ?: command.get("timestamp")?.asLong 
            ?: 0L

        if (playlist.isNotEmpty()) {
            Timber.i("PLAY command: ${playlist.size} files, index=$index, pos=$position")
            
            // Pass the full playlist to PlayerManager
            // PlayerManager will validate files and handle missing ones gracefully
            playerManager.playPlaylist(playlist, index, position, timestamp)
        } else {
            Timber.w("Invalid PLAY command: Playlist empty.")
            feedbackSender?.sendPlaybackStatus("ERROR", "EMPTY_PLAYLIST", 0)
        }
    }
    
    /**
     * Handle PAUSE command - pause playback and show black screen
     */
    private fun handlePauseCommand() {
        if (playerManager.isTimelineActive()) {
            Timber.d("Ignoring legacy PAUSE — timeline sync is active")
            return
        }
        Timber.i("PAUSE command received - showing black screen")
        playerManager.pause()  // This now shows black overlay
    }
    
    /**
     * Handle STOP command - stop playback completely
     */
    private fun handleStopCommand() {
        Timber.i("STOP command received")
        playerManager.stop()
    }
    
    /**
     * Handle REQUEST_STATUS command - respond with full STATUS_REPORT
     * including device identification and playlist context for dashboard.
     */
    private fun handleRequestStatus() {
        Timber.d("REQUEST_STATUS received, sending full STATUS_REPORT")
        playerManager.sendCurrentStatusReport()
    }

    /**
     * Handle PLAY_TIMELINE command - start timeline-based synchronized playback.
     * Each receiver independently calculates its position and self-corrects.
     */
    private fun handlePlayTimelineCommand(command: JsonObject) {
        val playlistArray = command.getAsJsonArray("playlist")
        val playlist = playlistArray?.map { it.asString } ?: emptyList()

        val durationsArray = command.getAsJsonArray("durations")
        val durations = durationsArray?.map { it.asLong } ?: emptyList()

        val timelineStart = command.get("timelineStart")?.asLong ?: 0L
        val totalDuration = command.get("totalDuration")?.asLong ?: 0L

        if (playlist.isNotEmpty() && durations.isNotEmpty() && timelineStart > 0L && totalDuration > 0L) {
            Timber.i("PLAY_TIMELINE command: ${playlist.size} files, timelineStart=$timelineStart, totalDuration=$totalDuration")
            playerManager.playTimeline(playlist, durations, timelineStart, totalDuration)
        } else {
            Timber.w("Invalid PLAY_TIMELINE command: playlist=${playlist.size}, durations=${durations.size}, timelineStart=$timelineStart, totalDuration=$totalDuration")
            feedbackSender?.sendPlaybackStatus("ERROR", "INVALID_TIMELINE", 0)
        }
    }

    /**
     * Handle PAUSE_TIMELINE command - pause timeline playback and show black screen.
     */
    private fun handlePauseTimelineCommand(command: JsonObject) {
        val pausePosition = command.get("pausePosition")?.asLong ?: 0L
        Timber.i("PAUSE_TIMELINE command received - pausePosition=$pausePosition")
        playerManager.pauseTimeline(pausePosition)
    }
}
