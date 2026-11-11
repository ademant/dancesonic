package org.dancesonic.player.analysis

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.dancesonic.player.data.db.DanceSonicDatabase
import org.dancesonic.player.data.db.WaveformEntity
import org.dancesonic.player.playback.AudioCache
import org.dancesonic.player.testsupport.TestSubsonicServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WaveformStoreTest {
    private lateinit var db: DanceSonicDatabase
    private lateinit var server: TestSubsonicServer
    private lateinit var store: WaveformStore
    private var supported = true

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room.inMemoryDatabaseBuilder(context, DanceSonicDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        server = TestSubsonicServer()
        store =
            WaveformStore(
                dao = db.waveformDao(),
                tracks = db.trackDao(),
                audio = AudioCache(context),
                settingsProvider = { server.settings },
                analysisFor = { AnalysisClient(it, server.http) },
                serverSupportsAnalysis = { supported },
            )
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    @Test
    fun `a stored server waveform is saved with its source and time`() =
        runBlocking {
            server.server.enqueue(MockResponse().setBody(WAVEFORM_123))

            store.loadFromServer(SONG)

            val row = db.waveformDao().get(SONG)
            assertEquals("server", row?.source)
            assertEquals("1,2,3", row?.peaks)
            assertTrue(row != null && row.computedAt > 0)
        }

    @Test
    fun `a song the server is still working on is asked again after its wait`() =
        runBlocking {
            server.server.enqueue(MockResponse().setResponseCode(202).setHeader("Retry-After", "1").setBody("{}"))
            server.server.enqueue(MockResponse().setBody(WAVEFORM_123))

            store.loadFromServer(SONG)

            assertEquals(2, server.server.requestCount)
            assertEquals("1,2,3", db.waveformDao().get(SONG)?.peaks)
        }

    @Test
    fun `nothing is requested when the server does not advertise the analysis`() =
        runBlocking {
            supported = false

            store.loadFromServer(SONG)

            assertEquals(0, server.server.requestCount)
            assertNull(db.waveformDao().get(SONG))
        }

    @Test
    fun `a stored server waveform is not requested again or replaced`() =
        runBlocking {
            db.waveformDao().upsert(WaveformEntity(SONG, "9", "server", 1L))

            store.loadFromServer(SONG)

            assertEquals(0, server.server.requestCount)
            assertEquals("9", db.waveformDao().get(SONG)?.peaks)
        }

    @Test
    fun `a server with no waveform and no pending work stores nothing`() =
        runBlocking {
            server.server.enqueue(MockResponse().setBody("""{"id":"$SONG","waveform":null}"""))

            store.loadFromServer(SONG)

            assertNull(db.waveformDao().get(SONG))
        }

    @Test
    fun `a failed request stores nothing and does not throw`() =
        runBlocking {
            server.server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))

            store.loadFromServer(SONG)

            assertNull(db.waveformDao().get(SONG))
        }

    @Test
    fun `observe gives the stored peaks`() =
        runBlocking {
            db.waveformDao().upsert(WaveformEntity(SONG, "4,5", "local", 1L))

            assertEquals(listOf(4, 5), store.observe(SONG).first())
            assertNull(store.observe("other").first())
        }

    @Test
    fun `local analysis does nothing when the track is not whole in the cache`() =
        runBlocking {
            // No track row exists, so nothing is decoded and nothing is stored.
            store.analyseCached(SONG, sizeBytes = 1_000L)

            assertNull(db.waveformDao().get(SONG))
        }

    private companion object {
        const val SONG = "so-1"
        const val WAVEFORM_123 = """{"id":"so-1","waveform":{"bins":3,"peaks":[1,2,3]}}"""
    }
}
