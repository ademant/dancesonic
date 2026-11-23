package org.dancesonic.player.playback

import androidx.media3.common.MediaItem

/** One song in the playback queue, as it appears in the media session. */
data class PlayQueueEntry(
    /** The song id, which is also the Media3 media id and the key in the local database. */
    val songId: String,
    /** The title to show, falling back to the song id when the server gave none. */
    val title: String,
    /** The artist to show, when the server gave one. */
    val artist: String?,
)

/** The songs Media3 will play in order, with the index of the song playing now, or -1 when there is none. */
data class PlayQueue(
    val entries: List<PlayQueueEntry> = emptyList(),
    val currentIndex: Int = -1,
)

/** Reads the session's media items as a queue the player screen can show. */
internal fun playQueueOf(
    items: List<MediaItem>,
    currentIndex: Int,
): PlayQueue =
    PlayQueue(
        entries =
            items.map { item ->
                PlayQueueEntry(
                    songId = item.mediaId,
                    title = item.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() } ?: item.mediaId,
                    artist = item.mediaMetadata.artist?.toString()?.takeIf { it.isNotBlank() },
                )
            },
        currentIndex = currentIndex,
    )
