package org.dancesonic.player.playback

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.dancesonic.player.data.db.TrackEntity
import org.dancesonic.player.data.db.TrackWithTempo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The queue and state the player screen shows, read from a real player (#129), and the progress ticker (#136). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackPublisherTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private var now = 0L
    private val counted = mutableListOf<String>()
    private val counter = PlayCounter({ now }) { songId, _ -> counted += songId }
    private val started = mutableListOf<String>()
    private val publisher =
        PlaybackPublisher(scope, counter, onTrackStarted = { _, songId -> started += songId }, main = dispatcher)
    private lateinit var player: ExoPlayer

    @Before
    fun setUp() {
        player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext()).build()
        publisher.attach(player)
    }

    @After
    fun tearDown() {
        player.release()
        publisher.clear()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun item(id: String) =
        MediaItem
            .Builder()
            .setMediaId(id)
            .setUri("https://music.example.com/rest/stream.view?id=$id")
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Title $id").build())
            .build()

    private fun track(
        id: String,
        bpm: Int,
        seconds: Int,
    ) = TrackWithTempo(TrackEntity(id, "a-1", "Ada", "Title $id", 1, seconds, null, 0L), bpm)

    private fun queueIds() =
        publisher.queue.value.entries
            .map { it.songId }

    @Test
    fun `a new queue is published when the timeline changes`() {
        player.setMediaItems(listOf(item("s-1"), item("s-2"), item("s-3")))
        idle()

        assertEquals(listOf("s-1", "s-2", "s-3"), queueIds())
        assertEquals(0, publisher.queue.value.currentIndex)
        assertEquals(3, publisher.state.value.queueLength)
    }

    @Test
    fun `starting another song publishes the transition and reports the start`() {
        player.setMediaItems(listOf(item("s-1"), item("s-2"), item("s-3")))
        idle()

        player.seekTo(2, 0L)
        idle()

        assertEquals(2, publisher.queue.value.currentIndex)
        assertEquals("s-3", publisher.state.value.nowPlayingId)
        assertTrue(started.contains("s-3"))
    }

    @Test
    fun `a reordered queue keeps each song's tempo and length`() {
        publisher.load(listOf(track("s-1", 90, 100), track("s-2", 120, 200), track("s-3", 180, 300)))
        player.setMediaItems(listOf(item("s-1"), item("s-2"), item("s-3")), 1, 0L)
        idle()
        assertEquals(120, publisher.state.value.trackBpm)

        // Another controller moves the song playing now to the end.
        player.moveMediaItem(1, 2)
        idle()

        assertEquals(listOf("s-1", "s-3", "s-2"), queueIds())
        assertEquals(2, publisher.queue.value.currentIndex)
        assertEquals("s-2", publisher.state.value.nowPlayingId)
        assertEquals(120, publisher.state.value.trackBpm)
        publisher.publishProgress(player)
        assertEquals(200_000L, publisher.progress.value.durationMs)
    }

    @Test
    fun `a song queued twice gets a row for each time`() {
        player.setMediaItems(listOf(item("s-1"), item("s-2"), item("s-1")))
        idle()

        val keys =
            publisher.queue.value.entries
                .map { it.key }
        assertEquals(listOf("s-1#1", "s-2#1", "s-1#2"), keys)
    }

    @Test
    fun `clearing the publisher, as a reset does, empties the queue and the state`() {
        player.setMediaItems(listOf(item("s-1")))
        idle()

        publisher.clear()

        assertEquals(PlayQueue(), publisher.queue.value)
        assertEquals(PlaybackUiState(), publisher.state.value)
    }

    @Test
    fun `the ticker runs only while the player plays`() {
        val listener = publisher.listenerFor(player)
        assertFalse(publisher.ticking)

        listener.onIsPlayingChanged(true)
        assertTrue(publisher.ticking)
        scope.advanceTimeBy(PlaybackPublisher.PROGRESS_INTERVAL_MS * 3)
        scope.runCurrent()

        listener.onIsPlayingChanged(false)
        assertFalse(publisher.ticking)
        // Paused: nothing is scheduled any more, so time passing runs no tick.
        val before = scope.testScheduler.currentTime
        scope.testScheduler.advanceUntilIdle()
        assertEquals(before, scope.testScheduler.currentTime)
    }

    @Test
    fun `play counting still counts playing time and not paused time`() {
        counter.trackStarted("s-1", durationMs = 4_000L)
        val listener = publisher.listenerFor(player)

        listener.onIsPlayingChanged(true)
        now += 1_500L
        listener.onIsPlayingChanged(false)
        // A long pause adds nothing.
        now += 60_000L
        listener.onIsPlayingChanged(true)
        now += 400L
        listener.onIsPlayingChanged(false)
        assertEquals(emptyList<String>(), counted)

        listener.onIsPlayingChanged(true)
        now += 200L
        listener.onIsPlayingChanged(false)
        assertEquals(listOf("s-1"), counted)
    }
}
