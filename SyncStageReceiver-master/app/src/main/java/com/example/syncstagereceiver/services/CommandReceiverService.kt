package com.example.syncstagereceiver.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.syncstagereceiver.R
import com.example.syncstagereceiver.network.Feedback
import com.example.syncstagereceiver.network.MulticastReceiver
import com.example.syncstagereceiver.network.PlaybackReportFeedback
import com.example.syncstagereceiver.network.PlaybackStatusFeedback
import com.example.syncstagereceiver.network.StatusReportFeedback
import com.example.syncstagereceiver.network.SyncStatusFeedback
import com.example.syncstagereceiver.ui.MainActivity
import com.example.syncstagereceiver.util.LocalPlaybackLogger
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * ==================== FEEDBACK SENDER INTERFACE ====================
 * 
 * Interface for sending feedback from Receiver to Controller.
 * 
 * UPDATES:
 * - Added sendPlaybackReport() for detailed playback logging
 */
interface FeedbackSender {
    fun sendSyncStatus(status: String, playlistId: String?, fileCount: Int)
    fun sendPlaybackStatus(status: String, filename: String, position: Long)
    fun isConnected(): Boolean
    
    // Send detailed playback report for Firebase logging
    fun sendPlaybackReport(
        deviceId: String,
        deviceName: String,
        videoFilename: String,
        playlistIndex: Int,
        playlistTotal: Int,
        positionMs: Long,
        status: String
    )

    // Send full status report in response to REQUEST_STATUS
    fun sendStatusReport(
        deviceId: String,
        deviceName: String,
        status: String,
        videoFilename: String,
        playlistIndex: Int,
        playlistTotal: Int,
        positionMs: Long
    )
}

/**
 * ==================== COMMAND RECEIVER SERVICE ====================
 * 
 * Foreground service that listens for commands from the Controller.
 * 
 * UPDATES:
 * - Increased socket timeout to 60s for mesh network reliability
 * - Added WakeLock with timeout and renewal
 * - Added playback report sending capability
 */
class CommandReceiverService : Service() {

    private val binder = LocalBinder()
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var clientOutput: PrintWriter? = null
    private var clientInput: BufferedReader? = null
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var wakeLock: PowerManager.WakeLock? = null
    private val gson: Gson = GsonBuilder().create()
    private val handler = Handler(Looper.getMainLooper())

    private var multicastReceiver: MulticastReceiver? = null
    internal var feedbackSender: FeedbackSender? = null
    var localLogger: LocalPlaybackLogger? = null

    // Heartbeat tracking for faster dead connection detection
    @Volatile
    private var lastMessageReceivedAt: Long = System.currentTimeMillis()
    private val HEARTBEAT_DEAD_THRESHOLD_MS = 15_000L  // Declare dead after 15s silence

    // WakeLock renewal interval (50 minutes, renews before 1-hour timeout)
    private val WAKELOCK_RENEWAL_MS = 50 * 60 * 1000L

    inner class LocalBinder : Binder() {
        fun getService(): CommandReceiverService = this@CommandReceiverService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun setFeedbackSender(sender: FeedbackSender?) {
        this.feedbackSender = sender
    }

    /**
     * Check if the server socket is still alive and listening for connections.
     */
    fun isServerSocketAlive(): Boolean {
        return serverSocket != null && !serverSocket!!.isClosed
    }

    override fun onCreate() {
        super.onCreate()
        Timber.i("CommandReceiverService creating.")
        acquireWakeLock()
        scheduleWakeLockRenewal()
        createNotificationChannel()
        startForeground(1, createNotification())

        serviceScope.launch {
            startServer()
        }

        startMulticastListener()
    }

    private fun startMulticastListener() {
        Timber.d("Starting Multicast Listener...")
        multicastReceiver = MulticastReceiver(this) { jsonCommand ->
            Timber.i("Broadcast from Multicast: $jsonCommand")
            sendBroadcast(Intent("COMMAND_RECEIVED").apply {
                putExtra("COMMAND_JSON", jsonCommand)
            })
        }
        multicastReceiver?.start()
    }

    private fun startServer() {
        try {
            serverSocket = ServerSocket(12345)
            Timber.i("Server socket started on port 12345")

            while (serviceScope.isActive) {
                Timber.d("Waiting for new client connection...")
                val socket = serverSocket!!.accept()
                Timber.i("Client connected: ${socket.inetAddress.hostAddress}")

                cleanupClientSocket()
                clientSocket = socket

                // Reduced timeout to 20s for faster dead connection detection
                clientSocket?.soTimeout = 20000
                clientSocket?.keepAlive = true

                try {
                    clientInput = BufferedReader(InputStreamReader(socket.getInputStream()))
                    clientOutput = PrintWriter(socket.getOutputStream(), true)

                    val sender = object : FeedbackSender {
                        override fun sendSyncStatus(status: String, playlistId: String?, fileCount: Int) {
                            // Get free disk space
                            val freeSpace = try {
                                java.io.File(filesDir, "videos").freeSpace
                            } catch (e: Exception) { 0L }
                            
                            val feedback = SyncStatusFeedback(
                                status = status, 
                                syncedPlaylistId = playlistId, 
                                fileCount = fileCount,
                                diskFreeSpace = freeSpace
                            )
                            sendFeedback(feedback)
                        }

                        override fun sendPlaybackStatus(status: String, filename: String, position: Long) {
                            val feedback = PlaybackStatusFeedback(status = status, filename = filename, position = position)
                            sendFeedback(feedback)
                        }

                        override fun isConnected(): Boolean {
                            return clientSocket?.isConnected == true && clientSocket?.isClosed == false
                        }
                        
                        override fun sendPlaybackReport(
                            deviceId: String,
                            deviceName: String,
                            videoFilename: String,
                            playlistIndex: Int,
                            playlistTotal: Int,
                            positionMs: Long,
                            status: String
                        ) {
                            val feedback = PlaybackReportFeedback(
                                deviceId = deviceId,
                                deviceName = deviceName,
                                videoFilename = videoFilename,
                                playlistIndex = playlistIndex,
                                playlistTotal = playlistTotal,
                                positionMs = positionMs,
                                status = status
                            )
                            sendFeedback(feedback)
                        }

                        override fun sendStatusReport(
                            deviceId: String,
                            deviceName: String,
                            status: String,
                            videoFilename: String,
                            playlistIndex: Int,
                            playlistTotal: Int,
                            positionMs: Long
                        ) {
                            // Get free disk space
                            val freeSpace = try {
                                java.io.File(filesDir, "videos").freeSpace
                            } catch (e: Exception) { 0L }
                            
                            val feedback = StatusReportFeedback(
                                deviceId = deviceId,
                                deviceName = deviceName,
                                status = status,
                                videoFilename = videoFilename,
                                playlistIndex = playlistIndex,
                                playlistTotal = playlistTotal,
                                positionMs = positionMs,
                                diskFreeSpace = freeSpace
                            )
                            sendFeedback(feedback)
                        }
                    }
                    setFeedbackSender(sender)
                    lastMessageReceivedAt = System.currentTimeMillis()
                    localLogger?.logConnectionEvent("CLIENT_CONNECTED", socket.inetAddress?.hostAddress ?: "unknown")

                    while (serviceScope.isActive) {
                        try {
                            val command = clientInput?.readLine()
                            if (command == null) {
                                Timber.w("Client disconnected (null read)")
                                localLogger?.logConnectionEvent("CLIENT_DISCONNECTED", "null read")
                                break
                            }

                            // Update heartbeat tracker on every message
                            lastMessageReceivedAt = System.currentTimeMillis()

                            // Intercept HEARTBEAT at transport level and respond immediately
                            // without broadcasting to the activity layer
                            if (command.contains("\"HEARTBEAT\"") && !command.contains("\"HEARTBEAT_ACK\"")) {
                                try {
                                    clientOutput?.println("{\"action\":\"HEARTBEAT_ACK\"}")
                                    clientOutput?.flush()
                                    Timber.v("HEARTBEAT_ACK sent")
                                } catch (e: Exception) {
                                    Timber.e(e, "Failed to send HEARTBEAT_ACK")
                                }
                                continue
                            }

                            Timber.d("Received TCP command: $command")
                            sendBroadcast(Intent("COMMAND_RECEIVED").apply {
                                putExtra("COMMAND_JSON", command)
                            })

                        } catch (e: SocketTimeoutException) {
                            // Check heartbeat: if no message for > threshold, connection is dead
                            val silenceMs = System.currentTimeMillis() - lastMessageReceivedAt
                            if (silenceMs > HEARTBEAT_DEAD_THRESHOLD_MS) {
                                Timber.w("Heartbeat dead: No message for ${silenceMs}ms (threshold: ${HEARTBEAT_DEAD_THRESHOLD_MS}ms). Closing connection.")
                                localLogger?.logConnectionEvent("HEARTBEAT_DEAD", "silence=${silenceMs}ms")
                                break
                            } else {
                                // Socket timeout but heartbeat still recent — continue waiting
                                Timber.d("Socket timeout but last message was ${silenceMs}ms ago, continuing...")
                                continue
                            }
                        } catch (e: IOException) {
                            Timber.e("Socket read error: ${e.message}")
                            localLogger?.logConnectionEvent("SOCKET_ERROR", e.message ?: "unknown")
                            break
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error initializing client streams.")
                    localLogger?.logConnectionEvent("STREAM_INIT_ERROR", e.message ?: "unknown")
                } finally {
                    localLogger?.logConnectionEvent("CLIENT_CLEANUP", "cleaning up client socket")
                    cleanupClientSocket()
                    setFeedbackSender(null)
                }
            }
        } catch (e: IOException) {
            Timber.e(e, "Server socket error.")
        } finally {
            serverSocket?.close()
        }
    }

    private fun sendFeedback(feedback: Feedback) {
        if (feedbackSender?.isConnected() == true) {
            serviceScope.launch {
                try {
                    val jsonFeedback = gson.toJson(feedback)
                    if (clientOutput != null && clientSocket?.isClosed == false) {
                        clientOutput?.println(jsonFeedback)
                        clientOutput?.flush()
                        Timber.v("Feedback sent: ${feedback.action}")
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to send feedback.")
                }
            }
        }
    }

    private fun cleanupClientSocket() {
        try {
            clientInput?.close()
            clientOutput?.close()
            clientSocket?.close()
        } catch (e: IOException) { }
        clientSocket = null
        clientOutput = null
        clientInput = null
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.i("CommandReceiverService destroying.")
        handler.removeCallbacksAndMessages(null)
        multicastReceiver?.stop()
        serviceJob.cancel()
        cleanupClientSocket()
        try { serverSocket?.close() } catch (e: Exception) {}
        releaseWakeLock()
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, "CommandReceiverChannel")
            .setContentTitle("SyncStage Receiver")
            .setContentText("Listening for commands...")
            .setSmallIcon(R.drawable.app_banner)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                "CommandReceiverChannel",
                "Command Receiver Service",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(serviceChannel)
        }
    }

    // ==================== WAKELOCK MANAGEMENT (UPDATED) ====================

    /**
     * Acquire WakeLock with 1-hour timeout.
     * This prevents the device from sleeping while the service is running.
     */
    private fun acquireWakeLock() {
        try {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
                newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SyncStageReceiver::WakeLock").apply {
                    acquire(60 * 60 * 1000L)  // 1 hour timeout
                }
            }
            Timber.i("WakeLock acquired with 1-hour timeout")
        } catch (e: Exception) {
            Timber.e(e, "Failed to acquire WakeLock")
        }
    }

    /**
     * Release WakeLock safely
     */
    private fun releaseWakeLock() {
        try {
            wakeLock?.let { 
                if (it.isHeld) {
                    it.release()
                    Timber.i("WakeLock released")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error releasing WakeLock")
        }
    }
    
    /**
     * Schedule WakeLock renewal to prevent timeout
     */
    private fun scheduleWakeLockRenewal() {
        handler.postDelayed({
            Timber.d("Renewing WakeLock...")
            releaseWakeLock()
            acquireWakeLock()
            scheduleWakeLockRenewal()  // Schedule next renewal
        }, WAKELOCK_RENEWAL_MS)
    }
}
