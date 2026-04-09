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
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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
    
    // Timeline sync state
    private var activeTimelineStart: Long = 0L
    private var activeTimelineDurations: List<Long> = emptyList()
    private var activeTimelineTotalDuration: Long = 0L
    private var activeTimelineFilenames: List<String> = emptyList()
    private var timelinePausePositionMs: Long = 0L

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

    // Missing file retry: periodically check if skipped files have been synced
    private val MISSING_FILE_RETRY_INTERVAL_MS = 15_000L  // Check every 15 seconds
    private var missingFileCount: Int = 0

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

        // Start missing file retry timer
        startMissingFileRetry()
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
            exoPlayer.playbackState == Player.STATE_BUFFERING -> "BUFFERING"
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
            positionMs = exoPlayer.currentPosition,
            versionCode = com.example.syncstagereceiver.BuildConfig.VERSION_CODE,
            versionName = com.example.syncstagereceiver.BuildConfig.VERSION_NAME
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

    // ==================== MISSING FILE RETRY ====================

    /**
     * Periodically check if previously missing files have been synced to disk.
     * When files become available, reload the full playlist so no videos are skipped.
     */
    private val missingFileRetryRunnable = object : Runnable {
        override fun run() {
            try {
                if (missingFileCount > 0 && currentPlaylist.isNotEmpty()) {
                    reloadPlaylistIfFilesAvailable()
                }
            } catch (e: Exception) {
                Timber.e(e, "Missing file retry error")
            }
            handler.postDelayed(this, MISSING_FILE_RETRY_INTERVAL_MS)
        }
    }

    private fun startMissingFileRetry() {
        handler.postDelayed(missingFileRetryRunnable, MISSING_FILE_RETRY_INTERVAL_MS)
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
                    val mediaItemCount = exoPlayer.mediaItemCount
                    if (mediaItemCount > 0) {
                        val nextIndex = (exoPlayer.currentMediaItemIndex + 1) % mediaItemCount
                        exoPlayer.seekTo(nextIndex, 0)
                        exoPlayer.playWhenReady = true
                        Timber.i("Transition watchdog: Forced advance to clip index $nextIndex")
                    }
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

            // Build new player FIRST (before releasing old) for crash safety
            val newPlayer = ExoPlayer.Builder(context).build()

            // Now safe to release old player
            handler.removeCallbacks(watchdogRunnable)
            handler.removeCallbacks(playbackReportRunnable)
            handler.removeCallbacks(missingFileRetryRunnable)
            exoPlayer.release()

            // Assign new player
            exoPlayer = newPlayer
            playerView.player = exoPlayer
            exoPlayer.volume = 0f
            exoPlayer.repeatMode = Player.REPEAT_MODE_ALL
            exoPlayer.playWhenReady = true

            // Re-add listener
            exoPlayer.addListener(playerListener)

            // Reload playlist if we had one
            if (savedPlaylist.isNotEmpty()) {
                currentPlaylistSignature = ""  // Force reload
                val mediaItems = buildValidMediaItems(savedPlaylist)
                missingFileCount = savedPlaylist.size - mediaItems.size
                if (mediaItems.isNotEmpty()) {
                    exoPlayer.setMediaItems(mediaItems)
                    exoPlayer.prepare()
                    currentPlaylistSignature = savedSignature
                    Timber.i("Nuclear recovery: Rebuilt player with ${mediaItems.size}/${savedPlaylist.size} items")
                }
            }

            // Restart watchdog, periodic reporting, and missing file retry
            startWatchdog()
            startPeriodicReporting()
            startMissingFileRetry()
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

                    val mediaItems = buildValidMediaItems(filenames)

                    // Log which specific files are missing
                    val validIds = mediaItems.map { it.mediaId }.toSet()
                    filenames.filter { it !in validIds }.forEach { missing ->
                        Timber.e("MISSING/INVALID FILE: $missing - Skipping")
                    }

                    val skipped = filenames.size - mediaItems.size
                    missingFileCount = skipped
                    if (skipped > 0) {
                        Timber.w("Playlist: $skipped/${filenames.size} files missing or invalid")
                    }
                    Timber.i("Playlist loading: ${mediaItems.size}/${filenames.size} files valid")
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
                // Map targetIndex from the full playlist to the filtered ExoPlayer playlist.
                // The Controller calculates targetIndex based on all filenames, but some may
                // have been filtered out. Find the target filename and locate it by mediaId.
                val targetFilename = filenames.getOrNull(targetIndex)
                val mappedIndex = if (targetFilename != null && actualItemCount > 0) {
                    // Search ExoPlayer's media items for the target filename
                    (0 until actualItemCount).firstOrNull { i ->
                        exoPlayer.getMediaItemAt(i).mediaId == targetFilename
                    } ?: -1
                } else -1
                val safeIndex = when {
                    mappedIndex >= 0 -> mappedIndex
                    targetIndex < actualItemCount -> targetIndex
                    else -> 0
                }

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

                // Save playback state for crash recovery
                savePlaybackState(filenames, safeIndex, adjustedPosition)

            } catch (e: Exception) {
                Timber.e(e, "Error in playPlaylist")
            }
        }
    }
    
    // ==================== TIMELINE SYNC LOOP ====================

    private val timelineSyncRunnable = object : Runnable {
        override fun run() {
            try {
                if (activeTimelineStart <= 0L) return

                val now = timeManager.getSynchronizedTime()
                val elapsed = now - activeTimelineStart
                val loopPosition = elapsed % activeTimelineTotalDuration

                val (expectedIndex, expectedOffset) = calculateTimelinePosition(loopPosition, activeTimelineDurations)

                val actualIndex = exoPlayer.currentMediaItemIndex
                val actualPosition = exoPlayer.currentPosition
                val isWrongVideo = actualIndex != expectedIndex
                val drift = expectedOffset - actualPosition
                val absDrift = abs(drift)

                if (isWrongVideo || absDrift > 200L) {
                    Timber.w("Timeline sync: HARD SEEK - expected idx=$expectedIndex@${expectedOffset}ms, actual idx=$actualIndex@${actualPosition}ms (drift=${drift}ms)")
                    exoPlayer.seekTo(expectedIndex, expectedOffset)
                    exoPlayer.playbackParameters = PlaybackParameters(1.0f)
                } else if (absDrift > 50L) {
                    if (drift > 0) {
                        Timber.v("Timeline sync: Behind by ${drift}ms - speed 1.03x")
                        exoPlayer.playbackParameters = PlaybackParameters(1.03f)
                    } else {
                        Timber.v("Timeline sync: Ahead by ${absDrift}ms - speed 0.97x")
                        exoPlayer.playbackParameters = PlaybackParameters(0.97f)
                    }
                } else {
                    if (exoPlayer.playbackParameters.speed != 1.0f) {
                        exoPlayer.playbackParameters = PlaybackParameters(1.0f)
                    }
                }

                handler.postDelayed(this, 2000L)
            } catch (e: Exception) {
                Timber.e(e, "Timeline sync loop error")
                handler.postDelayed(this, 2000L)
            }
        }
    }

    /**
     * Calculate which video index and offset within that video corresponds
     * to a given loopPosition within the total timeline duration.
     */
    private fun calculateTimelinePosition(loopPosition: Long, durations: List<Long>): Pair<Int, Long> {
        var remaining = loopPosition
        for ((index, duration) in durations.withIndex()) {
            if (remaining < duration) return Pair(index, remaining)
            remaining -= duration
        }
        return Pair(0, 0L)
    }

    // ==================== TIMELINE PLAYBACK CONTROL ====================

    /**
     * Start timeline-based playback. The controller announces a timeline
     * (playlist + absolute start time) and each receiver independently
     * calculates its own position and self-corrects continuously.
     */
    fun playTimeline(
        filenames: List<String>,
        durations: List<Long>,
        timelineStart: Long,
        totalDuration: Long
    ) {
        handler.post {
            try {
                // Store timeline parameters
                activeTimelineFilenames = filenames
                activeTimelineDurations = durations
                activeTimelineStart = timelineStart
                activeTimelineTotalDuration = totalDuration

                // Hide black overlay, show player
                hideBlackOverlay()
                showIdleImage(false)

                if (filenames.isEmpty()) {
                    stop()
                    return@post
                }

                // Load playlist if changed (same signature-based detection as playPlaylist)
                val newSignature = filenames.joinToString(",") { filename ->
                    val f = File(context.filesDir, "videos/$filename")
                    "$filename:${f.length()}:${f.lastModified()}"
                }
                if (newSignature != currentPlaylistSignature) {
                    Timber.i("Timeline: Loading NEW playlist: $filenames")

                    cleanupBeforePlaylistChange()

                    val mediaItems = buildValidMediaItems(filenames)

                    val validIds = mediaItems.map { it.mediaId }.toSet()
                    filenames.filter { it !in validIds }.forEach { missing ->
                        Timber.e("Timeline: MISSING/INVALID FILE: $missing - Skipping")
                    }

                    val skipped = filenames.size - mediaItems.size
                    missingFileCount = skipped
                    if (skipped > 0) {
                        Timber.w("Timeline: $skipped/${filenames.size} files missing or invalid")
                    }
                    if (mediaItems.isNotEmpty()) {
                        exoPlayer.setMediaItems(mediaItems)
                        exoPlayer.prepare()
                        currentPlaylistSignature = newSignature
                        currentPlaylist = filenames
                    } else {
                        Timber.e("Timeline: No valid files found in playlist!")
                        feedbackSender?.sendPlaybackStatus("ERROR", "NO_FILES", 0)
                        return@post
                    }
                }

                // Calculate initial position and seek
                val now = timeManager.getSynchronizedTime()
                val elapsed = now - timelineStart
                val loopPosition = elapsed % totalDuration
                val (startIndex, startOffset) = calculateTimelinePosition(loopPosition, durations)

                Timber.i("Timeline: Starting at index=$startIndex, offset=${startOffset}ms (elapsed=${elapsed}ms, loop=${loopPosition}ms)")
                exoPlayer.seekTo(startIndex, startOffset)
                exoPlayer.playbackParameters = PlaybackParameters(1.0f)
                exoPlayer.playWhenReady = true
                exoPlayer.play()

                // Start continuous self-sync loop
                handler.removeCallbacks(timelineSyncRunnable)
                handler.postDelayed(timelineSyncRunnable, 2000L)

                Timber.i("Timeline: Playback started with ${filenames.size} files, totalDuration=${totalDuration}ms")
            } catch (e: Exception) {
                Timber.e(e, "Error in playTimeline")
            }
        }
    }

    /**
     * Pause timeline playback. Stops the self-sync loop, pauses ExoPlayer,
     * and shows the black overlay.
     */
    fun pauseTimeline(pausePositionMs: Long) {
        handler.post {
            try {
                // Stop the self-sync loop
                handler.removeCallbacks(timelineSyncRunnable)

                // Clear active timeline fields
                activeTimelineStart = 0L
                activeTimelineDurations = emptyList()
                activeTimelineTotalDuration = 0L
                activeTimelineFilenames = emptyList()

                // Pause ExoPlayer
                exoPlayer.pause()
                exoPlayer.playbackParameters = PlaybackParameters(1.0f)

                // Show black overlay
                showBlackOverlay()

                // Store pause position for potential resume
                timelinePausePositionMs = pausePositionMs

                // Send PAUSED feedback
                val filename = exoPlayer.currentMediaItem?.mediaId ?: ""
                feedbackSender?.sendPlaybackStatus("PAUSED", filename, exoPlayer.currentPosition)
                sendPlaybackReport(filename, "PAUSED")

                Timber.i("Timeline: Paused at position=${pausePositionMs}ms")
            } catch (e: Exception) {
                Timber.e(e, "Error in pauseTimeline")
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
     * Called when SYNC_PLAYLIST completes. Invalidates the playlist cache and
     * auto-reloads the player so the loop list immediately reflects the newly
     * synced files. If playlist content changed, does a full reload. If same
     * playlist, preserves playback position while picking up newly available files.
     *
     * @param syncedFileNames the file names from the completed sync manifest
     */
    fun onSyncCompleted(syncedFileNames: List<String>) {
        handler.post {
            Timber.i("Sync completed with ${syncedFileNames.size} files. Invalidating playlist cache.")
            currentPlaylistSignature = ""

            val isActive = exoPlayer.isPlaying || exoPlayer.playWhenReady

            if (isActive && syncedFileNames.isNotEmpty()) {
                if (syncedFileNames != currentPlaylist) {
                    // Playlist content changed — full reload with new files
                    Timber.i("Playlist changed after sync (was ${currentPlaylist.size} files, now ${syncedFileNames.size}). Auto-reloading player.")
                    currentPlaylist = syncedFileNames
                    reloadPlaylistIfFilesAvailable()
                } else {
                    // Same playlist — reload to pick up any newly available files
                    Timber.i("Same playlist after sync. Reloading to pick up new files.")
                    reloadPlaylistIfFilesAvailable()
                }
            } else if (syncedFileNames.isNotEmpty()) {
                // Player was idle — save the synced list so auto-resume can use it
                currentPlaylist = syncedFileNames
                savePlaybackState(syncedFileNames, 0, 0)
                Timber.i("Player idle. Saved synced playlist for future resume.")
            }
        }
    }

    /**
     * Check if previously missing files are now available on disk and reload
     * the ExoPlayer playlist to include them. Preserves current playback position.
     */
    private fun reloadPlaylistIfFilesAvailable() {
        val filenames = currentPlaylist
        if (filenames.isEmpty()) return

        val mediaItems = buildValidMediaItems(filenames)
        val nowMissing = filenames.size - mediaItems.size
        val currentLoadedCount = exoPlayer.mediaItemCount

        if (mediaItems.size <= currentLoadedCount && mediaItems.size == currentLoadedCount) {
            // No new files became available
            missingFileCount = nowMissing
            return
        }

        // New files are available or playlist changed — reload
        Timber.i("Playlist reload: ${mediaItems.size} files now available (was $currentLoadedCount, total ${filenames.size})")

        // Remember what's currently playing so we can resume at the same video
        val currentMediaId = exoPlayer.currentMediaItem?.mediaId
        val currentPos = exoPlayer.currentPosition

        cleanupBeforePlaylistChange()
        exoPlayer.setMediaItems(mediaItems)
        exoPlayer.prepare()

        // Find the previously-playing video in the new (expanded) playlist
        val resumeIndex = if (currentMediaId != null) {
            mediaItems.indexOfFirst { it.mediaId == currentMediaId }.takeIf { it >= 0 } ?: 0
        } else 0

        exoPlayer.seekTo(resumeIndex, currentPos)
        exoPlayer.playWhenReady = true

        // Update signature and missing count
        currentPlaylistSignature = filenames.joinToString(",") { filename ->
            val f = File(context.filesDir, "videos/$filename")
            "$filename:${f.length()}:${f.lastModified()}"
        }
        missingFileCount = nowMissing

        if (nowMissing > 0) {
            Timber.w("Playlist reload: still missing $nowMissing/${filenames.size} files")
        } else {
            Timber.i("Playlist reload: all ${filenames.size} files now loaded")
        }
    }

    /**
     * Build validated MediaItems from a filename list.
     * Returns only files that exist on disk and are > 1KB.
     */
    private fun buildValidMediaItems(filenames: List<String>): List<MediaItem> {
        return filenames.mapNotNull { filename ->
            val file = File(context.filesDir, "videos/$filename")
            if (file.exists() && file.length() > 1000) {
                MediaItem.Builder()
                    .setUri(file.absolutePath)
                    .setMediaId(filename)
                    .build()
            } else null
        }
    }

    fun stop() {
        handler.post {
            handler.removeCallbacks(timelineSyncRunnable)
            activeTimelineStart = 0L
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            currentPlaylistSignature = ""
            currentPlaylist = emptyList()
            clearSavedPlaybackState()
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
        handler.removeCallbacks(missingFileRetryRunnable)
        handler.removeCallbacks(timelineSyncRunnable)
        activeTimelineStart = 0L
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
            exoPlayer.playbackState == Player.STATE_BUFFERING -> "BUFFERING"
            blackOverlay?.visibility == View.VISIBLE -> "PAUSED"
            else -> "IDLE"
        }
        return Triple(status, filename, position)
    }

    private fun savePlaybackState(filenames: List<String>, index: Int, position: Long) {
        try {
            sharedPreferences.edit()
                .putString("last_playlist_json", Gson().toJson(filenames))
                .putInt("last_index", index)
                .putLong("last_position", position)
                .putLong("last_saved_at", System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            Timber.w(e, "Failed to save playback state")
        }
    }

    private fun clearSavedPlaybackState() {
        sharedPreferences.edit()
            .remove("last_playlist_json")
            .remove("last_index")
            .remove("last_position")
            .remove("last_saved_at")
            .apply()
    }

    fun tryResumeFromSavedState() {
        try {
            val json = sharedPreferences.getString("last_playlist_json", null)
            val savedAt = sharedPreferences.getLong("last_saved_at", 0)
            val ageMs = System.currentTimeMillis() - savedAt

            if (json == null || ageMs > 24 * 60 * 60 * 1000L) {
                Timber.d("No recent saved state (age=${ageMs / 1000}s). Waiting for Controller command.")
                return
            }

            val filenames: List<String> = Gson().fromJson(json, object : TypeToken<List<String>>() {}.type)
            val index = sharedPreferences.getInt("last_index", 0)
            val position = sharedPreferences.getLong("last_position", 0)

            if (filenames.isNotEmpty()) {
                Timber.i("Resuming from saved state: ${filenames.size} files, index=$index, pos=${position}ms (saved ${ageMs / 1000}s ago)")
                playPlaylist(filenames, index, position, System.currentTimeMillis())
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to resume from saved state")
        }
    }
}
