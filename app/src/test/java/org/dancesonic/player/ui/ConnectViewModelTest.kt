package org.dancesonic.player.ui

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.dancesonic.player.AppContainer
import org.dancesonic.player.testsupport.TestViewModelHost
import org.dancesonic.player.testsupport.newTestContainer
import org.dancesonic.player.testsupport.resetDatabase
import org.dancesonic.player.testsupport.seedOneAlbum
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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

        assertEquals("The server address must start with https://", vm.state.value.error)
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
}
