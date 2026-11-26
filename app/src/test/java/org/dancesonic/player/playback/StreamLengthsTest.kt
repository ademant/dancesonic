package org.dancesonic.player.playback

import org.dancesonic.player.data.db.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Every stored track's size bounds its stream, however it was started (#154). */
class StreamLengthsTest {
    private fun track(
        id: String,
        size: Long?,
    ) = TrackEntity(
        id = id,
        albumId = "al",
        artistName = "Ada",
        title = id,
        trackNumber = null,
        durationSec = null,
        mbid = null,
        syncedAt = 0,
        sizeBytes = size,
    )

    @Test
    fun `sizes of the whole library are known without queueing anything`() {
        val lengths = StreamLengths()

        lengths.replaceAll(mapOf("s-1" to 3_000_000L, "s-2" to 4_000_000L))

        assertEquals(3_000_000L, lengths.sizeOf("s-1"))
        assertEquals(4_000_000L, lengths.sizeOf("s-2"))
    }

    @Test
    fun `a song gone from the library loses its size`() {
        val lengths = StreamLengths()
        lengths.replaceAll(mapOf("s-1" to 3_000_000L, "s-2" to 4_000_000L))

        lengths.replaceAll(mapOf("s-2" to 4_000_000L))

        assertNull(lengths.sizeOf("s-1"))
    }

    @Test
    fun `a queued track's size is kept as before`() {
        val lengths = StreamLengths()

        lengths.remember(listOf(track("s-1", 5L), track("s-2", null)))

        assertEquals(5L, lengths.sizeOf("s-1"))
        assertNull(lengths.sizeOf("s-2"))
    }
}
