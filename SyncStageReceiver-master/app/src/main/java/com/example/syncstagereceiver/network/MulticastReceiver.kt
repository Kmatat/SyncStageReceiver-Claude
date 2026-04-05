package com.example.syncstagereceiver.network

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.*
import timber.log.Timber
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.util.concurrent.ConcurrentHashMap

class MulticastReceiver(
    private val context: Context,
    private val onMessageReceived: (String) -> Unit
) {
    private var socket: MulticastSocket? = null
    private var multicastGroup: InetAddress? = null
    private var listenJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var multicastLock: WifiManager.MulticastLock? = null

    // Cache to filter duplicate packets (Controller sends 3x copies or Bursts)
    // Key: Command Hash, Value: Timestamp
    private val processedCommands = ConcurrentHashMap<Int, Long>()
    private val DEDUPLICATION_WINDOW_MS = 2000L

    fun start() {
        if (listenJob?.isActive == true) return

        listenJob = scope.launch {
            acquireMulticastLock()
            try {
                // Standard administrative multicast scope
                val group = InetAddress.getByName("239.0.0.1")
                val port = 8888
                socket = MulticastSocket(port)
                socket?.joinGroup(group)
                multicastGroup = group
                socket?.soTimeout = 0 // Infinite timeout to prevent loop churn

                Timber.i("MulticastReceiver listening on 239.0.0.1:$port")

                val buffer = ByteArray(4096)
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet) // Blocks until data arrives

                    val message = String(packet.data, 0, packet.length)
                    handlePacket(message)
                }
            } catch (e: Exception) {
                Timber.e(e, "MulticastReceiver error")
            } finally {
                try { multicastGroup?.let { socket?.leaveGroup(it) } } catch (_: Exception) {}
                socket?.close()
                releaseMulticastLock()
            }
        }
    }

    private fun handlePacket(message: String) {
        val hash = message.hashCode()
        val now = System.currentTimeMillis()

        // Cleanup old cache entries occasionally
        if (processedCommands.size > 100) {
            synchronized(processedCommands) {
                processedCommands.entries.removeIf { now - it.value > DEDUPLICATION_WINDOW_MS }
            }
        }

        // Deduplication Check
        val lastSeen = processedCommands[hash]
        if (lastSeen != null && (now - lastSeen < DEDUPLICATION_WINDOW_MS)) {
            // We saw this exact command recently (it's a redundancy copy), ignore it
            return
        }

        processedCommands[hash] = now
        Timber.v("Multicast received: $message")
        onMessageReceived(message)
    }

    fun stop() {
        listenJob?.cancel()
        try { multicastGroup?.let { socket?.leaveGroup(it) } } catch (_: Exception) {}
        socket?.close()
        releaseMulticastLock()
    }

    private fun acquireMulticastLock() {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("SyncStageReceiverMulticastLock")
        multicastLock?.setReferenceCounted(true)
        multicastLock?.acquire()
    }

    private fun releaseMulticastLock() {
        if (multicastLock?.isHeld == true) {
            multicastLock?.release()
        }
    }
}