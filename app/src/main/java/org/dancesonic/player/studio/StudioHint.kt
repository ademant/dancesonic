package org.dancesonic.player.studio

import okhttp3.HttpUrl

/**
 * Turns the addresses Studio reports in `network_hint` into HTTPS addresses for the network transport. Studio only
 * listens on private and link-local addresses, so anything else is refused here and never dialled. Every address is
 * still checked against Studio's fingerprint before use, as an mDNS address is.
 */
object StudioHint {
    private const val MAX_PORT = 65535
    private const val OCTET_MAX = 255
    private const val TEN = 10
    private const val PRIVATE_172 = 172
    private const val PRIVATE_172_LOW = 16
    private const val PRIVATE_172_HIGH = 31
    private const val PRIVATE_192 = 192
    private const val PRIVATE_192_SECOND = 168
    private const val LINK_LOCAL_FIRST = 169
    private const val LINK_LOCAL_SECOND = 254

    /** fe80::/10 link-local and fc00::/7 unique-local, by their leading hex digits. */
    private val IPV6_PRIVATE_PREFIXES = listOf("fc", "fd", "fe8", "fe9", "fea", "feb")
    private val ipv4 = Regex("""\d+(\.\d+){3}""")
    private val ipv6 = Regex("""[0-9a-fA-F:]+""")

    /** The addresses to try for a Studio on the network: the mDNS one first, then the hinted ones in order. */
    fun candidates(
        found: HttpUrl?,
        addresses: List<String>,
        port: Int?,
    ): List<HttpUrl> = listOfNotNull(found) + urls(addresses, port ?: 0)

    /** The URLs to try for a hint, in the order Studio gave them. Invalid entries are skipped. */
    fun urls(
        addresses: List<String>,
        port: Int,
    ): List<HttpUrl> {
        if (port !in 1..MAX_PORT) return emptyList()
        return addresses.mapNotNull { address ->
            if (!isPrivateAddress(address)) return@mapNotNull null
            HttpUrl.Builder().scheme("https").host(address).port(port).build()
        }
    }

    /** True for an IP literal in a private, link-local or unique-local range. Host names are never accepted. */
    fun isPrivateAddress(address: String): Boolean =
        when {
            ipv4.matches(address) -> privateIpv4(address)
            ipv6.matches(address) && address.contains(':') -> privateIpv6(address)
            else -> false
        }

    private fun privateIpv4(address: String): Boolean {
        val parts = address.split('.').map { it.toIntOrNull() ?: return false }
        if (parts.any { it !in 0..OCTET_MAX }) return false
        val (first, second) = parts
        return first == TEN ||
            (first == LINK_LOCAL_FIRST && second == LINK_LOCAL_SECOND) ||
            (first == PRIVATE_172 && second in PRIVATE_172_LOW..PRIVATE_172_HIGH) ||
            (first == PRIVATE_192 && second == PRIVATE_192_SECOND)
    }

    private fun privateIpv6(address: String): Boolean {
        val lower = address.lowercase()
        return IPV6_PRIVATE_PREFIXES.any { lower.startsWith(it) }
    }
}
