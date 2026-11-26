package org.dancesonic.player.studio

import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * "Play on" with several paired Studios and one active (#88): switching, going back to the phone, ignoring a Studio
 * that is no longer active, and carrying on over another link when one drops.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayOnTargetTest {
    private val sessions = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var phone: ExoPlayer
    private lateinit var mediaSession: MediaSession

    /** What [reconnect] answers next; null throws as an unreachable Studio. */
    private var nextReconnect: (() -> OpenStudio)? = null
    private val reconnects = mutableListOf<Pair<String, String>>()
    private val target =
        PlayOnTarget(scope, reconnect = { record, dropped ->
            reconnects += record.id to dropped
            nextReconnect?.invoke() ?: throw StudioUnavailable(StudioUnavailable.Reason.NOT_FOUND)
        })

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        phone = ExoPlayer.Builder(context).build()
        mediaSession = MediaSession.Builder(context, phone).build()
        target.attach(mediaSession, phone)
    }

    @After
    fun tearDown() {
        target.detach()
        mediaSession.release()
        phone.release()
        sessions.cancel()
        scope.cancel()
    }

    private class Opened(
        val link: FakeLink,
        val studio: OpenStudio,
    )

    private fun open(
        id: String,
        transport: String = StudioTransports.BLUETOOTH,
    ): Opened {
        val link = FakeLink()
        val session = StudioSession(link, "d-$id", SoftwareSigner(), RecordingCounter(mutableListOf()), sessions)
        return Opened(link, OpenStudio(pairing(id), session, transport))
    }

    /** Runs the main looper until [done], for work that hops between the main thread and the session threads. */
    private fun until(done: () -> Boolean) {
        repeat(ATTEMPTS) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            Thread.sleep(STEP_MILLIS)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun sentCommands(link: FakeLink): List<String> =
        generateSequence { link.sent.tryReceive().getOrNull() }.toList()

    @Test
    fun `playing on a Studio puts it behind the media session`() {
        val a = open("a")

        assertTrue(target.playOnStudio(a.studio))

        assertEquals(PlayOnTarget.Target.Studio("a", "Studio a", "ble"), target.target.value)
        assertTrue(mediaSession.player is StudioPlayer)
    }

    @Test
    fun `choosing another Studio disconnects the active one first`() {
        val a = open("a")
        val b = open("b", StudioTransports.NETWORK)
        target.playOnStudio(a.studio)

        target.playOnStudio(b.studio)

        assertTrue(a.link.incoming.isClosedForReceive)
        assertFalse(b.link.incoming.isClosedForReceive)
        assertEquals(PlayOnTarget.Target.Studio("b", "Studio b", "network"), target.target.value)
    }

    @Test
    fun `a Studio that is no longer active is ignored when its link ends`() {
        val a = open("a")
        val b = open("b")
        target.playOnStudio(a.studio)
        target.playOnStudio(b.studio)

        // A's session ends after the switch; nothing reconnects to it and B stays active.
        a.studio.session.close()
        until { false }

        assertEquals(emptyList<Pair<String, String>>(), reconnects)
        assertEquals("b", (target.target.value as PlayOnTarget.Target.Studio).pairingId)
    }

    @Test
    fun `going back to the phone leaves the Studio playing`() {
        val a = open("a")
        target.playOnStudio(a.studio)

        target.playOnPhone()
        until { a.link.incoming.isClosedForReceive }

        assertEquals(PlayOnTarget.Target.Phone, target.target.value)
        assertSame(phone, mediaSession.player)
        assertTrue(a.link.incoming.isClosedForReceive)
        assertFalse(sentCommands(a.link).any { it.contains("pause") || it.contains("stop") })
    }

    @Test
    fun `a dropped link carries on over the next transport with the same Studio`() {
        val a = open("a", StudioTransports.BLUETOOTH)
        val again = open("a", StudioTransports.NETWORK)
        nextReconnect = { again.studio }
        target.playOnStudio(a.studio)

        a.link.incoming.close()
        until { (target.target.value as? PlayOnTarget.Target.Studio)?.transport == "network" }

        assertEquals(listOf("a" to "ble"), reconnects)
        assertEquals(PlayOnTarget.Target.Studio("a", "Studio a", "network"), target.target.value)
        assertTrue(mediaSession.player is StudioPlayer)
    }

    @Test
    fun `when no other transport answers, playback returns to the phone`() {
        val a = open("a")
        target.playOnStudio(a.studio)

        a.link.incoming.close()
        until { target.target.value == PlayOnTarget.Target.Phone }

        assertEquals(PlayOnTarget.Target.Phone, target.target.value)
        assertSame(phone, mediaSession.player)
    }

    private companion object {
        const val ATTEMPTS = 100
        const val STEP_MILLIS = 20L
    }
}
