package org.dancesonic.player.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.dancesonic.player.data.MeterView
import org.dancesonic.player.data.ShoesView
import org.dancesonic.player.data.db.EditEntity
import org.dancesonic.player.data.db.EditKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrackDetailRowsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun show(
        hasDescription: Boolean = false,
        canEdit: Boolean = true,
        meter: MeterView? = MeterView("8/4", "3+3+2", pending = false, error = null),
        actions: TrackEntryActions = TrackEntryActions(),
    ) {
        val shoes = ShoesView(mine = 3, median = null, raters = 0, pending = false, error = null)
        val entries =
            TrackEntries(
                dances = listOf("gavotte"),
                meter = meter,
                hasDescription = hasDescription,
                shoes = shoes,
                canEdit = canEdit,
            )
        composeRule.setContent { TrackDetailRows(entries = entries, actions = actions) }
    }

    private fun topOf(text: String): Float = composeRule.onNodeWithText(text).fetchSemanticsNode().positionInRoot.y

    @Test
    fun `the rows come in the order dance, meter, description, shoes`() {
        show(hasDescription = true)

        val dance = topOf("Dance: gavotte")
        val meter = topOf("Meter: 8/4 - 3+3+2")
        val description = topOf("Dance description")
        val shoes = topOf("Shoes:")
        assertTrue(dance < meter)
        assertTrue(meter < description)
        assertTrue(description < shoes)
    }

    @Test
    fun `the dance description row appears only when DanceBrainz has one`() {
        show(hasDescription = false)

        composeRule.onAllNodesWithText("Dance description").assertCountEquals(0)
    }

    @Test
    fun `the dance description row opens the text when one exists`() {
        var opened = false
        show(hasDescription = true, actions = TrackEntryActions(onDescription = { opened = true }))

        composeRule.onNodeWithText("Dance description").assertIsDisplayed().performClick()

        assertTrue(opened)
    }

    @Test
    fun `a meter that is not set still has its row`() {
        show(meter = null)

        composeRule.onNodeWithText("Meter: not set").assertIsDisplayed()
    }

    @Test
    fun `a dance row does nothing without a sign-in, while the description stays readable`() {
        var danceTapped = false
        show(canEdit = false, hasDescription = true, actions = TrackEntryActions(onDances = { danceTapped = true }))

        composeRule.onNodeWithText("Dance: gavotte").performClick()

        assertEquals(false, danceTapped)
        composeRule.onNodeWithText("Dance description").assertIsDisplayed()
    }

    @Test
    fun `the shoes are five, one for each point of the rating`() {
        show()

        composeRule.onAllNodesWithText("👟").assertCountEquals(5)
    }
}

/** The text of a queued meter change, in words. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MeterChangeLineTest {
    private fun change(
        path: String,
        parent: String?,
    ) = EditEntity(kind = EditKind.METER, mbid = "m", path = path, meterParent = parent, queuedAt = 0)

    @Test
    fun `a queued sub-meter reads as its meter and sub-meter`() {
        assertEquals("Meter 8/4 - 3+3+2", changeLine(change(path = "3+3+2", parent = "8/4")))
    }

    @Test
    fun `a queued meter reads as the meter alone`() {
        assertEquals("Meter 8/4", changeLine(change(path = "8/4", parent = null)))
    }
}
