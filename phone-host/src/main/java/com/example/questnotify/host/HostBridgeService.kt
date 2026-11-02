package com.example.questnotify.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.questnotify.shared.DiscoveryBeacon
import com.example.questnotify.shared.NetworkUtils
import com.example.questnotify.shared.PacketType
import com.example.questnotify.shared.ProtocolConstants
import com.example.questnotify.shared.WireMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class HostBridgeService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    private var serverJob: Job? = null

    private data class ActiveClient(
        val id: String,
        val socket: Socket,
        val writer: BufferedWriter
    )

    private val activeClients = ConcurrentHashMap<String, ActiveClient>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopBridgeServer()
            stopSelf()
            return START_NOT_STICKY
        }

        val ip = NetworkUtils.getLocalIpv4Address()
        val notification = buildForegroundNotification(ip)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
        }

        startBridgeServer()
        return START_STICKY
    }

    private fun startBridgeServer() {
        if (serverJob?.isActive == true) return
        val initialIp = NetworkUtils.getLocalIpv4Address()
        HostBridgeRepository.setServerRunning(true, initialIp)

        serverJob = serviceScope.launch {
            launch { runUdpDiscoveryResponder() }
            launch { runUdpBeaconBroadcaster() }
            launch { forwardOutboundNotifications() }
            runTcpServer()
        }
    }

    private suspend fun runUdpBeaconBroadcaster() {
        val socket = try {
            DatagramSocket().apply { broadcast = true }
        } catch (_: Exception) {
            return
        }
        try {
            while (serviceScope.isActive) {
                val ip = NetworkUtils.getLocalIpv4Address()
                HostBridgeRepository.updateLocalIp(ip)
                val beaconJson = DiscoveryBeacon(
                    hostName = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                    hostIp = ip,
                    tcpPort = ProtocolConstants.TCP_STREAM_PORT
                ).toJsonLine()
                val bytes = beaconJson.toByteArray(Charsets.UTF_8)
                for (bcastAddr in NetworkUtils.getBroadcastAddresses()) {
                    try {
                        val packet = DatagramPacket(
                            bytes,
                            bytes.size,
                            bcastAddr,
                            ProtocolConstants.UDP_DISCOVERY_PORT
                        )
                        socket.send(packet)
                    } catch (_: Exception) {
                    }
                }
                delay(2500L)
            }
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun runUdpDiscoveryResponder() {
        try {
            val socket = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(ProtocolConstants.UDP_DISCOVERY_PORT))
            }
            udpSocket = socket
            val buffer = ByteArray(2048)
            while (serviceScope.isActive && !socket.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val raw = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val msg = WireMessage.fromJsonLine(raw)
                if (msg?.type == PacketType.DISCOVERY_PROBE) {
                    val ip = NetworkUtils.getLocalIpv4Address()
                    val beacon = DiscoveryBeacon(
                        hostName = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                        hostIp = ip,
                        tcpPort = ProtocolConstants.TCP_STREAM_PORT
                    ).toJsonLine()
                    val replyBytes = beacon.toByteArray(Charsets.UTF_8)
                    val replyPacket = DatagramPacket(
                        replyBytes,
                        replyBytes.size,
                        packet.address,
                        packet.port
                    )
                    socket.send(replyPacket)
                }
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun runTcpServer() {
        try {
            val server = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(ProtocolConstants.TCP_STREAM_PORT))
            }
            serverSocket = server
            while (serviceScope.isActive && !server.isClosed) {
                val clientSocket = server.accept()
                serviceScope.launch {
                    handleClientConnection(clientSocket)
                }
            }
        } catch (_: Exception) {
        } finally {
            HostBridgeRepository.setServerRunning(false)
        }
    }

    private suspend fun handleClientConnection(socket: Socket) {
        val clientId = UUID.randomUUID().toString()
        val remoteIp = socket.inetAddress?.hostAddress ?: "Unknown"
        try {
            socket.keepAlive = true
            socket.tcpNoDelay = true
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

            val firstLine = reader.readLine() ?: run {
                socket.close()
                return
            }
            val request = WireMessage.fromJsonLine(firstLine)
            if (request == null || request.type != PacketType.PAIR_REQUEST) {
                socket.close()
                return
            }

            val expectedPin = HostBridgeRepository.pairingPin.value
            if (request.pairingPin.trim() != expectedPin) {
                val reject = WireMessage(
                    type = PacketType.PAIR_REJECT,
                    reason = "Invalid pairing PIN. Check the 4-digit PIN on the phone host app."
                )
                sendLineSynchronized(writer, reject.toJsonLine())
                socket.close()
                return
            }

            val ack = WireMessage(
                type = PacketType.PAIR_ACK,
                deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
            )
            sendLineSynchronized(writer, ack.toJsonLine())

            val activeClient = ActiveClient(clientId, socket, writer)
            activeClients[clientId] = activeClient
            HostBridgeRepository.addConnectedClient(
                ConnectedClientInfo(
                    id = clientId,
                    deviceName = request.deviceName.ifEmpty { "Meta Quest 3" },
                    ipAddress = remoteIp
                )
            )

            while (serviceScope.isActive && !socket.isClosed) {
                val line = reader.readLine() ?: break
                val msg = WireMessage.fromJsonLine(line) ?: continue
                when (msg.type) {
                    PacketType.PING -> {
                        val pong = WireMessage(type = PacketType.PONG)
                        sendLineSynchronized(writer, pong.toJsonLine())
                    }
                    PacketType.NOTIFICATION_ACK -> {
                        val key = msg.targetKey.ifEmpty { msg.removedKey }
                        if (key.isNotEmpty()) {
                            handleNotificationAck(key)
                        }
                    }
                    else -> Unit
                }
            }
        } catch (_: Exception) {
        } finally {
            activeClients.remove(clientId)
            HostBridgeRepository.removeConnectedClient(clientId)
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun handleNotificationAck(key: String) {
        val dismissed = PhoneNotificationListenerService.dismissNotificationOnPhone(key)
        if (!dismissed || key.startsWith("test:")) {
            val nm = getSystemService(NotificationManager::class.java)
            val testId = key.removePrefix("test:").toIntOrNull()
            if (testId != null) {
                nm?.cancel(testId)
            }
            nm?.cancel(key.hashCode())
        }
        HostBridgeRepository.onPhoneNotificationRemoved(key)
    }

    private suspend fun forwardOutboundNotifications() {
        HostBridgeRepository.outboundMessages.collect { wireMsg ->
            val jsonLine = wireMsg.toJsonLine()
            val iterator = activeClients.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val client = entry.value
                try {
                    sendLineSynchronized(client.writer, jsonLine)
                } catch (_: Exception) {
                    iterator.remove()
                    HostBridgeRepository.removeConnectedClient(client.id)
                    try {
                        client.socket.close()
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private fun sendLineSynchronized(writer: BufferedWriter, line: String) {
        synchronized(writer) {
            writer.write(line)
            writer.newLine()
            writer.flush()
        }
    }

    private fun stopBridgeServer() {
        serverJob?.cancel()
        try {
            udpSocket?.close()
        } catch (_: Exception) {
        }
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        activeClients.values.forEach {
            try {
                it.socket.close()
            } catch (_: Exception) {
            }
        }
        activeClients.clear()
        HostBridgeRepository.setServerRunning(false)
    }

    override fun onDestroy() {
        stopBridgeServer()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val serviceChannel = NotificationChannel(
            CHANNEL_ID_SERVICE,
            "Quest Notify Bridge Server",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the local Wi-Fi notification bridge server active for Meta Quest 3"
        }
        val testChannel = NotificationChannel(
            CHANNEL_ID_TEST,
            "Test Notifications",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Used to test notification forwarding to Meta Quest 3"
        }
        manager.createNotificationChannel(serviceChannel)
        manager.createNotificationChannel(testChannel)
    }

    private fun buildForegroundNotification(ip: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID_SERVICE)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Quest Notify Bridge Active")
            .setContentText("Listening on $ip:${ProtocolConstants.TCP_STREAM_PORT} (PIN: ${HostBridgeRepository.pairingPin.value})")
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID_SERVICE = "quest_notify_host_service"
        const val CHANNEL_ID_TEST = "quest_notify_host_test"
        const val FOREGROUND_NOTIFICATION_ID = 9001
        const val ACTION_STOP = "com.example.questnotify.host.ACTION_STOP"
    }
}

