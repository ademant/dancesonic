package org.dancesonic.player.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import org.dancesonic.player.dance.DanceBrainzRatingsClient
import org.dancesonic.player.dance.DanceBrainzTagsClient
import org.dancesonic.player.data.db.DanceLookupEntity
import org.dancesonic.player.data.db.DanceSonicDatabase
import org.dancesonic.player.data.db.EditStatus
import org.dancesonic.player.testsupport.TestSubsonicServer
import org.dancesonic.player.testsupport.tokenStoreFor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Setting a track's meter: the send, the value it resolves to on DanceBrainz, and what the phone shows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DanceBrainzMeterEditsTest {
    private val mbid = "7f9c2a1e-0d3b-4c5a-9e8f-1a2b3c4d5e6f"
    private lateinit var db: DanceSonicDatabase
    private lateinit var server: TestSubsonicServer
    private lateinit var base: String
    private val now = 1_000_000L

    private val meterList =
        """[{"id":10,"name":"8/4","parent_id":null},{"id":11,"name":"3+3+2","parent_id":10}]"""

    @Before
    fun setUp() {
        db =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                DanceSonicDatabase::class.java,
            ).allowMainThreadQueries().build()
        server = TestSubsonicServer()
        base = server.server.url("/").toString()
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    private fun edits(signedIn: Boolean = true): DanceBrainzEdits =
        DanceBrainzEdits(
            database = db,
            tokens = tokenStoreFor(if (signedIn) SIGNED_IN_TOKEN else null),
            baseUrl = { base.toHttpUrl() },
            newClients = {
                DanceBrainzClients(
                    DanceBrainzRatingsClient(it, server.plain),
                    DanceBrainzTagsClient(it, server.plain),
                )
            },
            clock = { now },
        )

    private fun json(body: String): MockResponse = MockResponse().setResponseCode(200).setBody(body)

    private fun queuedMeters(): Int =
        runBlocking { db.editQueueDao().withStatus(EditStatus.QUEUED).count { it.kind == "meter" } }

    @Test
    fun `a sub-meter is sent as the id of its own value under its meter, without a qualifier`() {
        runBlocking { db.danceLookupDao().upsert(DanceLookupEntity(mbid, checkedAt = now)) }
        server.server.enqueue(json(meterList))
        server.server.enqueue(json("""{"change":{"status":"applied"}}"""))
        val repo = edits()

        runBlocking { repo.meters.set(mbid, name = "3+3+2", parentName = "8/4") }

        assertEquals("/api/v1/tag-types/meter/tags", server.server.takeRequest().requestUrl?.encodedPath)
        val put = server.server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("type=meter", put.requestUrl?.encodedQuery)
        assertEquals("""{"tags":[{"tag_id":11}]}""", put.body.readUtf8())
        assertEquals(0, queuedMeters())
    }

    @Test
    fun `once sent, the phone shows the new meter and sub-meter from the stored lookup`() {
        runBlocking { db.danceLookupDao().upsert(DanceLookupEntity(mbid, checkedAt = now, meter = null)) }
        server.server.enqueue(json(meterList))
        server.server.enqueue(json("""{"change":{"status":"applied"}}"""))
        val repo = edits()

        runBlocking { repo.meters.set(mbid, name = "3+3+2", parentName = "8/4") }

        val shown = runBlocking { repo.meters.observe().first()[mbid] }
        assertEquals("8/4", shown?.meter)
        assertEquals("3+3+2", shown?.subMeter)
        assertEquals(false, shown?.pending)
    }

    @Test
    fun `a meter chosen while offline is shown at once, and a second choice replaces it`() {
        runBlocking { db.danceLookupDao().upsert(DanceLookupEntity(mbid, checkedAt = now, meter = "3/4")) }
        val repo = edits(signedIn = false)

        runBlocking { repo.meters.set(mbid, name = "8/4", parentName = null) }
        runBlocking { repo.meters.set(mbid, name = "3+3+2", parentName = "8/4") }

        assertEquals(1, queuedMeters())
        val shown = runBlocking { repo.meters.observe().first()[mbid] }
        assertEquals("8/4", shown?.meter)
        assertEquals("3+3+2", shown?.subMeter)
        assertEquals(true, shown?.pending)
    }

    @Test
    fun `a meter DanceBrainz no longer has is a conflict, and nothing is sent`() {
        server.server.enqueue(json("""[{"id":10,"name":"8/4","parent_id":null}]"""))
        val repo = edits()

        runBlocking { repo.meters.set(mbid, name = "5/4", parentName = null) }

        assertEquals(1, server.server.requestCount)
        val conflict = runBlocking { db.editQueueDao().withStatus(EditStatus.CONFLICT).single() }
        assertEquals("meter", conflict.kind)
        assertTrue(conflict.lastError.orEmpty().startsWith("DanceBrainz no longer has this meter"))
    }

    @Test
    fun `a meter held by a moderated server stays pending review and is not shown as set`() {
        runBlocking { db.danceLookupDao().upsert(DanceLookupEntity(mbid, checkedAt = now, meter = "3/4")) }
        server.server.enqueue(json(meterList))
        server.server.enqueue(MockResponse().setResponseCode(202).setBody("""{"change":{"status":"pending"}}"""))
        val repo = edits()

        runBlocking { repo.meters.set(mbid, name = "8/4", parentName = null) }

        val held = runBlocking { db.editQueueDao().withStatus(EditStatus.PENDING_REVIEW).single() }
        assertEquals("meter", held.kind)
        val shown = runBlocking { repo.meters.observe().first()[mbid] }
        assertEquals("8/4", shown?.meter)
        assertEquals(true, shown?.pending)
    }

    private companion object {
        const val SIGNED_IN_TOKEN = "dbt_test-token"
    }
}
