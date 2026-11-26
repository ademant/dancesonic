package org.dancesonic.player.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.dancesonic.player.AppContainer
import org.dancesonic.player.data.db.TrackEntity
import org.dancesonic.player.testsupport.TestViewModelHost
import org.dancesonic.player.testsupport.newTestContainer
import org.dancesonic.player.testsupport.resetDatabase
import org.dancesonic.player.testsupport.seedOneAlbum
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Play button of an open dance (#144): it starts the list as shown from its top track as the new queue, also
 * after Randomize, and a row tap still starts that row.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
// Tall enough that all five rows of the dance are laid out on screen.
@Config(sdk = [35], qualifiers = "w411dp-h1600dp")
class DanceScreenPlayTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var container: AppContainer
    private val host = TestViewModelHost()
    private val started = mutableListOf<Pair<List<String>, Int>>()
    private val titles = listOf("One", "Two", "Three", "Four", "Five")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        container = newTestContainer()
        runBlocking {
            container.resetDatabase()
            container.seedOneAlbum()
            container.database.trackDao().upsertAll(
                titles.drop(1).mapIndexed { i, title ->
                    TrackEntity(
                        id = "s-${i + 2}",
                        albumId = "al-1",
                        artistName = "Ada",
                        title = title,
                        trackNumber = i + 2,
                        durationSec = 200,
                        mbid = "rec-${i + 2}",
                        syncedAt = 1L,
                    )
                },
            )
            (1..titles.size).forEach {
                container.database.danceMetadataDao().replaceFor("rec-$it", listOf("Waltz"), 1L)
            }
        }
    }

    @After
    fun tearDown() {
        host.clear()
        container.database.close()
        Dispatchers.resetMain()
    }

    private fun openWaltz() {
        val library = host.create { LibraryViewModel(container) }
        val playlists = host.create { PlaylistViewModel(container) }
        composeRule.setContent {
            MaterialTheme {
                DanceScreen(library, container.player, playlists, onOpenConfig = {}) { tracks, index ->
                    started += tracks.map { it.track.title } to index
                }
            }
        }
        composeRule.waitUntilAtLeastOneExists(hasText("W · 1"))
        composeRule.onNodeWithText("W · 1").performClick()
        composeRule.waitUntilAtLeastOneExists(hasText("Waltz"))
        composeRule.onNodeWithText("Waltz").performClick()
        composeRule.waitUntilAtLeastOneExists(hasText("Five"))
    }

    /** The track titles in the order they are drawn, top to bottom. */
    private fun shownOrder(): List<String> =
        titles.sortedBy {
            composeRule
                .onNodeWithText(it)
                .getBoundsInRoot()
                .top.value
        }

    @Test
    fun `Play starts the whole dance from its top track, not the track already loaded`() {
        openWaltz()
        // A row tap loads a track further down first, as if it were already playing.
        val loaded = shownOrder().last()
        composeRule.onNodeWithText(loaded).performClick()

        composeRule.onNodeWithText("Play").performClick()

        val (queue, index) = started.last()
        assertEquals(shownOrder(), queue)
        assertEquals(0, index)
        assertTrue(queue.first() != loaded)
    }

    @Test
    fun `after Randomize Play starts at the new top track`() {
        openWaltz()
        composeRule.onNodeWithText("Sort: Artist").performClick()
        composeRule.onNodeWithText("Randomize").performClick()
        composeRule.waitUntilAtLeastOneExists(hasText("Sort: Randomize"))

        composeRule.onNodeWithText("Play").performClick()

        val (queue, index) = started.last()
        assertEquals(0, index)
        assertEquals(shownOrder(), queue)
        assertEquals(titles.sorted(), queue.sorted())
    }

    @Test
    fun `a row tap still starts that row and keeps the rest as the queue`() {
        openWaltz()

        composeRule.onNodeWithText("Three").performClick()

        val (queue, index) = started.last()
        assertEquals(shownOrder(), queue)
        assertEquals("Three", queue[index])
    }

    @Test
    fun `Play sits left of the sort and is off while the dance has no tracks`() {
        composeRule.setContent {
            MaterialTheme {
                DanceTracksBar(
                    crumbs = listOf(Crumb("Dance", current = false) {}, Crumb("Waltz", current = true)),
                    sort = TrackSort.ARTIST_TITLE,
                    canPlay = false,
                    onPlayAll = { started += emptyList<String>() to 0 },
                    onSort = {},
                )
            }
        }

        composeRule.onNodeWithText("Play").assertIsNotEnabled().performClick()
        assertTrue(started.isEmpty())
        val play = composeRule.onNodeWithText("Play").getBoundsInRoot()
        val sort = composeRule.onNodeWithText("Sort: Artist").getBoundsInRoot()
        assertTrue(play.right <= sort.left)
    }
}
