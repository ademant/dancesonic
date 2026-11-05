package org.dancesonic.player.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Sends `Range: bytes=0-` on a stream's first request. Without a Range, ExoPlayer opens an unbounded response,
 * reads part of it, and reopens with a range, which downloads the start of the song twice. With the header the
 * server answers 206 for the whole file, and the connection can be reused. Requests that already carry a
 * position or length are left alone, so the header is never sent twice.
 */
@UnstableApi
class StreamDataSource private constructor(
    private val upstream: DataSource,
    private val sizeOf: (String) -> Long?,
) : DataSource {
    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long = upstream.open(bounded(dataSpec))

    /**
     * With the song's size known, each request is bounded to the end of the file. The cache can then tell a
     * complete file from a partial one, and reads from disk instead of checking the network. Without a size,
     * the first request still asks for the whole file.
     */
    private fun bounded(dataSpec: DataSpec): DataSpec {
        if (dataSpec.length != C.LENGTH_UNSET.toLong()) return dataSpec
        val size = dataSpec.key?.let(sizeOf) ?: return withWholeFileRange(dataSpec)
        val remaining = size - dataSpec.position
        return if (remaining <= 0) dataSpec else dataSpec.buildUpon().setLength(remaining).build()
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        readLength: Int,
    ): Int = upstream.read(buffer, offset, readLength)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()

    /**
     * Creates [StreamDataSource]s on top of [upstream], typically the authenticated OkHttp source. [sizeOf] gives the
     * size of a song by its cache key (its song id).
     */
    class Factory(
        private val upstream: DataSource.Factory,
        private val sizeOf: (String) -> Long? = { null },
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = StreamDataSource(upstream.createDataSource(), sizeOf)
    }

    private companion object {
        const val RANGE_HEADER = "Range"
        const val WHOLE_FILE_RANGE = "bytes=0-"

        fun withWholeFileRange(dataSpec: DataSpec): DataSpec {
            if (dataSpec.position != 0L || dataSpec.length != C.LENGTH_UNSET.toLong()) return dataSpec
            if (dataSpec.httpRequestHeaders.containsKey(RANGE_HEADER)) return dataSpec
            return dataSpec.buildUpon()
                .setHttpRequestHeaders(dataSpec.httpRequestHeaders + (RANGE_HEADER to WHOLE_FILE_RANGE))
                .build()
        }
    }
}
