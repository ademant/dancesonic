package org.dancesonic.player.playback

import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AudioPrefetcherTest {
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope

    @Before
    fun start() {
        server = MockWebServer().also { it.start() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun stop() {
        scope.cancel()
        server.shutdown()
    }

    @Test
    fun `a track that is already downloading is not started again when it begins playing`() =
        runBlocking {
            // A slow body, so the second start comes while the first download is still running.
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-5/6")
                    .setBody("abcdef")
                    .throttleBody(2, 100, TimeUnit.MILLISECONDS),
            )
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val cache = AudioCache(context)
            val upstream = StreamDataSource.Factory(OkHttpDataSource.Factory(OkHttpClient())) { 6L }
            val prefetcher = AudioPrefetcher(cache, upstream, scope)
            val url = server.url("/stream?id=song-26")

            prefetcher.prefetch("song-26", url, 6L)
            delay(50)
            prefetcher.prefetch("song-26", url, 6L)
            delay(DOWNLOAD_WAIT_MILLIS)

            assertEquals(1, server.requestCount)
        }

    private companion object {
        const val DOWNLOAD_WAIT_MILLIS = 1_000L
    }
}
