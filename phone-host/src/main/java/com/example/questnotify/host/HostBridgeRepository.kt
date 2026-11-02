package com.example.questnotify.host

import com.example.questnotify.shared.NotificationPayload
import com.example.questnotify.shared.PacketType
import com.example.questnotify.shared.WireMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

data class ConnectedClientInfo(
    val id: String,
    val deviceName: String,
    val ipAddress: String,
    val connectedAt: Long = System.currentTimeMillis()
)

object HostBridgeRepository {
    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning: StateFlow<Boolean> = _isServerRunning.asStateFlow()

    private val _localIp = MutableStateFlow("0.0.0.0")
    val localIp: StateFlow<String> = _localIp.asStateFlow()

    private val _pairingPin = MutableStateFlow(generatePin())
    val pairingPin: StateFlow<String> = _pairingPin.asStateFlow()

    private val _ignoreOngoing = MutableStateFlow(true)
    val ignoreOngoing: StateFlow<Boolean> = _ignoreOngoing.asStateFlow()

    private val _connectedClients = MutableStateFlow<List<ConnectedClientInfo>>(emptyList())
    val connectedClients: StateFlow<List<ConnectedClientInfo>> = _connectedClients.asStateFlow()

    private val _forwardedHistory = MutableStateFlow<List<NotificationPayload>>(emptyList())
    val forwardedHistory: StateFlow<List<NotificationPayload>> = _forwardedHistory.asStateFlow()

    private val _outboundMessages = MutableSharedFlow<WireMessage>(extraBufferCapacity = 64)
    val outboundMessages: SharedFlow<WireMessage> = _outboundMessages.asSharedFlow()

    fun regeneratePin(): String {
        val newPin = generatePin()
        _pairingPin.value = newPin
        return newPin
    }

    fun setIgnoreOngoing(ignore: Boolean) {
        _ignoreOngoing.value = ignore
    }

    fun setServerRunning(running: Boolean, ip: String = _localIp.value) {
        _isServerRunning.value = running
        _localIp.value = ip
        if (!running) {
            _connectedClients.value = emptyList()
        }
    }

    fun updateLocalIp(ip: String) {
        _localIp.value = ip
    }

    fun addConnectedClient(client: ConnectedClientInfo) {
        _connectedClients.value = _connectedClients.value.filterNot { it.id == client.id } + client
    }

    fun removeConnectedClient(clientId: String) {
        _connectedClients.value = _connectedClients.value.filterNot { it.id == clientId }
    }

    fun onPhoneNotificationPosted(payload: NotificationPayload) {
        if (_ignoreOngoing.value && payload.isOngoing) return
        _forwardedHistory.value = (listOf(payload) + _forwardedHistory.value).take(50)
        _outboundMessages.tryEmit(
            WireMessage(
                type = PacketType.NOTIFICATION_POSTED,
                notification = payload
            )
        )
    }

    fun onPhoneNotificationRemoved(key: String) {
        _outboundMessages.tryEmit(
            WireMessage(
                type = PacketType.NOTIFICATION_REMOVED,
                removedKey = key
            )
        )
    }

    fun clearHistory() {
        _forwardedHistory.value = emptyList()
    }

    private fun generatePin(): String {
        return Random.nextInt(1000, 9999).toString()
    }
}

