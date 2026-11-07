package com.almus.ytdownloader.engine.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.almus.ytdownloader.engine.NetworkKind
import com.almus.ytdownloader.engine.NetworkMonitor
import com.almus.ytdownloader.engine.NetworkStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Follows the device's default network. "Online" means the system has validated that the internet works
 * (a hotspot login page counts as offline). "Metered" means mobile data or another paid connection.
 * Needs the normal ACCESS_NETWORK_STATE permission.
 */
class AndroidNetworkMonitor(context: Context) : NetworkMonitor {
    private val manager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val _status = MutableStateFlow(read(manager.activeNetwork?.let { manager.getNetworkCapabilities(it) }))

    override val status: StateFlow<NetworkStatus> = _status.asStateFlow()

    init {
        try {
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _status.value = read(manager.getNetworkCapabilities(network))
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    _status.value = read(capabilities)
                }

                override fun onLost(network: Network) {
                    _status.value = NetworkStatus(online = false, metered = false)
                }
            })
        } catch (_: SecurityException) {
            // Without the permission the last known status stays; downloads then fail normally if offline.
        }
    }

    private fun read(caps: NetworkCapabilities?): NetworkStatus {
        if (caps == null) return NetworkStatus(online = false, metered = false)
        val online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        // Hints for choosing how many connections a download may use (see ConnectionPolicy).
        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.Ethernet
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.Wifi
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.Cellular
            else -> NetworkKind.Other
        }
        return NetworkStatus(online, metered, kind, caps.linkDownstreamBandwidthKbps.coerceAtLeast(0))
    }
}
