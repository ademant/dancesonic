package org.dancesonic.player.playback

import app.cash.turbine.test
import kotlinx.coroutines.runBlocking
import org.dancesonic.player.testsupport.newTestContainer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackSettingsStoreTest {
    private val store = newTestContainer().playbackSettingsStore

    @Test
    fun `the defaults are multiplier mode, no ReplayGain and a 120 BPM target`() =
        runBlocking {
            store.settings.test {
                assertEquals(PlaybackSettings(), awaitItem())
            }
        }

    @Test
    fun `saved choices are read back`() =
        runBlocking {
            val saved =
                PlaybackSettings(
                    replayGain = true,
                    tempoMode = TempoMode.Bpm,
                    multiplier = 0.75f,
                    targetBpm = 140,
                )
            store.save(saved)
            store.settings.test {
                assertEquals(saved, awaitItem())
            }
        }
}
