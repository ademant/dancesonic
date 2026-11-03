package org.dancesonic.player.ui

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.dancesonic.player.AppContainer
import org.dancesonic.player.data.db.DanceTypeCount
import org.dancesonic.player.testsupport.TestViewModelHost
import org.dancesonic.player.testsupport.newTestContainer
import org.dancesonic.player.testsupport.resetDatabase
import org.dancesonic.player.testsupport.seedOneAlbum
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryViewModelTest {
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
    fun `artists appear once the catalog is stored`() =
        runTest {
            container.seedOneAlbum()
            val vm = host.create { LibraryViewModel(container) }

            vm.artists.test {
                assertEquals(listOf("Ada"), awaitMatching { it.isNotEmpty() }.map { it.name })
            }
        }

    @Test
    fun `albums and tracks follow the artist and album drill-down`() =
        runTest {
            container.seedOneAlbum()
            val vm = host.create { LibraryViewModel(container) }

            vm.albums("ar-1").test {
                assertEquals(listOf("Night"), awaitItem().map { it.name })
            }
            vm.tracks("al-1").test {
                assertEquals(listOf("One"), awaitItem().map { it.title })
            }
        }

    @Test
    fun `dance types count the matched tracks and list them`() =
        runTest {
            container.seedOneAlbum()
            container.database.danceMetadataDao().replaceFor("rec-1", listOf("Waltz"), 1L)
            val vm = host.create { LibraryViewModel(container) }

            vm.danceTypes.test {
                assertEquals(
                    listOf(DanceTypeCount(danceType = "Waltz", trackCount = 1)),
                    awaitMatching { it.isNotEmpty() },
                )
            }
            vm.tracksForDance("Waltz").test {
                assertEquals(listOf("One"), awaitItem().map { it.title })
            }
        }

    /** Room and StateFlow emit asynchronously; skip the empty initial value until the data arrives. */
    private suspend fun <T> ReceiveTurbine<T>.awaitMatching(predicate: (T) -> Boolean): T {
        var item = awaitItem()
        while (!predicate(item)) item = awaitItem()
        return item
    }
}
