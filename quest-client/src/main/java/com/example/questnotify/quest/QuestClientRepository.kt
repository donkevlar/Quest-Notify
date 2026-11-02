package com.example.questnotify.quest

import com.example.questnotify.shared.DiscoveryBeacon
import com.example.questnotify.shared.NotificationPayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class QuestConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

object QuestClientRepository {
    private val _connectionState = MutableStateFlow(QuestConnectionState.DISCONNECTED)
    val connectionState: StateFlow<QuestConnectionState> = _connectionState.asStateFlow()

    private val _statusMessage = MutableStateFlow("Not connected — select your phone or enter IP & PIN")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _connectedPhoneName = MutableStateFlow("")
    val connectedPhoneName: StateFlow<String> = _connectedPhoneName.asStateFlow()

    private val _discoveredPhones = MutableStateFlow<List<DiscoveryBeacon>>(emptyList())
    val discoveredPhones: StateFlow<List<DiscoveryBeacon>> = _discoveredPhones.asStateFlow()

    private val _notifications = MutableStateFlow<List<NotificationPayload>>(emptyList())
    val notifications: StateFlow<List<NotificationPayload>> = _notifications.asStateFlow()

    private val _showHeadsUpToasts = MutableStateFlow(true)
    val showHeadsUpToasts: StateFlow<Boolean> = _showHeadsUpToasts.asStateFlow()

    private val _speakNotifications = MutableStateFlow(false)
    val speakNotifications: StateFlow<Boolean> = _speakNotifications.asStateFlow()

    fun updateConnectionState(
        state: QuestConnectionState,
        message: String,
        phoneName: String = _connectedPhoneName.value
    ) {
        _connectionState.value = state
        _statusMessage.value = message
        _connectedPhoneName.value = if (state == QuestConnectionState.CONNECTED) phoneName else ""
    }

    fun onBeaconDiscovered(beacon: DiscoveryBeacon) {
        if (beacon.hostIp.isEmpty()) return
        val existing = _discoveredPhones.value.filterNot { it.hostIp == beacon.hostIp }
        _discoveredPhones.value = listOf(beacon) + existing
    }

    fun onNotificationReceived(payload: NotificationPayload) {
        val deduplicated = _notifications.value.filterNot { it.key == payload.key }
        _notifications.value = (listOf(payload) + deduplicated).take(100)
    }

    fun onNotificationRemoved(key: String) {
        _notifications.value = _notifications.value.filterNot { it.key == key }
    }

    fun clearNotifications() {
        _notifications.value = emptyList()
    }

    fun setShowHeadsUpToasts(enabled: Boolean) {
        _showHeadsUpToasts.value = enabled
    }

    fun setSpeakNotifications(enabled: Boolean) {
        _speakNotifications.value = enabled
    }
}

