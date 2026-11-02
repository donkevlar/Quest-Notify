package com.example.questnotify.quest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.Base64
import androidx.core.app.NotificationCompat
import com.example.questnotify.shared.DiscoveryBeacon
import com.example.questnotify.shared.NetworkUtils
import com.example.questnotify.shared.NotificationPayload
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
import java.net.Socket
import java.util.Locale

class QuestReceiverService : Service(), TextToSpeech.OnInitListener {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var discoveryJob: Job? = null
    private var connectionJob: Job? = null
    private var activeSocket: Socket? = null
    private var activeWriter: BufferedWriter? = null
    private var udpSocket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        tts = TextToSpeech(this, this)
        acquireMulticastLock()
        startUdpDiscoveryListener()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
            isTtsReady = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                disconnectFromHost(userInitiated = true)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_SCAN_LAN -> {
                ensureForeground()
                sendDiscoveryProbe()
                return START_STICKY
            }

            ACTION_CONNECT -> {
                val hostIp = intent.getStringExtra(EXTRA_HOST_IP).orEmpty().trim()
                val pin = intent.getStringExtra(EXTRA_PAIRING_PIN).orEmpty().trim()
                ensureForeground()
                if (hostIp.isNotEmpty() && pin.isNotEmpty()) {
                    connectToPhoneHost(hostIp, pin)
                }
                return START_STICKY
            }

            ACTION_ACKNOWLEDGE_NOTIFICATION -> {
                val key = intent.getStringExtra(EXTRA_NOTIFICATION_KEY).orEmpty()
                if (key.isNotEmpty()) {
                    acknowledgeNotificationOnPhone(key)
                }
                return START_STICKY
            }
        }

        ensureForeground()
        sendDiscoveryProbe()
        return START_STICKY
    }

    private fun ensureForeground() {
        val notif = buildForegroundServiceNotification("Listening for phone notifications in VR")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                FOREGROUND_SERVICE_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(FOREGROUND_SERVICE_ID, notif)
        }
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("QuestNotifyMulticastLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
    }

    private fun startUdpDiscoveryListener() {
        if (discoveryJob?.isActive == true) return
        discoveryJob = serviceScope.launch {
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
                    val beacon = DiscoveryBeacon.fromJson(raw)
                    if (beacon != null) {
                        val resolvedIp = beacon.hostIp.ifEmpty {
                            packet.address?.hostAddress.orEmpty()
                        }
                        QuestClientRepository.onBeaconDiscovered(
                            beacon.copy(hostIp = resolvedIp)
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun sendDiscoveryProbe() {
        serviceScope.launch {
            try {
                val probeSocket = DatagramSocket().apply { broadcast = true }
                val probeJson = WireMessage(
                    type = PacketType.DISCOVERY_PROBE,
                    deviceName = "Meta Quest 3"
                ).toJsonLine()
                val bytes = probeJson.toByteArray(Charsets.UTF_8)
                for (bcast in NetworkUtils.getBroadcastAddresses()) {
                    try {
                        val packet = DatagramPacket(
                            bytes,
                            bytes.size,
                            bcast,
                            ProtocolConstants.UDP_DISCOVERY_PORT
                        )
                        probeSocket.send(packet)
                    } catch (_: Exception) {
                    }
                }
                probeSocket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun connectToPhoneHost(hostIp: String, pairingPin: String) {
        connectionJob?.cancel()
        try {
            activeSocket?.close()
        } catch (_: Exception) {
        }

        connectionJob = serviceScope.launch {
            var shouldReconnect = true
            while (serviceScope.isActive && shouldReconnect) {
                QuestClientRepository.updateConnectionState(
                    QuestConnectionState.CONNECTING,
                    "Connecting to $hostIp:${ProtocolConstants.TCP_STREAM_PORT}..."
                )
                try {
                    val socket = Socket()
                    activeSocket = socket
                    socket.connect(
                        InetSocketAddress(hostIp, ProtocolConstants.TCP_STREAM_PORT),
                        5000
                    )
                    socket.keepAlive = true
                    socket.tcpNoDelay = true

                    val reader = BufferedReader(
                        InputStreamReader(socket.getInputStream(), Charsets.UTF_8)
                    )
                    val writer = BufferedWriter(
                        OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
                    )

                    val pairReq = WireMessage(
                        type = PacketType.PAIR_REQUEST,
                        deviceName = "Meta Quest 3 (${Build.MODEL})",
                        pairingPin = pairingPin
                    )
                    writer.write(pairReq.toJsonLine())
                    writer.newLine()
                    writer.flush()

                    val handshakeLine = reader.readLine()
                    val handshakeMsg = handshakeLine?.let { WireMessage.fromJsonLine(it) }

                    if (handshakeMsg == null) {
                        QuestClientRepository.updateConnectionState(
                            QuestConnectionState.ERROR,
                            "Connection closed by phone host during handshake."
                        )
                        shouldReconnect = false
                        socket.close()
                        break
                    }

                    if (handshakeMsg.type == PacketType.PAIR_REJECT) {
                        QuestClientRepository.updateConnectionState(
                            QuestConnectionState.ERROR,
                            handshakeMsg.reason.ifEmpty { "Pairing rejected: Invalid PIN" }
                        )
                        shouldReconnect = false
                        socket.close()
                        break
                    }

                    if (handshakeMsg.type == PacketType.PAIR_ACK) {
                        activeWriter = writer
                        val phoneName = handshakeMsg.deviceName.ifEmpty { hostIp }
                        QuestClientRepository.updateConnectionState(
                            QuestConnectionState.CONNECTED,
                            "Paired with $phoneName ($hostIp)",
                            phoneName = phoneName
                        )
                        updateForegroundText("Connected to $phoneName")

                        val heartbeatJob = launch {
                            while (isActive && !socket.isClosed) {
                                delay(15_000L)
                                try {
                                    synchronized(writer) {
                                        writer.write(WireMessage(type = PacketType.PING).toJsonLine())
                                        writer.newLine()
                                        writer.flush()
                                    }
                                } catch (_: Exception) {
                                    break
                                }
                            }
                        }

                        try {
                            while (isActive && !socket.isClosed) {
                                val line = reader.readLine() ?: break
                                val msg = WireMessage.fromJsonLine(line) ?: continue
                                when (msg.type) {
                                    PacketType.NOTIFICATION_POSTED -> {
                                        msg.notification?.let { handleIncomingNotification(it) }
                                    }

                                    PacketType.NOTIFICATION_REMOVED -> {
                                        if (msg.removedKey.isNotEmpty()) {
                                            handleRemovedNotification(msg.removedKey)
                                        }
                                    }

                                    else -> Unit
                                }
                            }
                        } finally {
                            heartbeatJob.cancel()
                        }
                    }
                } catch (e: Exception) {
                    QuestClientRepository.updateConnectionState(
                        QuestConnectionState.CONNECTING,
                        "Connection lost (${e.localizedMessage ?: "error"}). Retrying in 4s..."
                    )
                } finally {
                    activeWriter = null
                    try {
                        activeSocket?.close()
                    } catch (_: Exception) {
                    }
                }

                if (shouldReconnect && serviceScope.isActive) {
                    delay(4000L)
                }
            }
        }
    }

    private fun handleIncomingNotification(payload: NotificationPayload) {
        if (QuestClientRepository.mutedPackages.value.contains(payload.packageName)) {
            return
        }
        QuestClientRepository.onNotificationReceived(payload)

        if (QuestClientRepository.showHeadsUpToasts.value) {
            postQuestHeadsUpNotification(payload)
        }

        if (QuestClientRepository.speakNotifications.value && isTtsReady) {
            val spokenText = "${payload.appName}: ${payload.title}. ${payload.text}"
            tts?.speak(spokenText, TextToSpeech.QUEUE_ADD, null, payload.key)
        }
    }

    private fun handleRemovedNotification(key: String) {
        QuestClientRepository.onNotificationRemoved(key)
        val nm = getSystemService(NotificationManager::class.java)
        nm?.cancel(key.hashCode())
    }

    private fun acknowledgeNotificationOnPhone(key: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.cancel(key.hashCode())
        QuestClientRepository.onNotificationRemoved(key)

        serviceScope.launch {
            try {
                val writer = activeWriter ?: return@launch
                val ackMsg = WireMessage(
                    type = PacketType.NOTIFICATION_ACK,
                    targetKey = key
                )
                synchronized(writer) {
                    writer.write(ackMsg.toJsonLine())
                    writer.newLine()
                    writer.flush()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun postQuestHeadsUpNotification(payload: NotificationPayload) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val openIntent = Intent(this, QuestMainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this,
            payload.key.hashCode(),
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val ackIntent = Intent(this, QuestReceiverService::class.java).apply {
            action = ACTION_ACKNOWLEDGE_NOTIFICATION
            putExtra(EXTRA_NOTIFICATION_KEY, payload.key)
        }
        val pendingAck = PendingIntent.getService(
            this,
            payload.key.hashCode() + 1000,
            ackIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID_VR_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setSubText(payload.appName)
            .setContentTitle(payload.title)
            .setContentText(payload.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(payload.text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingOpen)
            .addAction(
                android.R.drawable.checkbox_on_background,
                "Mark as Read",
                pendingAck
            )

        if (payload.iconBase64.isNotBlank()) {
            try {
                val bytes = Base64.decode(payload.iconBase64, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) {
                    builder.setLargeIcon(bitmap)
                }
            } catch (_: Exception) {
            }
        }

        nm.notify(payload.key.hashCode(), builder.build())
    }

    private fun disconnectFromHost(userInitiated: Boolean) {
        connectionJob?.cancel()
        try {
            activeSocket?.close()
        } catch (_: Exception) {
        }
        if (userInitiated) {
            QuestClientRepository.updateConnectionState(
                QuestConnectionState.DISCONNECTED,
                "Disconnected"
            )
        }
    }

    override fun onDestroy() {
        disconnectFromHost(userInitiated = false)
        discoveryJob?.cancel()
        try {
            udpSocket?.close()
        } catch (_: Exception) {
        }
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {
        }
        tts?.stop()
        tts?.shutdown()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val serviceChannel = NotificationChannel(
            CHANNEL_ID_SERVICE,
            "Quest Notify Background Receiver",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the Meta Quest 3 connected to your phone while in VR"
        }
        val vrAlertChannel = NotificationChannel(
            CHANNEL_ID_VR_ALERTS,
            "Phone Notifications in VR",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Displays floating VR heads-up alerts for incoming phone notifications"
            enableVibration(true)
        }
        nm.createNotificationChannel(serviceChannel)
        nm.createNotificationChannel(vrAlertChannel)
    }

    private fun updateForegroundText(statusText: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(FOREGROUND_SERVICE_ID, buildForegroundServiceNotification(statusText))
    }

    private fun buildForegroundServiceNotification(statusText: String): Notification {
        val openIntent = Intent(this, QuestMainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID_SERVICE)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Quest Notify VR Receiver")
            .setContentText(statusText)
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID_SERVICE = "quest_notify_vr_service"
        const val CHANNEL_ID_VR_ALERTS = "quest_notify_vr_alerts"
        const val FOREGROUND_SERVICE_ID = 9101

        const val ACTION_CONNECT = "com.example.questnotify.quest.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.example.questnotify.quest.ACTION_DISCONNECT"
        const val ACTION_SCAN_LAN = "com.example.questnotify.quest.ACTION_SCAN_LAN"
        const val ACTION_ACKNOWLEDGE_NOTIFICATION = "com.example.questnotify.quest.ACTION_ACKNOWLEDGE_NOTIFICATION"
        const val EXTRA_HOST_IP = "extra_host_ip"
        const val EXTRA_PAIRING_PIN = "extra_pairing_pin"
        const val EXTRA_NOTIFICATION_KEY = "extra_notification_key"

        fun acknowledgeNotification(context: Context, key: String) {
            val intent = Intent(context, QuestReceiverService::class.java).apply {
                action = ACTION_ACKNOWLEDGE_NOTIFICATION
                putExtra(EXTRA_NOTIFICATION_KEY, key)
            }
            context.startService(intent)
        }
    }
}

