package org.dancesonic.player.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
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

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StreamDataSourceTest {
    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun streamUri(): Uri = Uri.parse(server.url("/stream").toString())

    private fun factory() = StreamDataSource.Factory(OkHttpDataSource.Factory(OkHttpClient()))

    @Test
    fun `the first request for a whole file asks for bytes from zero, once`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-2/3")
                .setBody("abc"),
        )
        val source = factory().createDataSource()
        source.open(DataSpec(streamUri()))
        source.close()

        val request = server.takeRequest()
        assertEquals(listOf("bytes=0-"), request.headers.values("Range"))
    }

    @Test
    fun `a ranged request keeps its own range, with no second Range header`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 10-12/13")
                .setBody("klm"),
        )
        val source = factory().createDataSource()
        source.open(DataSpec(streamUri(), 10L, C.LENGTH_UNSET.toLong()))
        source.close()

        val request = server.takeRequest()
        assertEquals(listOf("bytes=10-"), request.headers.values("Range"))
    }

    @Test
    fun `with the size known, the first request is bounded to the end of the file`() {
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-5/6").setBody("abcdef"))
        val source = StreamDataSource.Factory(OkHttpDataSource.Factory(OkHttpClient())) { 6L }.createDataSource()
        source.open(DataSpec.Builder().setUri(streamUri()).setKey("song-1").build())
        source.close()

        val request = server.takeRequest()
        assertEquals(listOf("bytes=0-5"), request.headers.values("Range"))
    }

    @Test
    fun `with the size known, a request from a position stops at the end of the file`() {
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 4-5/6").setBody("ef"))
        val source = StreamDataSource.Factory(OkHttpDataSource.Factory(OkHttpClient())) { 6L }.createDataSource()
        source.open(DataSpec.Builder().setUri(streamUri()).setKey("song-1").setPosition(4).build())
        source.close()

        val request = server.takeRequest()
        assertEquals(listOf("bytes=4-5"), request.headers.values("Range"))
    }
}
