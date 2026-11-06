package org.dancesonic.player.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Why downloads cannot run now. */
enum class DownloadBlock {
    /** No network at all. */
    Offline,

    /** Only a metered connection, and mobile data is not allowed for downloads. */
    WaitingForWifi,
}

/**
 * Reads the system's network state to decide whether a download may run. Needs ACCESS_NETWORK_STATE. A connection
 * counts as Wi-Fi when the system reports it as not metered, which is also true of wired networks.
 */
class DownloadNetwork(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    /** Null when a download may run, otherwise the reason it may not. */
    fun blockReason(allowMobileData: Boolean): DownloadBlock? {
        val capabilities =
            connectivity.getNetworkCapabilities(connectivity.activeNetwork)
                ?: return DownloadBlock.Offline
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return DownloadBlock.Offline
        val unmetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return if (unmetered || allowMobileData) null else DownloadBlock.WaitingForWifi
    }

    /** Emits when the default network changes, so a paused download can resume. */
    fun changes(): Flow<Unit> =
        callbackFlow {
            val callback =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        trySend(Unit)
                    }

                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities,
                    ) {
                        trySend(Unit)
                    }

                    override fun onLost(network: Network) {
                        trySend(Unit)
                    }
                }
            connectivity.registerDefaultNetworkCallback(callback)
            awaitClose { connectivity.unregisterNetworkCallback(callback) }
        }
}
