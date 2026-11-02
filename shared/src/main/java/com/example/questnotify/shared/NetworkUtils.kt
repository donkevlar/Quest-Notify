package com.example.questnotify.shared

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

object NetworkUtils {
    /**
     * Finds the device's active local Wi-Fi / LAN IPv4 address (e.g., 192.168.1.42).
     */
    fun getLocalIpv4Address(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (!ip.startsWith("169.254.")) {
                            return ip
                        }
                    }
                }
            }
            "127.0.0.1"
        } catch (_: Exception) {
            "127.0.0.1"
        }
    }

    /**
     * Returns all broadcast addresses across active IPv4 network interfaces,
     * plus 255.255.255.255 as fallback.
     */
    fun getBroadcastAddresses(): List<InetAddress> {
        val result = mutableSetOf<InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            if (interfaces != null) {
                for (intf in interfaces) {
                    if (!intf.isUp || intf.isLoopback) continue
                    for (ifaceAddr in intf.interfaceAddresses) {
                        val bcast = ifaceAddr.broadcast
                        if (bcast != null) {
                            result.add(bcast)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        try {
            result.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Exception) {
        }
        return result.toList()
    }
}

