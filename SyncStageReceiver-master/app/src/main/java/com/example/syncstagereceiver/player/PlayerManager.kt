package com.example.syncstagereceiver.player

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.syncstagereceiver.services.FeedbackSender
import com.example.syncstagereceiver.util.TimeManager
import timber.log.Timber
import java.io.File
import kotlin.math.abs

/**
 * ==================== PLAYER MANAGER ====================
 * 
 * Manages video playback on the Receiver (Xiaomi TV Box).
 * 
 * UPDATES:
 * 1. Black screen overlay on PAUSE - shows black instead of frozen frame
 * 2. Playback reporting - sends reports to Controller for Firebase logging
 * 3. Memory cleanup on playlist change - prevents OOM crashes
 * 4. Watchdog timer - detects stuck playback and forces recovery
 */
class PlayerManager(
    private val context: Context,
    private val playerView: PlayerView,
    private val idleImageView: ImageView,
    private val timeManager: TimeManager,
    private val blackOverlay: View? = null  // NEW: Black overlay for pause state
) {
    private var exoPlayer: ExoPlayer = ExoPlayer.Builder(context).build()
    private val handler = Handler(Looper.getMainLooper())
    private var currentPlaylistSignature: String = ""
    private var currentPlaylist: List<String> = emptyList()

    var feedbackSender: FeedbackSender? = null
    
    // Device identification for playback reports
    private val deviceId: String by lazy {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    }
    private val sharedPreferences: SharedPreferences by lazy {
        context.getSharedPreferences("SyncStageReceiverPrefs", Context.MODE_PRIVATE)
    }
    private val deviceName: String
        get() = sharedPreferences.getString("device_name", deviceId) ?: deviceId
    
    // Watchdog for detecting stuck playback
    private var lastWatchdogPosition: Long = 0L
    private var watchdogStuckCount: Int = 0
    private val WATCHDOG_INTERVAL_MS = 5000L
    private val WATCHDOG_MAX_STUCK_COUNT = 3

    init {
        playerView.player = exoPlayer
        exoPlayer.volume = 0f // Mute
        exoPlayer.repeatMode = Player.REPEAT_MODE_ALL
        exoPlayer.playWhenReady = true
        showIdleImage(true)
        hideBlackOverlay()

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        showIdleImage(false)
                        hideBlackOverlay()
                    }
                    Player.STATE_ENDED -> {
                        // Loop handled by REPEAT_MODE_ALL
                    }
                    Player.STATE_BUFFERING -> {
                        Timber.d("Player buffering...")
                    }
                    Player.STATE_IDLE -> {
                        // Player is idle
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val currentMedia = exoPlayer.currentMediaItem
                val filename = currentMedia?.mediaId ?: "unknown"
                val status = if (isPlaying) "PLAYING" else "PAUSED"
                Timber.v("Playback: $status ($filename)")
                
                // Send standard playback status
                feedbackSender?.sendPlaybackStatus(status, filename, exoPlayer.currentPosition)
                
                // NEW: Send detailed playback report for Firebase logging
                if (isPlaying) {
                    sendPlaybackReport(filename, "PLAYING")
                }
            }
            
            // NEW: Report when video changes in playlist
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (exoPlayer.isPlaying && mediaItem != null) {
                    val filename = mediaItem.mediaId ?: "unknown"
                    Timber.i("Video transition: $filename (reason: $reason)")
                    sendPlaybackReport(filename, "PLAYING")
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Timber.e(error, "ExoPlayer Error - attempting recovery")
                
                // Report error
                val filename = exoPlayer.currentMediaItem?.mediaId ?: "unknown"
                sendPlaybackReport(filename, "ERROR")
                
                // Attempt recovery
                try {
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                } catch (e: Exception) {
                    Timber.e(e, "Recovery failed")
                }
            }
        })
        
        // Start watchdog timer
        startWatchdog()
    }
    
    // ==================== PLAYBACK REPORTING (NEW) ====================
    
    /**
     * Send a detailed playback report to the Controller for Firebase logging.
     * This allows tracking what each screen is actually playing.
     */
    private fun sendPlaybackReport(filename: String, status: String) {
        feedbackSender?.sendPlaybackReport(
            deviceId = deviceId,
            deviceName = deviceName,
            videoFilename = filename,
            playlistIndex = exoPlayer.currentMediaItemIndex,
            playlistTotal = exoPlayer.mediaItemCount,
            positionMs = exoPlayer.currentPosition,
            status = status
        )
    }
    
    // ==================== WATCHDOG TIMER (NEW) ====================
    
    /**
     * Watchdog timer to detect and recover from stuck playback.
     * If the playback position doesn't change for too long while playing,
     * it forces a recovery action.
     */
    private fun startWatchdog() {
        handler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
    }
    
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            try {
                if (exoPlayer.isPlaying) {
                    val currentPos = exoPlayer.currentPosition
                    
                    if (currentPos == lastWatchdogPosition) {
                        watchdogStuckCount++
                        Timber.w("Watchdog: Position stuck at $currentPos (count: $watchdogStuckCount)")
                        
                        if (watchdogStuckCount >= WATCHDOG_MAX_STUCK_COUNT) {
                            Timber.e("Watchdog: Player stuck for too long! Forcing recovery...")
                            forceRecovery()
                            watchdogStuckCount = 0
                        }
                    } else {
                        watchdogStuckCount = 0
                    }
                    
                    lastWatchdogPosition = currentPos
                }
            } catch (e: Exception) {
                Timber.e(e, "Watchdog error")
            }
            
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }
    
    private fun forceRecovery() {
        handler.post {
            try {
                val currentPos = exoPlayer.currentPosition
                val currentIndex = exoPlayer.currentMediaItemIndex
                
                // Try seeking forward slightly
                exoPlayer.seekTo(currentIndex, currentPos + 100)
                
                // If still stuck, try re-preparing
                if (!exoPlayer.isPlaying) {
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                }
                
                Timber.i("Recovery attempted: seeked to ${currentPos + 100}")
            } catch (e: Exception) {
                Timber.e(e, "Force recovery failed")
            }
        }
    }

    // ==================== PLAYBACK CONTROL ====================

    fun playPlaylist(
        filenames: List<String>,
        targetIndex: Int,
        targetPositionMs: Long,
        commandTimestampMs: Long
    ) {
        handler.post {
            try {
                // Hide black overlay when playing
                hideBlackOverlay()
                
                if (filenames.isEmpty()) {
                    stop()
                    return@post
                }

                // 1. Calculate Latency
                val now = timeManager.getSynchronizedTime()
                val latency = if (commandTimestampMs > 0 && now > commandTimestampMs) {
                    now - commandTimestampMs
                } else { 0L }

                val adjustedPosition = targetPositionMs + latency
                Timber.d("Sync: Latency=${latency}ms. TargetPos=$targetPositionMs -> AdjPos=$adjustedPosition")

                // 2. Load Playlist (If changed) with memory cleanup
                val newSignature = filenames.joinToString(",")
                if (newSignature != currentPlaylistSignature) {
                    Timber.i("Loading NEW playlist: $filenames")

                    // NEW: Memory cleanup before loading new playlist
                    cleanupBeforePlaylistChange()

                    val mediaItems = filenames.mapNotNull { filename ->
                        val file = File(context.filesDir, "videos/$filename")
                        if (file.exists() && file.length() > 1000) {  // Validate file exists and > 1KB
                            MediaItem.Builder()
                                .setUri(file.absolutePath)
                                .setMediaId(filename)
                                .build()
                        } else {
                            Timber.e("MISSING/INVALID FILE: $filename - Skipping")
                            null
                        }
                    }

                    if (mediaItems.isNotEmpty()) {
                        exoPlayer.setMediaItems(mediaItems)
                        exoPlayer.prepare()
                        currentPlaylistSignature = newSignature
                        currentPlaylist = filenames
                    } else {
                        Timber.e("No valid files found in playlist!")
                        feedbackSender?.sendPlaybackStatus("ERROR", "NO_FILES", 0)
                        return@post
                    }
                }

                // 3. Alignment / Smart Seek
                val currentIndex = exoPlayer.currentMediaItemIndex
                val currentPos = exoPlayer.currentPosition

                val HARD_SEEK_THRESHOLD_MS = 150L
                val SPEED_CORRECTION_THRESHOLD_MS = 50L

                val actualItemCount = exoPlayer.mediaItemCount
                val safeIndex = if (targetIndex < actualItemCount) targetIndex else 0

                val isWrongVideo = currentIndex != safeIndex
                val drift = adjustedPosition - currentPos
                val absDrift = abs(drift)

                if (isWrongVideo || absDrift > HARD_SEEK_THRESHOLD_MS) {
                    Timber.w("Hard Sync: Drift=${drift}ms. Seeking to Index $safeIndex @ $adjustedPosition")
                    exoPlayer.seekTo(safeIndex, adjustedPosition)
                    exoPlayer.playbackParameters = PlaybackParameters(1.0f)
                    showIdleImage(false)
                }
                else if (absDrift > SPEED_CORRECTION_THRESHOLD_MS) {
                    if (drift > 0) {
                        Timber.v("Soft Sync: Behind by ${drift}ms. Speeding up (1.05x).")
                        exoPlayer.playbackParameters = PlaybackParameters(1.05f)
                    } else {
                        Timber.v("Soft Sync: Ahead by ${absDrift}ms. Slowing down (0.95x).")
                        exoPlayer.playbackParameters = PlaybackParameters(0.95f)
                    }
                }
                else {
                    if (exoPlayer.playbackParameters.speed != 1.0f) {
                        exoPlayer.playbackParameters = PlaybackParameters(1.0f)
                    }
                    if (!exoPlayer.isPlaying) exoPlayer.play()
                }

            } catch (e: Exception) {
                Timber.e(e, "Error in playPlaylist")
            }
        }
    }
    
    /**
     * NEW: Clean up memory before loading a new playlist.
     * Prevents OOM crashes on devices with limited RAM.
     */
    private fun cleanupBeforePlaylistChange() {
        try {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            
            // Request garbage collection
            System.gc()
            
            // Small delay to allow cleanup
            Thread.sleep(50)
            
            Timber.d("Memory cleanup completed before playlist change")
        } catch (e: Exception) {
            Timber.w(e, "Error during memory cleanup")
        }
    }

    fun stop() {
        handler.post {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            currentPlaylistSignature = ""
            currentPlaylist = emptyList()
            showIdleImage(true)
            hideBlackOverlay()
        }
    }

    /**
     * UPDATED: Pause now shows black screen overlay instead of frozen frame.
     */
    fun pause() {
        handler.post {
            exoPlayer.pause()
            showBlackOverlay()  // NEW: Show black screen instead of frozen frame
            
            // Report pause status
            val filename = exoPlayer.currentMediaItem?.mediaId ?: ""
            sendPlaybackReport(filename, "PAUSED")
        }
    }
    
    /**
     * Resume playback (hides black overlay)
     */
    fun resume() {
        handler.post {
            hideBlackOverlay()
            exoPlayer.play()
        }
    }

    fun releasePlayer() {
        handler.removeCallbacks(watchdogRunnable)
        handler.post { 
            exoPlayer.release() 
        }
    }

    // ==================== UI HELPERS ====================

    private fun showIdleImage(show: Boolean) {
        playerView.visibility = if (show) View.GONE else View.VISIBLE
        idleImageView.visibility = if (show) View.VISIBLE else View.GONE
    }
    
    /**
     * NEW: Show black overlay (for pause state)
     */
    private fun showBlackOverlay() {
        handler.post {
            blackOverlay?.visibility = View.VISIBLE
            Timber.d("Black overlay shown (PAUSE state)")
        }
    }
    
    /**
     * NEW: Hide black overlay (when playing or stopped)
     */
    private fun hideBlackOverlay() {
        handler.post {
            blackOverlay?.visibility = View.GONE
        }
    }

    fun getCurrentStatus(): Triple<String, String, Long> {
        val filename = exoPlayer.currentMediaItem?.mediaId ?: ""
        val position = exoPlayer.currentPosition
        val status = when {
            exoPlayer.isPlaying -> "PLAYING"
            blackOverlay?.visibility == View.VISIBLE -> "PAUSED"
            else -> "IDLE"
        }
        return Triple(status, filename, position)
    }

    fun tryResumeFromSavedState() {
        Timber.d("tryResumeFromSavedState called. Waiting for Controller command.")
        // For now, we wait for Controller to send a command
        // Future: Read last playlist from SharedPrefs and resume
    }
}
