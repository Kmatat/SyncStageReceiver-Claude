package com.example.syncstagereceiver.util

import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Utility class for file verification.
 * This file is updated to include the SHA-256 hashing function.
 */
class VerificationUtils {

    /**
     * NEW
     * Calculates the SHA-256 hash of a given file.
     * This is required by FileHandler to verify downloaded videos.
     */
    fun getFileSha256(file: File): String? {
        if (!file.exists()) {
            Timber.w("Cannot calculate hash: file does not exist at ${file.absolutePath}")
            return null
        }
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            // Convert byte array to hex string
            val bytes = digest.digest()
            bytes.fold("") { str, b -> str + "%02x".format(b) }
        } catch (e: Exception) {
            Timber.e(e, "Error calculating SHA-256 hash for ${file.name}")
            null
        }
    }
}