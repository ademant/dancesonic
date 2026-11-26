package org.dancesonic.player.studio

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import org.dancesonic.player.subsonic.LocalNetworkAccess
import kotlin.coroutines.resume

/**
 * Finds a paired Studio on the local network (`_dancesonic._tcp`) by the tag of its fingerprint. The phone keeps no
 * address; it looks Studio up each time. The tag only selects an instance: the connection checks the fingerprint.
 */
class StudioDiscovery(private val context: Context) {
    private val resolving = Mutex()

    /**
     * The HTTPS address of the Studio whose tag is [tag], or null when none answers within [timeoutMillis]. Throws
     * [org.dancesonic.player.subsonic.LocalNetworkBlockedException] at once when Android 17 blocks the local network.
     */
    suspend fun find(
        tag: String,
        timeoutMillis: Long = TIMEOUT_MILLIS,
    ): HttpUrl? {
        LocalNetworkAccess.requireAllowed()
        return withTimeoutOrNull(timeoutMillis) {
            services().map { resolve(it) }.first { it.tag == tag && it.url != null }.url
        }
    }

    private data class Found(val tag: String?, val url: HttpUrl?)

    /** Each service found, one at a time: the platform resolves only one name at once. */
    private fun services() =
        callbackFlow {
            val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            val listener =
                object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(serviceType: String) = Unit

                    override fun onServiceFound(info: NsdServiceInfo) {
                        if (info.serviceType.startsWith(SERVICE_TYPE)) trySend(info)
                    }

                    override fun onServiceLost(info: NsdServiceInfo) = Unit

                    override fun onDiscoveryStopped(serviceType: String) = Unit

                    override fun onStartDiscoveryFailed(
                        serviceType: String,
                        errorCode: Int,
                    ) {
                        close()
                    }

                    override fun onStopDiscoveryFailed(
                        serviceType: String,
                        errorCode: Int,
                    ) = Unit
                }
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
        }

    /** Resolves one service to its tag and address. Failures give no address and are skipped by the caller. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // a service that fails to resolve is skipped
    private suspend fun resolve(info: NsdServiceInfo): Found =
        resolving.withLock {
            try {
                resolveOnce(info)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Found(null, null)
            }
        }

    @Suppress("DEPRECATION")
    private suspend fun resolveOnce(info: NsdServiceInfo): Found {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        return suspendCancellableCoroutine { cont ->
            nsd.resolveService(
                info,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(
                        serviceInfo: NsdServiceInfo,
                        errorCode: Int,
                    ) {
                        cont.resume(Found(null, null))
                    }

                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val tag = resolved.attributes["fp"]?.toString(Charsets.UTF_8)
                        val host = resolved.host?.hostAddress?.substringBefore('%')
                        val url = host?.let { HttpUrl.Builder().scheme("https").host(it).port(resolved.port).build() }
                        cont.resume(Found(tag, url))
                    }
                },
            )
        }
    }

    companion object {
        const val SERVICE_TYPE = "_dancesonic._tcp"
        const val TIMEOUT_MILLIS = 8_000L
    }
}
