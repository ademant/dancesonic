package org.dancesonic.player.analysis

import android.media.MediaDataSource
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException

/**
 * Presents a cached track to MediaExtractor as random-access bytes. Each read opens the cache at the position asked
 * for, through [reader], which must be cache-only (see [org.dancesonic.player.playback.AudioCache.cacheOnlyFactory]).
 * A read the cache cannot serve ends the track and sets [failed], so the caller drops the result.
 */
internal class CachedMediaDataSource(
    private val reader: DataSource.Factory,
    private val uri: Uri,
    private val key: String,
    private val length: Long,
) : MediaDataSource() {
    /** True once a read failed. The bytes read before the failure are not a whole track. */
    @Volatile
    var failed: Boolean = false
        private set

    override fun getSize(): Long = length

    override fun readAt(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        size: Int,
    ): Int {
        if (position >= length) return -1
        val wanted = minOf(size.toLong(), length - position).toInt()
        val source = reader.createDataSource()
        return try {
            source.open(
                DataSpec.Builder()
                    .setUri(uri)
                    .setKey(key)
                    .setPosition(position)
                    .setLength(length - position)
                    .build(),
            )
            var total = 0
            while (total < wanted) {
                val read = source.read(buffer, offset + total, wanted - total)
                if (read == C.RESULT_END_OF_INPUT) break
                total += read
            }
            if (total == 0) -1 else total
        } catch (
            @Suppress("SwallowedException") e: IOException,
        ) {
            // Not cached (or the cache failed). MediaExtractor sees the end; the caller checks [failed].
            failed = true
            -1
        } finally {
            closeQuietly(source)
        }
    }

    override fun close() = Unit

    /** A failed close means the cache could not release the read; the result is then not trusted. */
    private fun closeQuietly(source: DataSource) {
        try {
            source.close()
        } catch (
            @Suppress("SwallowedException") e: IOException,
        ) {
            failed = true
        }
    }
}
