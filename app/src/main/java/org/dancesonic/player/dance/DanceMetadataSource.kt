package org.dancesonic.player.dance

/** What DanceBrainz says about one recording. */
data class RecordingDance(
    /** Names of the recording's dance tags. Empty when the recording has none. */
    val danceTypes: List<String>,
    /** Public tempo in beats per minute, or null when nobody has reported one. */
    val bpm: Int?,
    /** Number of tempo reports behind [bpm]. */
    val raters: Int?,
)

/**
 * Source of DanceBrainz metadata for recording MusicBrainz IDs.
 *
 * Implementations throw on transport errors, so the caller can count the failure and keep the cached
 * values. A recording the source did not answer for is absent from the result; it is not an error.
 */
interface DanceMetadataSource {
    /** Looks up [mbids] in one request. The caller keeps each request within [MAX_BATCH_SIZE]. */
    suspend fun lookup(mbids: List<String>): Map<String, RecordingDance>

    companion object {
        /** Largest number of recordings one lookup request may carry (the DanceBrainz batch limit). */
        const val MAX_BATCH_SIZE = 500
    }
}

/**
 * Used when no DanceBrainz server is configured. Nothing is answered, so the Dance view stays empty and the
 * Library keeps working.
 */
object NoDanceMetadataSource : DanceMetadataSource {
    override suspend fun lookup(mbids: List<String>): Map<String, RecordingDance> = emptyMap()
}
