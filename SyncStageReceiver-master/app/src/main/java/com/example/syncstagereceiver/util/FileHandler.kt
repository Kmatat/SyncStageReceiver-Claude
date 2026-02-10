package com.example.syncstagereceiver.util

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import okio.appendingSink
import timber.log.Timber
import java.io.File
import java.io.IOException

class FileHandler(
    context: Context,
    private val okHttpClient: OkHttpClient
) {
    private val localDir = File(context.filesDir, "videos")

    init {
        if (!localDir.exists()) localDir.mkdirs()
    }

    fun getLocalFile(filename: String): File {
        return File(localDir, filename)
    }

    /**
     * RENAMES the .tmp file to the final filename.
     * This is the "Commit" phase of the Atomic Sync.
     */
    fun finalizeDownload(filename: String): Boolean {
        val targetFile = getLocalFile(filename)
        val tempFile = File(targetFile.parent, "$filename.tmp")

        if (tempFile.exists()) {
            if (targetFile.exists()) {
                // Ensure target is gone before renaming (Atomic replacement)
                targetFile.delete()
            }
            val success = tempFile.renameTo(targetFile)
            if (success) {
                Timber.i("Finalized: $filename")
            } else {
                Timber.e("Failed to rename temp file for $filename")
            }
            return success
        } else {
            // If temp doesn't exist, maybe the file was already valid and we skipped download?
            // In that case, verify the target exists.
            return targetFile.exists()
        }
    }

    fun cleanupOldFiles(filesToKeep: List<String>) {
        val localFiles = localDir.listFiles() ?: return
        
        // 1. First pass: Delete files not in the current playlist
        for (file in localFiles) {
            val name = file.name

            // Keep .tmp files if they belong to a video in the new playlist
            val isRelevantTemp = name.endsWith(".tmp") &&
                    filesToKeep.contains(name.removeSuffix(".tmp"))

            val isRelevantVideo = filesToKeep.contains(name)

            if (!isRelevantVideo && !isRelevantTemp) {
                Timber.i("Deleting old/unused file: $name")
                try {
                    if (file.delete()) {
                        Timber.d("Deleted: $name")
                    } else {
                        Timber.e("Failed to delete: $name")
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error deleting file: $name")
                }
            }
        }
        
        // 2. Second pass: Check disk space and emergency cleanup if needed
        // If free space is < 500MB, we might need to be more aggressive or warn
        try {
            val freeSpace = localDir.freeSpace
            val totalSpace = localDir.totalSpace
            Timber.i("Disk Status: ${freeSpace / 1024 / 1024}MB free of ${totalSpace / 1024 / 1024}MB")
            
            if (freeSpace < 500 * 1024 * 1024) { // Less than 500MB free
                Timber.w("LOW DISK SPACE WARNING! Free: ${freeSpace / 1024 / 1024}MB")
                // In a stricter implementation, we could try to delete even 'kept' files if they are not currently playing,
                // but that risks playback failure. For now, just logging warning is safer 
                // as we already deleted all non-playlist files.
            }
        } catch (e: Exception) {
            Timber.e(e, "Error checking disk space")
        }
    }
}