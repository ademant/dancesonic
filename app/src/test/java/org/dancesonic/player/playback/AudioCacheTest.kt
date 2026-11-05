package org.dancesonic.player.playback

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.test.core.app.ApplicationProvider
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AudioCacheTest {
    private lateinit var server: MockWebServer
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun start() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun streamUri(): Uri = Uri.parse(server.url("/stream?id=song-1&apikey=secret").toString())

    /** Runs [block] on the source and closes it afterwards; Media3 sources are not Closeable. */
    private inline fun <T> DataSource.session(block: (DataSource) -> T): T =
        try {
            block(this)
        } finally {
            close()
        }

    private fun readAll(source: DataSource): String {
        val out = StringBuilder()
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val n = source.read(buffer, 0, buffer.size)
            if (n == -1) break
            out.append(String(buffer, 0, n))
        }
        return out.toString()
    }

    @Test
    fun `a track played once is read from disk the second time`() {
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-5/6").setBody("abcdef"))
        val cache = AudioCache(context)
        val factory = cache.dataSourceFactory(StreamDataSource.Factory(OkHttpDataSource.Factory(OkHttpClient())))
        val spec = DataSpec.Builder().setUri(streamUri()).setKey("song-1").build()

        factory.createDataSource().session { source ->
            source.open(spec)
            assertEquals("abcdef", readAll(source))
        }
        factory.createDataSource().session { source ->
            source.open(spec)
            assertEquals("abcdef", readAll(source))
        }

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `with the size known, a track played once is read from disk without a request`() {
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-5/6").setBody("abcdef"))
        val cache = AudioCache(context)
        val factory =
            cache.dataSourceFactory(StreamDataSource.Factory(OkHttpDataSource.Factory(OkHttpClient())) { 6L })
        val spec = DataSpec.Builder().setUri(streamUri()).setKey("song-4").build()

        factory.createDataSource().session { source ->
            source.open(spec)
            assertEquals("abcdef", readAll(source))
        }
        factory.createDataSource().session { source ->
            source.open(spec)
            assertEquals("abcdef", readAll(source))
        }

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `clearing removes the cached track, so it is downloaded again`() =
        kotlinx.coroutines.runBlocking {
            server.enqueue(
                MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-5/6").setBody("abcdef"),
            )
            server.enqueue(
                MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-5/6").setBody("abcdef"),
            )
            val cache = AudioCache(context)
            val factory = cache.dataSourceFactory(StreamDataSource.Factory(OkHttpDataSource.Factory(OkHttpClient())))
            val spec = DataSpec.Builder().setUri(streamUri()).setKey("song-2").build()

            factory.createDataSource().session { source ->
                source.open(spec)
                readAll(source)
            }
            cache.clear()
            factory.createDataSource().session { source ->
                source.open(spec)
                readAll(source)
            }

            assertEquals(2, server.requestCount)
        }

    private companion object {
        const val BUFFER_BYTES = 64
    }
}
