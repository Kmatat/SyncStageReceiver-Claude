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
import com.example.syncstagereceiver.util.LocalPlaybackLogger
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
    private var exoPlayer = ExoPlayer.Builder(context).build()
    private val handler = Handler(Looper.getMainLooper())
    private var currentPlaylistSignature: String = ""
    private var currentPlaylist: List<String> = emptyList()

    var feedbackSender: FeedbackSender? = null
    var localLogger: LocalPlaybackLogger? = null
    
    // Device identification for playback reports
    private val deviceId: String by lazy {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    }
    private val sharedPreferences: SharedPreferences by lazy {
        context.getSharedPreferences("SyncStageReceiverPrefs", Context.MODE_PRIVATE)
    }
    private val deviceName: String
        get() = sharedPreferences.getString("device_name", deviceId) ?: deviceId
    
    // Watchdog for detecting stuck playback (aggressive recovery)
    private var lastWatchdogPosition: Long = 0L
    private var watchdogStuckCount: Int = 0
    private val WATCHDOG_INTERVAL_MS = 2000L          // Check every 2 seconds (was 5s)
    private val WATCHDOG_MAX_STUCK_COUNT = 2           // Trigger after 2 stuck readings = 4s (was 3 = 15s)
    private var consecutiveRecoveryFailures: Int = 0   // Track failed recoveries for nuclear option

    // Transition watchdog for short clips (10-15 second videos)
    private var transitionExpectedAt: Long = 0L
    private val TRANSITION_TIMEOUT_MS = 3000L          // Force next clip if transition takes > 3s

    // Periodic playback reporting for dashboard freshness
    private val PLAYBACK_REPORT_INTERVAL_MS = 10_000L  // Report every 10 seconds while playing

    // Player listener extracted as a field so it can be re-attached on player rebuild
    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    showIdleImage(false)
                    hideBlackOverlay()
                    transitionExpectedAt = 0  // Transition completed successfully
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
            Timber.i("Playback state changed: $status ($filename)")

            // Send standard playback status for every state change
            feedbackSender?.sendPlaybackStatus(status, filename, exoPlayer.currentPosition)

            // Send detailed playback report for Firebase logging on EVERY state change
            sendPlaybackReport(filename, status)

            if (isPlaying) {
                localLogger?.logVideoStart(filename, exoPlayer.currentMediaItemIndex, exoPlayer.mediaItemCount)
            }
        }

        // Report when video changes in playlist + trigger transition watchdog
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val prevFilename = exoPlayer.currentMediaItem?.mediaId ?: "unknown"
            if (mediaItem != null) {
                val filename = mediaItem.mediaId ?: "unknown"
                Timber.i("Video transition: $filename (reason: $reason)")
                transitionExpectedAt = 0  // Transition completed
                localLogger?.logVideoTransition(prevFilename, filename, "reason=$reason")
                // Always send report on transition — use playWhenReady instead of isPlaying
                // because isPlaying is false during brief buffering between clips
                if (exoPlayer.playWhenReady) {
                    sendPlaybackReport(filename, "PLAYING")
                }
            }
            // Set transition watchdog for next expected transition
            val duration = exoPlayer.duration
            if (duration > 0) {
                transitionExpectedAt = System.currentTimeMillis() + duration - exoPlayer.currentPosition
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Timber.e(error, "ExoPlayer Error - attempting recovery")

            // Report error
            val filename = exoPlayer.currentMediaItem?.mediaId ?: "unknown"
            sendPlaybackReport(filename, "ERROR")
            localLogger?.logPlaybackError(filename, error.message ?: "unknown")

            // Attempt recovery
            try {
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
            } catch (e: Exception) {
                Timber.e(e, "Recovery failed")
            }
        }
    }

    init {
        playerView.player = exoPlayer
        exoPlayer.volume = 0f // Mute
        exoPlayer.repeatMode = Player.REPEAT_MODE_ALL
        exoPlayer.playWhenReady = true
        showIdleImage(true)
        hideBlackOverlay()

        exoPlayer.addListener(playerListener)

        // Start watchdog timer
        startWatchdog()

        // Start periodic playback reporting for dashboard
        startPeriodicReporting()
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
    
    /**
     * Send a full STATUS_REPORT in response to REQUEST_STATUS.
     * Includes device identification and playlist context for the dashboard.
     */
    fun sendCurrentStatusReport() {
        val filename = exoPlayer.currentMediaItem?.mediaId ?: ""
        val status = when {
            exoPlayer.isPlaying -> "PLAYING"
            blackOverlay?.visibility == View.VISIBLE -> "PAUSED"
            else -> "IDLE"
        }
        feedbackSender?.sendStatusReport(
            deviceId = deviceId,
            deviceName = deviceName,
            status = status,
            videoFilename = filename,
            playlistIndex = exoPlayer.currentMediaItemIndex,
            playlistTotal = exoPlayer.mediaItemCount,
            positionMs = exoPlayer.currentPosition
        )
    }

    // ==================== PERIODIC PLAYBACK REPORTING ====================

    private val playbackReportRunnable = object : Runnable {
        override fun run() {
            try {
                if (exoPlayer.isPlaying) {
                    val filename = exoPlayer.currentMediaItem?.mediaId ?: "unknown"
                    sendPlaybackReport(filename, "PLAYING")
                }
            } catch (e: Exception) {
                Timber.e(e, "Periodic playback report error")
            }
            handler.postDelayed(this, PLAYBACK_REPORT_INTERVAL_MS)
        }
    }

    private fun startPeriodicReporting() {
        handler.postDelayed(playbackReportRunnable, PLAYBACK_REPORT_INTERVAL_MS)
    }

    // ==================== WATCHDOG TIMER ====================
    
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
                        Timber.w("Watchdog: Position stuck at $currentPos (count: $watchdogStuckCount/$WATCHDOG_MAX_STUCK_COUNT)")

                        if (watchdogStuckCount >= WATCHDOG_MAX_STUCK_COUNT) {
                            Timber.e("Watchdog: Player stuck for ${watchdogStuckCount * WATCHDOG_INTERVAL_MS}ms! Forcing recovery...")
                            val filename = exoPlayer.currentMediaItem?.mediaId ?: "unknown"
                            localLogger?.logFreezeDetected(filename, currentPos, watchdogStuckCount)
                            forceRecovery()
                            watchdogStuckCount = 0
                        }
                    } else {
                        watchdogStuckCount = 0
                        consecutiveRecoveryFailures = 0  // Reset on healthy playback
                    }

                    lastWatchdogPosition = currentPos
                }

                // Transition watchdog: if we're waiting for a transition and it's taking too long
                checkTransitionTimeout()

            } catch (e: Exception) {
                Timber.e(e, "Watchdog error")
            }

            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    /**
     * Check if a video transition is taking too long and force the next clip.
     */
    private fun checkTransitionTimeout() {
        if (transitionExpectedAt > 0 && System.currentTimeMillis() > transitionExpectedAt + TRANSITION_TIMEOUT_MS) {
            Timber.e("Transition watchdog: Transition timed out after ${TRANSITION_TIMEOUT_MS}ms, forcing next clip")
            transitionExpectedAt = 0
            handler.post {
                try {
                    val nextIndex = (exoPlayer.currentMediaItemIndex + 1) % exoPlayer.mediaItemCount
                    exoPlayer.seekTo(nextIndex, 0)
                    exoPlayer.playWhenReady = true
                    Timber.i("Transition watchdog: Forced advance to clip index $nextIndex")
                } catch (e: Exception) {
                    Timber.e(e, "Transition watchdog: Failed to force next clip")
                }
            }
        }
    }

    /**
     * Aggressive recovery: full re-prepare instead of weak seek.
     * If re-prepare fails twice in a row, nuclear option: release and rebuild player.
     */
    private fun forceRecovery() {
        handler.post {
            try {
                if (consecutiveRecoveryFailures >= 2) {
                    // Nuclear option: full player rebuild
                    Timber.e("Recovery: 2 consecutive failures - rebuilding player from scratch")
                    localLogger?.logRecoveryAction("NUCLEAR_REBUILD", true, "2 consecutive re-prepare failures")
                    rebuildPlayer()
                    consecutiveRecoveryFailures = 0
                    return@post
                }

                val currentIndex = exoPlayer.currentMediaItemIndex
                val mediaItemCount = exoPlayer.mediaItemCount

                // Full re-prepare: stop, re-prepare, and resume from same position
                Timber.i("Recovery: Full re-prepare (attempt ${consecutiveRecoveryFailures + 1})")
                localLogger?.logRecoveryAction("RE_PREPARE", true, "attempt ${consecutiveRecoveryFailures + 1}")
                exoPlayer.stop()
                exoPlayer.prepare()
                if (mediaItemCount > 0) {
                    exoPlayer.seekTo(currentIndex, 0)  // Restart current clip from beginning
                }
                exoPlayer.playWhenReady = true

                consecutiveRecoveryFailures++

                // Schedule a check to see if recovery worked
                handler.postDelayed({
                    if (!exoPlayer.isPlaying) {
                        Timber.w("Recovery: Player still not playing after re-prepare")
                    } else {
                        Timber.i("Recovery: Re-prepare succeeded, playback resumed")
                        consecutiveRecoveryFailures = 0
                    }
                }, 2000)

            } catch (e: Exception) {
                Timber.e(e, "Force recovery failed")
                consecutiveRecoveryFailures++
            }
        }
    }

    /**
     * Nuclear recovery option: fully release and rebuild the ExoPlayer instance.
     * Used when re-prepare fails multiple times.
     */
    private fun rebuildPlayer() {
        try {
            Timber.w("Nuclear recovery: Releasing and rebuilding ExoPlayer")
            val savedPlaylist = currentPlaylist.toList()
            val savedSignature = currentPlaylistSignature

            // Release old player
            handler.removeCallbacks(watchdogRunnable)
            handler.removeCallbacks(playbackReportRunnable)
            exoPlayer.release()

            // Build new player
            exoPlayer = ExoPlayer.Builder(context).build()
            playerView.player = exoPlayer
            exoPlayer.volume = 0f
            exoPlayer.repeatMode = Player.REPEAT_MODE_ALL
            exoPlayer.playWhenReady = true

            // Re-add listener
            exoPlayer.addListener(playerListener)

            // Reload playlist if we had one
            if (savedPlaylist.isNotEmpty()) {
                currentPlaylistSignature = ""  // Force reload
                val mediaItems = savedPlaylist.mapNotNull { filename ->
                    val file = File(context.filesDir, "videos/$filename")
                    if (file.exists() && file.length() > 1000) {
                        MediaItem.Builder()
                            .setUri(file.absolutePath)
                            .setMediaId(filename)
                            .build()
                    } else null
                }
                if (mediaItems.isNotEmpty()) {
                    exoPlayer.setMediaItems(mediaItems)
                    exoPlayer.prepare()
                    currentPlaylistSignature = savedSignature
                    Timber.i("Nuclear recovery: Rebuilt player with ${mediaItems.size} items")
                }
            }

            // Restart watchdog and periodic reporting
            startWatchdog()
            startPeriodicReporting()
        } catch (e: Exception) {
            Timber.e(e, "Nuclear recovery failed completely")
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
                // Signature includes file size + lastModified so that replacing
                // files on disk (same name, new content after SYNC_PLAYLIST)
                // forces ExoPlayer to reload instead of playing stale cached data.
                val newSignature = filenames.joinToString(",") { filename ->
                    val f = File(context.filesDir, "videos/$filename")
                    "$filename:${f.length()}:${f.lastModified()}"
                }
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

    /**
     * Invalidate the cached playlist signature so the next PLAY command
     * forces ExoPlayer to reload media items from disk.
     * Call this after SYNC_PLAYLIST completes to ensure updated files are picked up.
     */
    fun invalidatePlaylistCache() {
        handler.post {
            Timber.i("Playlist cache invalidated (sync completed)")
            currentPlaylistSignature = ""
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
        handler.removeCallbacks(playbackReportRunnable)
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
