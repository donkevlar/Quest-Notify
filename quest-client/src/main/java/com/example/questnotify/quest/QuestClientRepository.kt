package com.example.questnotify.quest

import android.content.Context
import android.content.SharedPreferences
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
    private const val PREFS_NAME = "quest_notify_client_prefs"
    private const val KEY_SAVED_HOST_IP = "saved_host_ip"
    private const val KEY_SAVED_PIN = "saved_pin"
    private const val KEY_PIN_TIMESTAMP = "saved_pin_timestamp"
    const val PIN_VALIDITY_DURATION_MS = 24 * 60 * 60 * 1000L // 24 hours

    private var prefs: SharedPreferences? = null

    @Volatile
    var userExplicitlyDisconnected = false

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

    private val _mutedPackages = MutableStateFlow<Set<String>>(emptySet())
    val mutedPackages: StateFlow<Set<String>> = _mutedPackages.asStateFlow()

    fun initPreferences(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun savePairedHost(ip: String, pin: String) {
        prefs?.edit()
            ?.putString(KEY_SAVED_HOST_IP, ip)
            ?.putString(KEY_SAVED_PIN, pin)
            ?.putLong(KEY_PIN_TIMESTAMP, System.currentTimeMillis())
            ?.apply()
    }

    fun getValidSavedPairing(): Pair<String, String>? {
        val sp = prefs ?: return null
        val pin = sp.getString(KEY_SAVED_PIN, null) ?: return null
        val ip = sp.getString(KEY_SAVED_HOST_IP, "").orEmpty()
        val timestamp = sp.getLong(KEY_PIN_TIMESTAMP, 0L)
        val now = System.currentTimeMillis()

        return if ((now - timestamp) < PIN_VALIDITY_DURATION_MS) {
            Pair(ip, pin)
        } else {
            clearSavedPairing()
            null
        }
    }

    fun clearSavedPairing() {
        prefs?.edit()
            ?.remove(KEY_SAVED_HOST_IP)
            ?.remove(KEY_SAVED_PIN)
            ?.remove(KEY_PIN_TIMESTAMP)
            ?.apply()
    }

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
        if (_mutedPackages.value.contains(payload.packageName)) return
        val deduplicated = _notifications.value.filterNot { it.key == payload.key }
        _notifications.value = (listOf(payload) + deduplicated).take(100)
    }

    fun mutePackage(packageName: String) {
        _mutedPackages.value = _mutedPackages.value + packageName
        _notifications.value = _notifications.value.filterNot { it.packageName == packageName }
    }

    fun unmutePackage(packageName: String) {
        _mutedPackages.value = _mutedPackages.value - packageName
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

