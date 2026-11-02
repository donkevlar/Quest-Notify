package com.example.questnotify.host

import android.content.Context
import android.content.SharedPreferences
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

enum class AppFilterMode {
    ALL_APPS,           // Forward from all apps, except those explicitly unchecked (blocklist)
    SELECTED_APPS_ONLY  // Forward only from apps that are explicitly checked (allowlist)
}

object HostBridgeRepository {
    private const val PREFS_NAME = "quest_notify_host_prefs"
    private const val KEY_FILTER_MODE = "filter_mode"
    private const val KEY_BLOCKED_PACKAGES = "blocked_packages"
    private const val KEY_ALLOWED_PACKAGES = "allowed_packages"
    private const val KEY_KNOWN_PACKAGES = "known_packages"
    private const val KEY_IGNORE_ONGOING = "ignore_ongoing"
    private const val KEY_PAIRING_PIN = "pairing_pin"
    private const val KEY_PIN_TIMESTAMP = "pin_timestamp"
    private const val KEY_WEB_VIEW_ENABLED = "web_view_enabled"
    const val PIN_VALIDITY_DURATION_MS = 24 * 60 * 60 * 1000L // 24 hours

    private var prefs: SharedPreferences? = null

    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning: StateFlow<Boolean> = _isServerRunning.asStateFlow()

    private val _localIp = MutableStateFlow("0.0.0.0")
    val localIp: StateFlow<String> = _localIp.asStateFlow()

    private val _pairingPin = MutableStateFlow(generatePin())
    val pairingPin: StateFlow<String> = _pairingPin.asStateFlow()

    private val _ignoreOngoing = MutableStateFlow(true)
    val ignoreOngoing: StateFlow<Boolean> = _ignoreOngoing.asStateFlow()

    private val _isWebViewEnabled = MutableStateFlow(false)
    val isWebViewEnabled: StateFlow<Boolean> = _isWebViewEnabled.asStateFlow()

    private val _filterMode = MutableStateFlow(AppFilterMode.ALL_APPS)
    val filterMode: StateFlow<AppFilterMode> = _filterMode.asStateFlow()

    private val _blockedPackages = MutableStateFlow<Set<String>>(emptySet())
    val blockedPackages: StateFlow<Set<String>> = _blockedPackages.asStateFlow()

    private val _allowedPackages = MutableStateFlow<Set<String>>(emptySet())
    val allowedPackages: StateFlow<Set<String>> = _allowedPackages.asStateFlow()

    private val _knownPackages = MutableStateFlow<Set<String>>(emptySet())
    val knownPackages: StateFlow<Set<String>> = _knownPackages.asStateFlow()

    private val _connectedClients = MutableStateFlow<List<ConnectedClientInfo>>(emptyList())
    val connectedClients: StateFlow<List<ConnectedClientInfo>> = _connectedClients.asStateFlow()

    private val _forwardedHistory = MutableStateFlow<List<NotificationPayload>>(emptyList())
    val forwardedHistory: StateFlow<List<NotificationPayload>> = _forwardedHistory.asStateFlow()

    private val _outboundMessages = MutableSharedFlow<WireMessage>(extraBufferCapacity = 64)
    val outboundMessages: SharedFlow<WireMessage> = _outboundMessages.asSharedFlow()

    fun initPreferences(context: Context) {
        if (prefs != null) return
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp

        val savedMode = sp.getString(KEY_FILTER_MODE, AppFilterMode.ALL_APPS.name)
        _filterMode.value = try {
            AppFilterMode.valueOf(savedMode ?: AppFilterMode.ALL_APPS.name)
        } catch (_: Exception) {
            AppFilterMode.ALL_APPS
        }

        _blockedPackages.value = sp.getStringSet(KEY_BLOCKED_PACKAGES, emptySet())?.toSet() ?: emptySet()
        _allowedPackages.value = sp.getStringSet(KEY_ALLOWED_PACKAGES, emptySet())?.toSet() ?: emptySet()
        _knownPackages.value = sp.getStringSet(KEY_KNOWN_PACKAGES, emptySet())?.toSet() ?: emptySet()
        _ignoreOngoing.value = sp.getBoolean(KEY_IGNORE_ONGOING, true)
        _isWebViewEnabled.value = sp.getBoolean(KEY_WEB_VIEW_ENABLED, false)

        val savedPin = sp.getString(KEY_PAIRING_PIN, null)
        val pinTime = sp.getLong(KEY_PIN_TIMESTAMP, 0L)
        val now = System.currentTimeMillis()

        if (savedPin != null && (now - pinTime) < PIN_VALIDITY_DURATION_MS) {
            _pairingPin.value = savedPin
        } else {
            val newPin = generatePin()
            _pairingPin.value = newPin
            sp.edit()
                .putString(KEY_PAIRING_PIN, newPin)
                .putLong(KEY_PIN_TIMESTAMP, now)
                .apply()
        }
    }

    fun setFilterMode(mode: AppFilterMode) {
        _filterMode.value = mode
        prefs?.edit()?.putString(KEY_FILTER_MODE, mode.name)?.apply()
    }

    fun setWebViewEnabled(enabled: Boolean) {
        _isWebViewEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_WEB_VIEW_ENABLED, enabled)?.apply()
    }

    fun isPackageAllowed(packageName: String): Boolean {
        // Always allow test notifications from our own app
        if (packageName == "com.example.questnotify.host") return true

        return when (_filterMode.value) {
            AppFilterMode.ALL_APPS -> {
                !_blockedPackages.value.contains(packageName)
            }
            AppFilterMode.SELECTED_APPS_ONLY -> {
                _allowedPackages.value.contains(packageName)
            }
        }
    }

    fun setAppAllowed(packageName: String, allowed: Boolean) {
        when (_filterMode.value) {
            AppFilterMode.ALL_APPS -> {
                val updated = if (allowed) {
                    _blockedPackages.value - packageName
                } else {
                    _blockedPackages.value + packageName
                }
                _blockedPackages.value = updated
                prefs?.edit()?.putStringSet(KEY_BLOCKED_PACKAGES, updated)?.apply()
            }
            AppFilterMode.SELECTED_APPS_ONLY -> {
                val updated = if (allowed) {
                    _allowedPackages.value + packageName
                } else {
                    _allowedPackages.value - packageName
                }
                _allowedPackages.value = updated
                prefs?.edit()?.putStringSet(KEY_ALLOWED_PACKAGES, updated)?.apply()
            }
        }
        recordPackageSeen(packageName)
    }

    fun setAllAppsAllowed(packageNames: Collection<String>, allowed: Boolean) {
        when (_filterMode.value) {
            AppFilterMode.ALL_APPS -> {
                val updated = if (allowed) {
                    _blockedPackages.value - packageNames.toSet()
                } else {
                    _blockedPackages.value + packageNames
                }
                _blockedPackages.value = updated
                prefs?.edit()?.putStringSet(KEY_BLOCKED_PACKAGES, updated)?.apply()
            }
            AppFilterMode.SELECTED_APPS_ONLY -> {
                val updated = if (allowed) {
                    _allowedPackages.value + packageNames
                } else {
                    _allowedPackages.value - packageNames.toSet()
                }
                _allowedPackages.value = updated
                prefs?.edit()?.putStringSet(KEY_ALLOWED_PACKAGES, updated)?.apply()
            }
        }
    }

    fun recordPackageSeen(packageName: String) {
        if (!_knownPackages.value.contains(packageName)) {
            val updated = _knownPackages.value + packageName
            _knownPackages.value = updated
            prefs?.edit()?.putStringSet(KEY_KNOWN_PACKAGES, updated)?.apply()
        }
    }

    fun regeneratePin(): String {
        val newPin = generatePin()
        _pairingPin.value = newPin
        prefs?.edit()
            ?.putString(KEY_PAIRING_PIN, newPin)
            ?.putLong(KEY_PIN_TIMESTAMP, System.currentTimeMillis())
            ?.apply()
        return newPin
    }

    fun getValidPairingPin(): String {
        val sp = prefs
        if (sp != null) {
            val pinTime = sp.getLong(KEY_PIN_TIMESTAMP, 0L)
            val now = System.currentTimeMillis()
            if (now - pinTime >= PIN_VALIDITY_DURATION_MS) {
                return regeneratePin()
            }
        }
        return _pairingPin.value
    }

    fun isPinValid(pin: String): Boolean {
        val current = getValidPairingPin()
        return pin.trim() == current.trim()
    }

    fun setIgnoreOngoing(ignore: Boolean) {
        _ignoreOngoing.value = ignore
        prefs?.edit()?.putBoolean(KEY_IGNORE_ONGOING, ignore)?.apply()
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
        recordPackageSeen(payload.packageName)
        if (_ignoreOngoing.value && payload.isOngoing) return
        if (!isPackageAllowed(payload.packageName)) return

        _forwardedHistory.value = (listOf(payload) + _forwardedHistory.value).take(50)
        _outboundMessages.tryEmit(
            WireMessage(
                type = PacketType.NOTIFICATION_POSTED,
                notification = payload
            )
        )
    }

    fun onPhoneNotificationRemoved(key: String) {
        _forwardedHistory.value = _forwardedHistory.value.filterNot { it.key == key }
        _outboundMessages.tryEmit(
            WireMessage(
                type = PacketType.NOTIFICATION_REMOVED,
                removedKey = key,
                targetKey = key
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

