package com.example.syncstagereceiver.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
import com.example.syncstagereceiver.R
import com.example.syncstagereceiver.databinding.ActivityMainBinding
import com.example.syncstagereceiver.kiosk.KioskManager
import com.example.syncstagereceiver.network.NetworkServiceAdvertiser
import com.example.syncstagereceiver.network.StreamingServer
import com.example.syncstagereceiver.network.WifiReconnectManager
import com.example.syncstagereceiver.playback.PlaybackHandler
import com.example.syncstagereceiver.player.PlayerManager
import com.example.syncstagereceiver.services.CommandReceiverService
import com.example.syncstagereceiver.services.FeedbackSender
import com.example.syncstagereceiver.sync.SyncHandler
import com.example.syncstagereceiver.util.FileHandler
import com.example.syncstagereceiver.util.LocalPlaybackLogger
import com.example.syncstagereceiver.util.TimeManager
import com.example.syncstagereceiver.util.VerificationUtils
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import timber.log.Timber

/**
 * ==================== RECEIVER MAIN ACTIVITY ====================
 * 
 * Main activity for the Receiver app running on Xiaomi TV Boxes.
 * 
 * UPDATES:
 * - Added black overlay support for pause state
 * - Added auto-recovery loop for disconnections
 * - Improved error handling
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var playerManager: PlayerManager
    private lateinit var playbackHandler: PlaybackHandler
    private lateinit var syncHandler: SyncHandler
    private lateinit var fileHandler: FileHandler
    private lateinit var verificationUtils: VerificationUtils
    private lateinit var networkServiceAdvertiser: NetworkServiceAdvertiser
    private lateinit var sharedPreferences: SharedPreferences
    private val gson = Gson()
    private lateinit var timeManager: TimeManager
    private lateinit var kioskManager: KioskManager
    private lateinit var streamingServer: StreamingServer
    private lateinit var wifiReconnectManager: WifiReconnectManager
    private lateinit var localPlaybackLogger: LocalPlaybackLogger

    private val handler = Handler(Looper.getMainLooper())

    private var commandReceiverService: CommandReceiverService? = null
    private var isBound = false
    private var feedbackSender: FeedbackSender? = null

    // Auto-recovery settings (faster: 10s instead of 30s)
    private val AUTO_RECOVERY_INTERVAL_MS = 10_000L  // Check every 10 seconds

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Timber.i("POST_NOTIFICATIONS permission granted.")
            binding.permissionErrorText.visibility = View.GONE
            startAndBindService()
        } else {
            Timber.e("POST_NOTIFICATIONS permission DENIED.")
            binding.permissionErrorText.visibility = View.VISIBLE
        }
    }

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra("COMMAND_JSON")?.let {
                handleCommand(it)
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as CommandReceiverService.LocalBinder
            commandReceiverService = binder.getService()
            isBound = true
            Timber.i("CommandReceiverService connected.")

            feedbackSender = commandReceiverService?.feedbackSender
            updateHandlersWithFeedbackSender()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            commandReceiverService = null
            isBound = false
            feedbackSender = null
            updateHandlersWithFeedbackSender()
            Timber.w("CommandReceiverService disconnected.")
        }
    }

    private fun updateHandlersWithFeedbackSender() {
        playerManager.feedbackSender = feedbackSender
        playbackHandler.feedbackSender = feedbackSender
        syncHandler.feedbackSender = feedbackSender
        commandReceiverService?.localLogger = localPlaybackLogger
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        sharedPreferences = getSharedPreferences("SyncStageReceiverPrefs", Context.MODE_PRIVATE)

        timeManager = TimeManager(this)
        fileHandler = FileHandler(this, OkHttpClient())
        verificationUtils = VerificationUtils()
        kioskManager = KioskManager(this)

        // Initialize local playback logger
        localPlaybackLogger = LocalPlaybackLogger(this)
        localPlaybackLogger.init()

        // UPDATED: Initialize PlayerManager with black overlay view
        playerManager = PlayerManager(
            context = this,
            playerView = binding.playerView,
            idleImageView = binding.idleImageView,
            timeManager = timeManager,
            blackOverlay = binding.blackOverlay  // NEW: Pass black overlay
        )

        playerManager.localLogger = localPlaybackLogger

        playbackHandler = PlaybackHandler(playerManager, fileHandler, gson)
        syncHandler = SyncHandler(fileHandler, gson, verificationUtils)

        networkServiceAdvertiser = NetworkServiceAdvertiser(this, sharedPreferences, 12345)
        networkServiceAdvertiser.registerService()

        // Start P2P Server
        streamingServer = StreamingServer(this)
        streamingServer.start()

        // Start WiFi auto-reconnection manager
        wifiReconnectManager = WifiReconnectManager(this)
        wifiReconnectManager.start()

        binding.idleImageView.setOnClickListener {
            showRenameDialog()
        }

        checkNotificationPermissionAndStartService()

        val filter = IntentFilter("COMMAND_RECEIVED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(commandReceiver, filter)
        }

        kioskManager.enableKioskMode(this)

        // Auto-Resume Logic
        handler.postDelayed({
            val (status, _, _) = playerManager.getCurrentStatus()
            if (status != "PLAYING") {
                Timber.i("No command from Controller yet. Attempting local resume...")
                playerManager.tryResumeFromSavedState()
            } else {
                Timber.i("Controller took control during boot. Local resume skipped.")
            }
        }, 5000)
        
        // NEW: Start auto-recovery loop
        startAutoRecoveryLoop()
    }

    private fun showRenameDialog() {
        val currentName = sharedPreferences.getString("device_name", "") ?: ""
        val input = EditText(this).apply {
            hint = "Enter Device Name"
            setText(currentName)
        }

        AlertDialog.Builder(this)
            .setTitle("Set Device Name")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    sharedPreferences.edit().putString("device_name", newName).apply()
                    networkServiceAdvertiser.updateAdvertisement()
                    Timber.i("Manually updated device name to: $newName")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkNotificationPermissionAndStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    startAndBindService()
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    binding.permissionErrorText.visibility = View.VISIBLE
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            startAndBindService()
        }
    }

    private fun startAndBindService() {
        val serviceIntent = Intent(this, CommandReceiverService::class.java)
        try {
            ContextCompat.startForegroundService(this, serviceIntent)
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Timber.e(e, "Failed to start service.")
        }
    }

    private fun handleCommand(jsonCommand: String) {
        try {
            val command = gson.fromJson(jsonCommand, JsonObject::class.java)
            val action = command.get("action")?.asString

            Timber.d("Handling action: $action")

            if (feedbackSender == null && commandReceiverService?.feedbackSender != null) {
                feedbackSender = commandReceiverService?.feedbackSender
                updateHandlersWithFeedbackSender()
            }

            when (action) {
                "PLAY", "PAUSE", "REQUEST_STATUS", "HEARTBEAT" -> {
                    playbackHandler.handleCommand(jsonCommand)
                }
                "SYNC_PLAYLIST" -> {
                    syncHandler.handleSyncPlaylist(jsonCommand)
                }
                "SET_NAME" -> {
                    val name = command.get("name")?.asString
                    if (!name.isNullOrEmpty()) {
                        sharedPreferences.edit().putString("device_name", name).apply()
                        networkServiceAdvertiser.updateAdvertisement()
                    }
                }
                "SET_MASTER_TIME" -> {
                    val masterTime = command.get("time")?.asLong
                    if (masterTime != null) {
                        timeManager.updateServerTimeOffset(masterTime)
                    }
                }
                else -> Timber.w("Unknown command: $action")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error parsing command")
        }
    }
    
    // ==================== AUTO-RECOVERY (NEW) ====================
    
    /**
     * Periodically check connection status and attempt recovery if disconnected.
     * This helps recover from network issues without manual intervention.
     */
    private fun startAutoRecoveryLoop() {
        handler.postDelayed(autoRecoveryRunnable, AUTO_RECOVERY_INTERVAL_MS)
    }
    
    private val autoRecoveryRunnable = object : Runnable {
        override fun run() {
            try {
                val (status, _, _) = playerManager.getCurrentStatus()
                val isConnected = feedbackSender?.isConnected() ?: false
                val isServerAlive = commandReceiverService?.isServerSocketAlive() ?: false

                if (!isConnected) {
                    Timber.w("Auto-recovery: Disconnected (status=$status, serverAlive=$isServerAlive)")
                    localPlaybackLogger.logConnectionEvent("AUTO_RECOVERY_CHECK", "disconnected, status=$status, serverAlive=$isServerAlive")

                    // Re-register NSD so Controller can rediscover us
                    try {
                        networkServiceAdvertiser.updateAdvertisement()
                        Timber.i("Auto-recovery: NSD re-registered")
                    } catch (e: Exception) {
                        Timber.e(e, "Auto-recovery: NSD re-registration failed")
                    }

                    // If server socket is dead, restart just the service
                    if (!isServerAlive) {
                        try {
                            Timber.w("Auto-recovery: Server socket dead, restarting service...")
                            localPlaybackLogger.logConnectionEvent("AUTO_RECOVERY_RESTART", "server socket dead")
                            stopService(Intent(this@MainActivity, CommandReceiverService::class.java))
                            startAndBindService()
                            Timber.i("Auto-recovery: Service restart initiated")
                        } catch (e: Exception) {
                            Timber.e(e, "Auto-recovery: Failed to restart service")
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Auto-recovery: Error during check")
            }

            // Schedule next check
            handler.postDelayed(this, AUTO_RECOVERY_INTERVAL_MS)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(commandReceiver) } catch (e: Exception) {}
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        playerManager.releasePlayer()
        networkServiceAdvertiser.unregisterService()
        streamingServer.stop()
        wifiReconnectManager.stop()
        localPlaybackLogger.shutdown()
    }
}
