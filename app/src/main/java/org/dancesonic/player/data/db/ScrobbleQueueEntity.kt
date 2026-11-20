package org.dancesonic.player.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A counted play waiting to be reported to folksonic (#89). [playedAt] is when the play started, in epoch
 * milliseconds; it is sent as the scrobble `time`, so a retry counts once on the server. [serverUrl] is the server
 * the play was made on, so it is never sent to another one.
 */
@Entity(tableName = "scrobble_queue", indices = [Index("serverUrl")])
data class ScrobbleQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverUrl: String,
    val songId: String,
    val playedAt: Long,
)
