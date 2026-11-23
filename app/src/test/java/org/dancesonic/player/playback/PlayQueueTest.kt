package org.dancesonic.player.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayQueueTest {
    @Test
    fun `queue keeps the session order and marks the current song`() {
        val queue =
            playQueueOf(
                items =
                    listOf(
                        item("s-1", "First", "Ada"),
                        item("s-2", "Second", "Ada"),
                        item("s-3", "Third", "Bea"),
                    ),
                currentIndex = 1,
            )

        assertEquals(3, queue.entries.size)
        assertEquals(1, queue.currentIndex)
        assertEquals("s-1", queue.entries[0].songId)
        assertEquals("Second", queue.entries[1].title)
        assertEquals("Bea", queue.entries[2].artist)
    }

    @Test
    fun `a song without metadata is shown by its id`() {
        val queue = playQueueOf(items = listOf(item("s-9", title = null, artist = null)), currentIndex = 0)

        assertEquals("s-9", queue.entries.single().title)
        assertEquals(null, queue.entries.single().artist)
    }

    private fun item(
        id: String,
        title: String?,
        artist: String?,
    ): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(title).setArtist(artist).build(),
            )
            .build()
}
