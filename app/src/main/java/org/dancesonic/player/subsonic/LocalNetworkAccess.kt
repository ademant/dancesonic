package org.dancesonic.player.subsonic

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Raised before a request to the local network is sent while the app may not use it (#124). Android 17 blocks such
 * connections for apps targeting API 37 until `ACCESS_LOCAL_NETWORK` is granted, and a blocked TCP connection
 * otherwise only times out. The message names no address and is safe to show.
 */
class LocalNetworkBlockedException : IOException(MESSAGE) {
    companion object {
        const val MESSAGE =
            "DanceSonic may not use the local network. Allow \"Nearby devices\" for DanceSonic, then try again."
    }
}

/**
 * The app's check of Android 17's local network permission (#124). Every HTTP client built through
 * [CleartextPolicy.configure] resolves names through [dns], which refuses a local address while the permission is
 * missing, so no API key, password, session or pairing token is sent there. The certificate read of [LanTls] and Studio
 * discovery check [requireAllowed] themselves. Public servers never need the permission.
 */
object LocalNetworkAccess {
    /** Android 17, the first version that has the permission. */
    const val FIRST_SDK = 37

    private const val ULA_MASK = 0xfe
    private const val ULA_PREFIX = 0xfc

    @Volatile
    private var granted: () -> Boolean = { true }

    /** The permissions to ask for on this Android version: none before Android 17. */
    fun required(sdk: Int = Build.VERSION.SDK_INT): Array<String> =
        if (sdk >= FIRST_SDK) arrayOf(Manifest.permission.ACCESS_LOCAL_NETWORK) else emptyArray()

    /** True when every permission [required] lists is granted. */
    fun granted(context: Context): Boolean =
        required().all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Makes [check] the answer to "may the app use the local network now". The app installs it once at start. */
    fun install(check: () -> Boolean) {
        granted = check
    }

    /** Whether the local network may be used now. */
    fun allowed(): Boolean = granted()

    /**
     * Whether [address] is on the local network: a private (RFC 1918), link-local, IPv6 unique-local or multicast
     * address. Loopback is this device and is not.
     */
    fun isLocal(address: InetAddress): Boolean =
        !address.isLoopbackAddress &&
            (
                address.isSiteLocalAddress ||
                    address.isLinkLocalAddress ||
                    address.isMulticastAddress ||
                    (address is Inet6Address && (address.address[0].toInt() and ULA_MASK) == ULA_PREFIX)
            )

    /** Whether [host] is written as a local network address. A name is not resolved here; [dns] checks it. */
    fun isLocalHost(host: String): Boolean = literalAddress(host)?.let { isLocal(it) } ?: false

    /** Whether a connection to [url] needs the permission, as far as its address shows without resolving a name. */
    fun needsPermission(url: HttpUrl): Boolean = isLocalHost(url.host)

    /** Throws [LocalNetworkBlockedException] when [host] is a local address and the permission is missing. */
    fun requireAllowed(host: String) {
        if (isLocalHost(host) && !allowed()) throw LocalNetworkBlockedException()
    }

    /** Throws [LocalNetworkBlockedException] when the permission is missing. For local-only work such as mDNS. */
    fun requireAllowed() {
        if (!allowed()) throw LocalNetworkBlockedException()
    }

    /** Resolves names as [Dns.SYSTEM] does, and refuses a local address while the permission is missing. */
    val dns: Dns =
        object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val addresses = Dns.SYSTEM.lookup(hostname)
                if (addresses.any { isLocal(it) } && !allowed()) throw LocalNetworkBlockedException()
                return addresses
            }
        }

    /**
     * Refuses a request to an address written as a local one. OkHttp does not ask [dns] for an address literal, so
     * [dns] alone would miss `https://192.168.1.5/`. Added as an application and a network interceptor, so a redirect
     * is checked too.
     */
    private val interceptor =
        Interceptor { chain ->
            requireAllowed(chain.request().url.host)
            chain.proceed(chain.request())
        }

    /** Applies the check to [builder]. Part of [CleartextPolicy.configure]. */
    internal fun configure(builder: OkHttpClient.Builder): OkHttpClient.Builder =
        builder.dns(dns).addInterceptor(interceptor).addNetworkInterceptor(interceptor)

    /** The address [host] spells out, or null for a name. A literal is parsed without a lookup. */
    private fun literalAddress(host: String): InetAddress? =
        if (host.contains(':') || IPV4_LITERAL.matches(host)) {
            runCatching { InetAddress.getByName(host) }.getOrNull()
        } else {
            null
        }

    private val IPV4_LITERAL = Regex("""\d{1,3}(\.\d{1,3}){3}""")
}

/** The [LocalNetworkBlockedException] in this exception or its causes, or null. */
internal fun Throwable.localNetworkBlock(): LocalNetworkBlockedException? =
    generateSequence(this) { it.cause }.filterIsInstance<LocalNetworkBlockedException>().firstOrNull()
