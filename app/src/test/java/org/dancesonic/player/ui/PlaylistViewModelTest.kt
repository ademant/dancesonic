package org.dancesonic.player.ui

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.dancesonic.player.AppContainer
import org.dancesonic.player.testsupport.TestViewModelHost
import org.dancesonic.player.testsupport.newTestContainer
import org.dancesonic.player.testsupport.resetDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaylistViewModelTest {
    private lateinit var container: AppContainer
    private val host = TestViewModelHost()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        container = newTestContainer()
        runBlocking { container.resetDatabase() }
    }

    @After
    fun tearDown() {
        host.clear()
        container.database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `created playlist is listed`() =
        runTest {
            val vm = host.create { PlaylistViewModel(container) }
            val createdId = CompletableDeferred<Long>()
            vm.create("Waltz course") { createdId.complete(it) }

            assertTrue(createdId.await() > 0)
            vm.playlists.test {
                assertEquals(listOf("Waltz course"), awaitMatching { it.isNotEmpty() }.map { it.name })
            }
        }

    @Test
    fun `tracks added with a dance type appear in that group`() =
        runTest {
            val vm = host.create { PlaylistViewModel(container) }
            val playlistId = CompletableDeferred<Long>()
            vm.create("Waltz course") { playlistId.complete(it) }
            vm.addTrack(playlistId.await(), trackId = "s-1", danceType = "Waltz")

            vm.groups(playlistId.await()).test {
                val groups = awaitMatching { it.isNotEmpty() }
                assertEquals(listOf("Waltz"), groups.map { it.danceType })
                assertEquals(listOf("s-1"), groups.single().items.map { it.trackId })
            }
        }

    @Test
    fun `deleted playlist disappears from the list`() =
        runTest {
            val vm = host.create { PlaylistViewModel(container) }
            val playlistId = CompletableDeferred<Long>()
            vm.create("Old course") { playlistId.complete(it) }
            vm.delete(playlistId.await())

            // The VM's list starts empty, so check the stored rows: they only become empty once the delete ran.
            container.playlists.playlists().test {
                assertEquals(emptyList<String>(), awaitMatching { it.isEmpty() }.map { it.name })
            }
        }

    /** Room and StateFlow emit asynchronously; skip the empty initial value until the data arrives. */
    private suspend fun <T> ReceiveTurbine<T>.awaitMatching(predicate: (T) -> Boolean): T {
        var item = awaitItem()
        while (!predicate(item)) item = awaitItem()
        return item
    }
}
