package org.dancesonic.player.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.dancesonic.player.dance.DanceBrainzException
import org.dancesonic.player.dance.DanceBrainzTagsClient
import org.dancesonic.player.dance.MeterValue
import org.dancesonic.player.data.db.DanceSonicDatabase
import org.dancesonic.player.data.db.EditEntity
import org.dancesonic.player.data.db.EditKind

/**
 * The meter of each recording on this phone: what it shows, the meters DanceBrainz offers, and the change to set one.
 * A set meter is queued and sent with the other DanceBrainz changes; [flush] sends the queue.
 */
class MeterEdits internal constructor(
    database: DanceSonicDatabase,
    private val tags: () -> DanceBrainzTagsClient,
    private val flush: suspend () -> Unit,
    private val clock: () -> Long,
) {
    private val lookups = database.danceLookupDao()
    private val queue = database.editQueueDao()

    /** The meter of every looked-up recording, with a queued meter change shown in place of the last lookup. */
    fun observe(): Flow<Map<String, MeterView>> =
        combine(lookups.observeMeters(), queue.observeAll()) { rows, edits ->
            val queued = edits.filter { it.kind == EditKind.METER }.associateBy { it.mbid }
            val fromLookups = rows.associate { row -> row.mbid to MeterView(row.meter, row.subMeter, false, null) }
            fromLookups + queued.mapValues { (_, edit) -> viewOf(edit) }
        }

    /** The meters and sub-meters DanceBrainz offers. Throws [DanceBrainzException] when it cannot be reached. */
    suspend fun options(): List<MeterValue> = tags().meterValues()

    /**
     * Queues setting the meter of [mbid] to [name], which is a sub-meter when [parentName] names its meter, then
     * tries to send it.
     */
    suspend fun set(
        mbid: String,
        name: String,
        parentName: String?,
    ) {
        require(MBID_PATTERN.matches(mbid)) { NOT_RATEABLE }
        queue.replaceQueued(
            EditEntity(
                kind = EditKind.METER,
                mbid = mbid,
                path = name,
                meterParent = parentName,
                queuedAt = clock(),
            ),
        )
        flush()
    }

    private fun viewOf(edit: EditEntity): MeterView {
        val parent = edit.meterParent
        return MeterView(
            meter = parent ?: edit.path,
            subMeter = if (parent != null) edit.path else null,
            pending = true,
            error = edit.lastError,
        )
    }

    private companion object {
        val MBID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        const val NOT_RATEABLE = "Only a recording with a MusicBrainz ID can be changed"
    }
}
