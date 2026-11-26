package org.dancesonic.player.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.dancesonic.player.data.MeterView
import org.dancesonic.player.data.ShoesView
import org.dancesonic.player.data.db.EditEntity
import org.dancesonic.player.data.db.EditKind
import org.dancesonic.player.data.db.EditStatus
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
        changes: FieldChanges = FieldChanges(),
    ) {
        val shoes = ShoesView(mine = 3, median = null, raters = 0, pending = false, error = null)
        val entries =
            TrackEntries(
                dances = listOf("gavotte"),
                meter = meter,
                hasDescription = hasDescription,
                shoes = shoes,
                canEdit = canEdit,
                changes = changes,
            )
        composeRule.setContent { TrackDetailRows(entries = entries, actions = actions) }
    }

    private fun topOf(text: String): Float =
        composeRule
            .onNodeWithText(text)
            .fetchSemanticsNode()
            .positionInRoot.y

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
    fun `each row shows where its own change stands (#75)`() {
        val changes = FieldChanges(dances = FieldChange.HELD, meter = FieldChange.QUEUED, shoes = FieldChange.CONFLICT)
        show(changes = changes)

        composeRule.onNodeWithText("Dance: gavotte · held for review").assertIsDisplayed()
        composeRule.onNodeWithText("Meter: 8/4 - 3+3+2 · not sent yet").assertIsDisplayed()
        composeRule.onNodeWithText("Shoes (changed on DanceBrainz, not sent):").assertIsDisplayed()
    }

    @Test
    fun `rows without an open change show no state`() {
        show()

        composeRule.onNodeWithText("Dance: gavotte").assertIsDisplayed()
        composeRule.onNodeWithText("Meter: 8/4 - 3+3+2").assertIsDisplayed()
        composeRule.onNodeWithText("Shoes:").assertIsDisplayed()
    }

    @Test
    fun `the shoes are five, one for each point of the rating`() {
        show()

        composeRule.onAllNodesWithText("👟").assertCountEquals(5)
    }

    /** Only the shoes matter here: the other rows are empty. */
    private fun showShoes(
        mine: Int?,
        canEdit: Boolean = true,
        actions: TrackEntryActions = TrackEntryActions(),
    ) {
        val shoes = ShoesView(mine = mine, median = null, raters = 0, pending = false, error = null)
        val entries = TrackEntries(emptyList(), meter = null, hasDescription = false, shoes = shoes, canEdit = canEdit)
        composeRule.setContent { TrackDetailRows(entries = entries, actions = actions) }
    }

    private fun shoe(value: Int) = composeRule.onNodeWithContentDescription("Rate $value of 5 shoes")

    @Test
    fun `the shoes up to the rating are lit and the rest are not (#145)`() {
        showShoes(mine = 3)

        (1..3).forEach { shoe(it).assertIsSelected() }
        (4..5).forEach { shoe(it).assertIsNotSelected() }
    }

    @Test
    fun `without a rating no shoe is lit`() {
        showShoes(mine = null)

        (1..5).forEach { shoe(it).assertIsNotSelected() }
    }

    @Test
    fun `a lit shoe is drawn clearly stronger than an empty one, not only tinted`() {
        assertTrue(shoeAlpha(lit = true) - shoeAlpha(lit = false) >= 0.5f)
    }

    @Test
    fun `tapping a shoe sets the rating to that shoe`() {
        val rated = mutableListOf<Int>()
        showShoes(mine = 3, actions = TrackEntryActions(onRate = { rated += it }))

        shoe(5).performClick()
        shoe(1).performClick()

        assertEquals(listOf(5, 1), rated)
    }

    @Test
    fun `a rating change shows at once with its state after the tap`() {
        var view by mutableStateOf(ShoesView(mine = 2, median = null, raters = 0, pending = false, error = null))
        composeRule.setContent {
            TrackDetailRows(
                entries =
                    TrackEntries(
                        dances = emptyList(),
                        meter = null,
                        hasDescription = false,
                        shoes = view,
                        canEdit = true,
                        changes = FieldChanges(shoes = if (view.pending) FieldChange.QUEUED else null),
                    ),
                // As the repository does: the queued value shows as the viewer's rating, marked pending.
                actions = TrackEntryActions(onRate = { view = view.copy(mine = it, pending = true) }),
            )
        }

        shoe(4).performClick()

        shoe(4).assertIsSelected()
        shoe(5).assertIsNotSelected()
        composeRule.onNodeWithText("Shoes (not sent yet):").assertIsDisplayed()
    }

    @Test
    fun `signed out, the shoes say why they cannot be tapped`() {
        val rated = mutableListOf<Int>()
        showShoes(mine = 3, canEdit = false, actions = TrackEntryActions(onRate = { rated += it }))

        shoe(4).performClick()

        assertTrue(rated.isEmpty())
        composeRule.onNodeWithText("Sign in to DanceBrainz to rate").assertIsDisplayed()
    }
}

/** Which field each queued change belongs to, and which state its row shows (#75). */
class FieldChangesTest {
    private fun edit(
        kind: String,
        status: String,
        mbid: String = "m",
    ) = EditEntity(kind = kind, mbid = mbid, status = status, queuedAt = 0)

    @Test
    fun `changes are sorted by field`() {
        val queue =
            listOf(
                edit(EditKind.DANCE_ADD, EditStatus.PENDING_REVIEW),
                edit(EditKind.METER, EditStatus.QUEUED),
                edit(EditKind.SHOES, EditStatus.CONFLICT),
            )

        assertEquals(
            FieldChanges(dances = FieldChange.HELD, meter = FieldChange.QUEUED, shoes = FieldChange.CONFLICT),
            fieldChanges(queue, "m"),
        )
    }

    @Test
    fun `the change to act on wins when a field has several`() {
        val queue =
            listOf(
                edit(EditKind.DANCE_ADD, EditStatus.PENDING_REVIEW),
                edit(EditKind.DANCE_REMOVE, EditStatus.QUEUED),
            )
        assertEquals(FieldChange.QUEUED, fieldChanges(queue, "m").dances)

        val withConflict = queue + edit(EditKind.DANCE_ADD, EditStatus.CONFLICT)
        assertEquals(FieldChange.CONFLICT, fieldChanges(withConflict, "m").dances)
    }

    @Test
    fun `another track's changes do not show`() {
        val queue = listOf(edit(EditKind.SHOES, EditStatus.QUEUED, mbid = "other"))

        assertEquals(FieldChanges(), fieldChanges(queue, "m"))
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
