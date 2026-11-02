package com.example.questnotify.shared

import org.json.JSONObject

object ProtocolConstants {
    const val MAGIC_HEADER = "QNOTIFY_V1"
    const val UDP_DISCOVERY_PORT = 48210
    const val TCP_STREAM_PORT = 48211
}

enum class PacketType {
    DISCOVERY_BEACON,
    DISCOVERY_PROBE,
    PAIR_REQUEST,
    PAIR_ACK,
    PAIR_REJECT,
    NOTIFICATION_POSTED,
    NOTIFICATION_REMOVED,
    PING,
    PONG
}

data class DiscoveryBeacon(
    val hostName: String,
    val hostIp: String,
    val tcpPort: Int = ProtocolConstants.TCP_STREAM_PORT
) {
    fun toJsonLine(): String {
        val obj = JSONObject()
        obj.put("magic", ProtocolConstants.MAGIC_HEADER)
        obj.put("type", PacketType.DISCOVERY_BEACON.name)
        obj.put("hostName", hostName)
        obj.put("hostIp", hostIp)
        obj.put("tcpPort", tcpPort)
        return obj.toString()
    }

    companion object {
        fun fromJson(raw: String): DiscoveryBeacon? {
            return try {
                val obj = JSONObject(raw.trim())
                if (obj.optString("magic") != ProtocolConstants.MAGIC_HEADER) return null
                if (obj.optString("type") != PacketType.DISCOVERY_BEACON.name) return null
                DiscoveryBeacon(
                    hostName = obj.optString("hostName", "Android Phone"),
                    hostIp = obj.optString("hostIp", ""),
                    tcpPort = obj.optInt("tcpPort", ProtocolConstants.TCP_STREAM_PORT)
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

data class NotificationPayload(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val subText: String = "",
    val category: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val isOngoing: Boolean = false,
    val iconBase64: String = ""
) {
    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("key", key)
            put("packageName", packageName)
            put("appName", appName)
            put("title", title)
            put("text", text)
            put("subText", subText)
            put("category", category)
            put("timestamp", timestamp)
            put("isOngoing", isOngoing)
            if (iconBase64.isNotEmpty()) put("iconBase64", iconBase64)
        }
    }

    companion object {
        fun fromJsonObject(obj: JSONObject): NotificationPayload {
            return NotificationPayload(
                key = obj.optString("key", ""),
                packageName = obj.optString("packageName", ""),
                appName = obj.optString("appName", "App"),
                title = obj.optString("title", ""),
                text = obj.optString("text", ""),
                subText = obj.optString("subText", ""),
                category = obj.optString("category", ""),
                timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                isOngoing = obj.optBoolean("isOngoing", false),
                iconBase64 = obj.optString("iconBase64", "")
            )
        }
    }
}

data class WireMessage(
    val type: PacketType,
    val deviceName: String = "",
    val pairingPin: String = "",
    val notification: NotificationPayload? = null,
    val removedKey: String = "",
    val reason: String = ""
) {
    fun toJsonLine(): String {
        val obj = JSONObject()
        obj.put("magic", ProtocolConstants.MAGIC_HEADER)
        obj.put("type", type.name)
        if (deviceName.isNotEmpty()) obj.put("deviceName", deviceName)
        if (pairingPin.isNotEmpty()) obj.put("pairingPin", pairingPin)
        if (notification != null) obj.put("notification", notification.toJsonObject())
        if (removedKey.isNotEmpty()) obj.put("removedKey", removedKey)
        if (reason.isNotEmpty()) obj.put("reason", reason)
        return obj.toString()
    }

    companion object {
        fun fromJsonLine(line: String): WireMessage? {
            return try {
                val obj = JSONObject(line.trim())
                if (obj.optString("magic") != ProtocolConstants.MAGIC_HEADER) return null
                val type = PacketType.valueOf(obj.getString("type"))
                val notifObj = obj.optJSONObject("notification")
                WireMessage(
                    type = type,
                    deviceName = obj.optString("deviceName", ""),
                    pairingPin = obj.optString("pairingPin", ""),
                    notification = notifObj?.let { NotificationPayload.fromJsonObject(it) },
                    removedKey = obj.optString("removedKey", ""),
                    reason = obj.optString("reason", "")
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

