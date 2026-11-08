package com.watchlink.phone

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    /** 当前设备的局域网 IPv4 地址，用于在界面上提示用户。 */
    fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()
}
