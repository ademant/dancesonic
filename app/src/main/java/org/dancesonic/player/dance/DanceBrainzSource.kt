package org.dancesonic.player.dance

import okhttp3.HttpUrl

/**
 * The DanceBrainz server chosen on the config page. Each lookup goes to the server that is current when it
 * runs, and the source key is that server's address.
 */
class DanceBrainzSource(
    private val currentBaseUrl: () -> HttpUrl,
) : DanceMetadataSource {
    private val lock = Any()
    private var client: Pair<HttpUrl, DanceBrainzClient>? = null

    override val sourceKey: String
        get() = currentBaseUrl().toString()

    override suspend fun lookup(mbids: List<String>): Map<String, RecordingDance> =
        clientFor(
            currentBaseUrl(),
        ).lookup(mbids)

    private fun clientFor(url: HttpUrl): DanceBrainzClient =
        synchronized(lock) {
            val cached = client
            if (cached != null && cached.first == url) return cached.second
            DanceBrainzClient(url).also { client = url to it }
        }
}
