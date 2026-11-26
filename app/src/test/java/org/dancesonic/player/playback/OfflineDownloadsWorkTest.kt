package org.dancesonic.player.playback

import android.content.Context
import android.util.Log
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.dancesonic.player.data.db.AlbumEntity
import org.dancesonic.player.data.db.ArtistEntity
import org.dancesonic.player.data.db.DanceSonicDatabase
import org.dancesonic.player.data.db.KeptCollectionEntity
import org.dancesonic.player.data.db.KeptKind
import org.dancesonic.player.data.db.TrackEntity
import org.dancesonic.player.subsonic.ServerSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress

/** Offline downloads run in WorkManager (#139): the worker, its network constraint, and the passes it runs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OfflineDownloadsWorkTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var server: MockWebServer
    private lateinit var db: DanceSonicDatabase
    private lateinit var cache: AudioCache

    /** The network the downloads see; the test changes it. */
    private var block: DownloadBlock? = null
    private val network =
        object : NetworkState {
            override fun blockReason(allowMobileData: Boolean): DownloadBlock? = block

            override fun changes(): Flow<Unit> = emptyFlow()
        }

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start(InetAddress.getByName(LOOPBACK), 0) }
        db =
            Room
                .inMemoryDatabaseBuilder(context, DanceSonicDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        cache = AudioCache(context)
    }

    @After
    fun tearDown() {
        scope.cancel()
        runBlocking { cache.clear() }
        db.close()
        server.shutdown()
    }

    private fun downloads(serverUrl: HttpUrl = "http://$LOOPBACK:${server.port}/".toHttpUrl()): OfflineDownloads =
        OfflineDownloads(
            db,
            { cache },
            DefaultHttpDataSource.Factory(),
            conditions = {
                DownloadConditions(ServerSettings(serverUrl, "key-123", allowCleartext = true), allowMobileData = false)
            },
            network = network,
            scheduler = {},
            scope = scope,
            io = Dispatchers.Unconfined,
        )

    private suspend fun keepOneTrack() {
        db.artistDao().upsertAll(listOf(ArtistEntity(id = "ar-1", name = "Ada", syncedAt = 1L)))
        db.albumDao().upsertAll(
            listOf(AlbumEntity(id = "al-1", artistId = "ar-1", name = "N", year = 1, syncedAt = 1L)),
        )
        db.trackDao().upsertAll(
            listOf(TrackEntity("s-1", "al-1", "Ada", "One", 1, 1, null, 1L, sizeBytes = BODY.length.toLong())),
        )
        db.keptCollectionDao().keep(KeptCollectionEntity(KeptKind.ALBUM, "al-1", keptAt = 1L))
    }

    @Test
    fun `a pass downloads a kept track from the server, and a repeated pass asks for nothing`() =
        runBlocking {
            keepOneTrack()
            server.enqueue(MockResponse().setBody(BODY))
            val offline = downloads()

            assertFalse(offline.runPasses())
            assertTrue(cache.holdsWhole("s-1", BODY.length.toLong()))
            assertEquals(1, server.requestCount)

            // After a restart the work runs again; the track is held, so nothing is fetched.
            offline.kick()
            assertFalse(offline.runPasses())
            assertEquals(1, server.requestCount)
            assertEquals(1, offline.status.value.heldTracks)
        }

    @Test
    fun `a metered network stops the pass for a retry, and nothing is fetched`() =
        runBlocking {
            keepOneTrack()
            block = DownloadBlock.WaitingForWifi

            assertTrue(downloads().runPasses())
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `a server that fails the download is retried later`() =
        runBlocking {
            keepOneTrack()
            server.enqueue(MockResponse().setResponseCode(503))

            assertTrue(downloads().runPasses())
            assertFalse(cache.holdsWhole("s-1", BODY.length.toLong()))
        }

    @Test
    fun `the worker retries a pass that asks for it and ends after one that finished`() =
        runBlocking {
            assertEquals(ListenableWorker.Result.retry(), worker { true }.doWork())
            assertEquals(ListenableWorker.Result.success(), worker { false }.doWork())
        }

    @Test
    fun `the work waits for an unmetered network, then runs once (#139)`() {
        var runs = 0
        val workManager =
            testWorkManager {
                runs++
                false
            }
        val scheduler = WorkDownloadScheduler { workManager }

        scheduler.schedule(allowMobileData = false)
        // A second request while the first waits is merged into it.
        scheduler.schedule(allowMobileData = false)
        val waiting = workInfos(workManager)
        assertEquals(1, waiting.size)
        assertEquals(WorkInfo.State.ENQUEUED, waiting.single().state)
        assertEquals(0, runs)

        val driver = WorkManagerTestInitHelper.getTestDriver(context)
        driver?.setAllConstraintsMet(waiting.single().id)

        assertEquals(WorkInfo.State.SUCCEEDED, workInfos(workManager).single().state)
        assertEquals(1, runs)
    }

    @Test
    fun `allowing mobile data replaces the waiting work with one that may use it`() {
        val workManager = testWorkManager { false }
        val scheduler = WorkDownloadScheduler { workManager }

        scheduler.schedule(allowMobileData = false)
        val first = workInfos(workManager).single { it.state == WorkInfo.State.ENQUEUED }
        scheduler.schedule(allowMobileData = true)
        val second = workInfos(workManager).single { it.state == WorkInfo.State.ENQUEUED }

        assertNotEquals(first.id, second.id)
        assertEquals(
            androidx.work.NetworkType.CONNECTED,
            second.constraints.requiredNetworkType,
        )
    }

    private fun worker(passes: DownloadPasses): OfflineDownloadWorker =
        TestListenableWorkerBuilder<OfflineDownloadWorker>(context)
            .setWorkerFactory(factory(passes))
            .build()

    private fun factory(passes: DownloadPasses) =
        object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = OfflineDownloadWorker(appContext, workerParameters) { passes }
        }

    private fun testWorkManager(passes: DownloadPasses): WorkManager {
        val config =
            Configuration
                .Builder()
                .setMinimumLoggingLevel(Log.DEBUG)
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(factory(passes))
                .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        return WorkManager.getInstance(context)
    }

    private fun workInfos(workManager: WorkManager): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(WorkDownloadScheduler.WORK_NAME).get()

    private companion object {
        const val BODY = "abcdefgh"
        const val LOOPBACK = "127.0.0.1"
    }
}
