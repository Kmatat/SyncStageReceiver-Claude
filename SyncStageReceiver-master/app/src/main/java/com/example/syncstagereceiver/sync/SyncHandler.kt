package com.example.syncstagereceiver.sync

import com.example.syncstagereceiver.services.FeedbackSender
import com.example.syncstagereceiver.util.FileHandler
import com.example.syncstagereceiver.util.VerificationUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

/**
 * ==================== SYNC HANDLER ====================
 *
 * Handles playlist synchronization from Controller to Receiver.
 * Downloads video files and verifies their integrity.
 */
class SyncHandler(
    private val fileHandler: FileHandler,
    private val gson: Gson,
    private val verificationUtils: VerificationUtils
) {
    var feedbackSender: FeedbackSender? = null
    var onSyncCompleted: ((List<String>) -> Unit)? = null

    // Retry configuration
    private val MAX_RETRIES = 3
    private val RETRY_DELAY_MS = 2000L

    private val syncScope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, e ->
            Timber.e(e, "Unhandled exception in SyncHandler")
        }
    )

    // Track active sync job so we can cancel it when a new SYNC_PLAYLIST arrives
    @Volatile
    private var activeSyncJob: Job? = null
    private val syncJobLock = Mutex()

    // First SYNC_PLAYLIST after boot should start immediately;
    // only subsequent ones need thunder-herd jitter.
    @Volatile
    private var hasSyncedOnce = false

    data class FileManifest(val id: String, val name: String, val url: String, val hash: String)

    fun handleSyncPlaylist(jsonPayload: String) {
        // Serialize cancel-and-start under a mutex so a new SYNC_PLAYLIST
        // cannot race the tail of a cancelling job still writing .tmp files.
        syncScope.launch {
            syncJobLock.withLock {
                val prev = activeSyncJob
                if (prev != null && prev.isActive) {
                    Timber.w("Cancelling previous sync — new SYNC_PLAYLIST arrived")
                    prev.cancelAndJoin()
                }
                activeSyncJob = syncScope.launch { runSyncPlaylist(jsonPayload) }
            }
        }
    }

    private suspend fun runSyncPlaylist(jsonPayload: String) {
        var playlistId: String? = null
        var filesToSync: List<FileManifest> = emptyList()

        try {
            val payloadType = object : TypeToken<Map<String, Any>>() {}.type
            val payload: Map<String, Any> = gson.fromJson(jsonPayload, payloadType)
            playlistId = payload["playlist_id"] as? String
            val filesList = payload["files"] as? List<Map<String, String>>

            filesToSync = filesList?.mapNotNull {
                FileManifest(
                    id = it["id"] ?: return@mapNotNull null,
                    name = it["name"] ?: return@mapNotNull null,
                    url = it["url"] ?: return@mapNotNull null,
                    hash = it["hash"] ?: "",
                )
            } ?: emptyList()

            if (playlistId == null) throw Exception("Missing playlist_id")

            Timber.i("Syncing playlist $playlistId. Files: ${filesToSync.size}")
            sendSyncProgress(playlistId, 0, filesToSync.size, 0)

            // JITTER: Random delay (0-3s) to prevent thunder-herd effect on re-syncs.
            // Skip on first sync after boot so the device starts playing as quickly as possible.
            if (hasSyncedOnce) {
                delay(Random.nextLong(0, 3000))
            } else {
                hasSyncedOnce = true
            }

            // Track progress
            var completedFiles = 0
            val failedFiles = mutableListOf<String>()

            // Download files one at a time to avoid memory issues
            for (file in filesToSync) {
                try {
                    secureDownloadWithRetry(file)
                    completedFiles++

                    val progress = (completedFiles * 100) / filesToSync.size
                    sendSyncProgress(playlistId, completedFiles, filesToSync.size, progress)

                } catch (e: Exception) {
                    Timber.e("Failed to sync ${file.name}: ${e.message}")
                    failedFiles.add(file.name)
                }

                // Small delay between files to avoid overwhelming the network
                delay(100)
            }

            if (failedFiles.isNotEmpty()) {
                throw Exception("Failed files: $failedFiles")
            }

            // COMMIT PHASE
            Timber.i("All downloads verified. Committing changes...")

            val failedFinalizations = mutableListOf<String>()
            filesToSync.forEach { manifest ->
                if (!fileHandler.finalizeDownload(manifest.name)) {
                    Timber.e("Failed to finalize ${manifest.name}")
                    failedFinalizations.add(manifest.name)
                }
            }
            if (failedFinalizations.isNotEmpty()) {
                throw Exception("Failed to finalize: $failedFinalizations")
            }

            feedbackSender?.sendSyncStatus("COMPLETED", playlistId, filesToSync.size)
            Timber.i("Sync completed successfully for playlist $playlistId")

            // Notify player BEFORE cleanup so it reloads with new files
            // while old files still exist on disk (prevents ExoPlayer errors)
            val syncedFileNames = filesToSync.map { it.name }
            onSyncCompleted?.invoke(syncedFileNames)

            // Cleanup old files AFTER player has reloaded
            fileHandler.cleanupOldFiles(syncedFileNames)

        } catch (e: Exception) {
            Timber.e(e, "Sync Failed")
            try {
                feedbackSender?.sendSyncStatus("ERROR", playlistId ?: "unknown", filesToSync.size)
            } catch (_: Exception) {}
        }
    }

    private fun sendSyncProgress(playlistId: String, completed: Int, total: Int, percentage: Int) {
        try {
            feedbackSender?.sendSyncStatus("SYNCING", playlistId, completed)
            Timber.d("Sync progress: $completed/$total ($percentage%)")
        } catch (e: Exception) {
            Timber.w(e, "Failed to send sync progress")
        }
    }

    private suspend fun secureDownloadWithRetry(file: FileManifest) {
        var lastException: Exception? = null

        repeat(MAX_RETRIES) { attempt ->
            try {
                secureDownload(file)
                return // Success
            } catch (e: Exception) {
                lastException = e
                Timber.w("Download attempt ${attempt + 1} failed for ${file.name}: ${e.message}")

                if (attempt < MAX_RETRIES - 1) {
                    delay(RETRY_DELAY_MS * (attempt + 1)) // Exponential backoff
                }
            }
        }

        throw lastException ?: Exception("Download failed after $MAX_RETRIES attempts")
    }

    private fun secureDownload(file: FileManifest) {
        val targetFile = fileHandler.getLocalFile(file.name)

        // Check existing live file
        if (targetFile.exists() && targetFile.length() > 1000) {
            if (file.hash.isEmpty()) {
                Timber.d("File exists (no hash to verify): ${file.name}")
                return
            }
            val localHash = verificationUtils.getFileSha256(targetFile)
            if (localHash != null && localHash.equals(file.hash, ignoreCase = true)) {
                Timber.d("Valid file exists: ${file.name}")
                return
            }
        }

        // Download to temp file
        val tempFile = File(targetFile.parent, "${file.name}.tmp")

        var downloadedBytes = 0L
        if (tempFile.exists()) {
            downloadedBytes = tempFile.length()
            // Check if temp file is already complete
            if (downloadedBytes > 0) {
                val tempHash = verificationUtils.getFileSha256(tempFile)
                if (tempHash != null && tempHash.equals(file.hash, ignoreCase = true)) {
                    return // Already downloaded
                }
            }
        }

        Timber.i("Downloading ${file.name} to staging...")

        val connection = URL(file.url).openConnection() as HttpURLConnection
        connection.connectTimeout = 30000
        connection.readTimeout = 60000

        if (downloadedBytes > 0) {
            connection.setRequestProperty("Range", "bytes=$downloadedBytes-")
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                if (responseCode == 416) tempFile.delete() // Invalid range
                throw Exception("Server returned code: $responseCode")
            }

            val append = (responseCode == HttpURLConnection.HTTP_PARTIAL)

            connection.inputStream.use { input ->
                FileOutputStream(tempFile, append).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                }
            }

            // Verify hash (skip if controller didn't provide one)
            if (file.hash.isNotEmpty()) {
                val downloadedHash = verificationUtils.getFileSha256(tempFile)
                if (downloadedHash == null || !downloadedHash.equals(file.hash, ignoreCase = true)) {
                    tempFile.delete()
                    throw Exception("Hash mismatch for ${file.name}")
                }
            }

            Timber.i("Download completed: ${file.name}")

        } catch (e: Exception) {
            Timber.w("Download interrupted for ${file.name}: ${e.message}")
            if (tempFile.exists()) {
                tempFile.delete()
            }
            throw e
        } finally {
            connection.disconnect()
        }
    }
}
