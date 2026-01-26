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
     * Handle PLAY command - start or sync video playback
     */
    private fun handlePlayCommand(command: JsonObject) {
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
     * Handle REQUEST_STATUS command - report current playback status
     */
    private fun handleRequestStatus() {
        val (status, filename, position) = playerManager.getCurrentStatus()
        Timber.d("Status requested: $status, $filename, $position")
        feedbackSender?.sendPlaybackStatus(status, filename, position)
    }
}
