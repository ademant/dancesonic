package org.dancesonic.player.ui

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.dancesonic.player.AppContainer
import org.dancesonic.player.playback.PlayQueue
import org.dancesonic.player.playback.PlaybackProgress
import org.dancesonic.player.playback.PlaybackUiState
import org.dancesonic.player.subsonic.LanPins
import org.dancesonic.player.subsonic.LocalNetworkAccess
import org.dancesonic.player.subsonic.LocalNetworkBlockedException
import org.dancesonic.player.subsonic.ServerUrl
import org.dancesonic.player.testsupport.TestSubsonicServer
import org.dancesonic.player.testsupport.TestViewModelHost
import org.dancesonic.player.testsupport.newTestContainer
import org.dancesonic.player.testsupport.resetDatabase
import org.dancesonic.player.testsupport.seedOneAlbum
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URLEncoder

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConnectViewModelTest {
    private lateinit var container: AppContainer
    private val host = TestViewModelHost()

    @Before
    fun setUp() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher())
            container = newTestContainer()
            container.resetDatabase()
        }

    @After
    fun tearDown() {
        LocalNetworkAccess.install { true }
        LanPins.clear()
        host.clear()
        container.database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `blank API key is rejected before any network call`() {
        val vm = host.create { ConnectViewModel(container) }

        vm.connectWithApiKey("https://music.example.com", "   ")

        assertEquals("Enter an API key.", vm.state.value.error)
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun `plain http server address is rejected`() {
        val vm = host.create { ConnectViewModel(container) }

        vm.connectWithApiKey("http://music.example.com", "key-123")

        assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun `scanned connection code over plain http is rejected before any network call`() {
        val vm = host.create { ConnectViewModel(container) }

        vm.connectFromScan("dancesonic://connect?server=http%3A%2F%2Fmusic.example.com%2F&key=key-123")

        assertEquals(ServerUrl.HTTP_NOT_PRIVATE, vm.state.value.error)
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun `refresh without saved settings explains why nothing happens`() {
        val vm = host.create { ConnectViewModel(container) }

        vm.sync()

        assertEquals("Not connected to a server. Connect again to sync.", vm.state.value.error)
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun `invalid pairing code is reported without starting a connection`() {
        val vm = host.create { ConnectViewModel(container) }

        vm.pairWithCode("https://music.example.com/not-a-pairing-code")

        assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun `disconnect without saved settings clears the catalog and keeps playlists`() =
        runTest {
            container.seedOneAlbum()
            val playlistId = container.playlists.create("Keep me")
            val vm = host.create { ConnectViewModel(container) }

            vm.disconnect()

            assertNull(vm.state.value.error)
            container.library.artists().test {
                assertEquals(emptyList<String>(), awaitItem().map { it.name })
            }
            container.playlists.playlist(playlistId).test {
                assertEquals("Keep me", awaitItem()?.name)
            }
        }

    @Test
    fun `a server on the local network is refused at once while the permission is missing (#124)`() {
        LocalNetworkAccess.install { false }
        val vm = host.create { ConnectViewModel(container) }

        vm.connectWithApiKey("https://192.168.1.5:4533", "key-123")

        assertEquals(LocalNetworkBlockedException.MESSAGE, vm.state.value.error)
        assertTrue(vm.state.value.localNetworkBlocked)
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun `a pairing code whose pin does not match sends no pairing request (#126)`() =
        runTest {
            TestSubsonicServer().use { server ->
                val address = URLEncoder.encode(server.server.url("/").toString(), "UTF-8")
                val wrongPin = URLEncoder.encode("sha256/" + "A".repeat(43) + "=", "UTF-8")
                val vm = host.create { ConnectViewModel(container) }

                vm.pairWithCode("tinysonic://pair?server=$address&token=token-12345&pin=$wrongPin")
                withContext(Dispatchers.Default) { withTimeout(10_000) { vm.state.first { !it.busy } } }

                assertEquals(PIN_MISMATCH, vm.state.value.error)
                assertEquals(0, server.server.requestCount)
                assertNull(LanPins.pinFor(server.server.hostName, server.server.port))
            }
        }

    @Test
    fun `disconnect leaves nothing playing and an empty queue, also when repeated (#130)`() =
        runTest {
            val vm = host.create { ConnectViewModel(container) }

            container.player.reset()
            vm.disconnect()
            vm.disconnect()

            assertEquals(PlaybackUiState(), container.player.state.value)
            assertEquals(PlayQueue(), container.player.queue.value)
            assertEquals(PlaybackProgress(), container.player.progress.value)
        }
}
