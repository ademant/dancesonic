package org.dancesonic.player.dance

/**
 * Source of DanceBrainz dance types for a recording MusicBrainz ID.
 *
 * Implementations must throw on transport errors (so the caller can count the failure and keep the
 * cached value) and return null when the source has no information about the recording.
 */
interface DanceMetadataSource {
    suspend fun danceTypesFor(mbid: String): List<String>?
}

/**
 * Used until a DanceBrainz client is configured. Every recording is reported as "no information",
 * so the Dance view stays empty and the Library keeps working.
 */
object NoDanceMetadataSource : DanceMetadataSource {
    override suspend fun danceTypesFor(mbid: String): List<String>? = null
}
