package org.dancesonic.player.ui

import org.dancesonic.player.data.db.ChapterEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterMathTest {
    private val chapters =
        listOf(
            chapter(0, 112_000, title = null, dance = "Ton simpl"),
            chapter(112_000, 190_000, title = null, dance = "tamm-kreiz"),
            chapter(190_000, 300_000, title = "Ton doubl", dance = null),
        )

    @Test
    fun `a chapter is named by its title, then its dance, then its number`() {
        assertEquals("Ton simpl", chapterName(chapters[0], 1))
        assertEquals("Ton doubl", chapterName(chapters[2], 3))
        assertEquals("Chapter 2", chapterName(chapter(0, null, title = " ", dance = null), 2))
        assertEquals("Ton doubl (valse)", chapterName(chapter(0, null, title = "Ton doubl", dance = "valse"), 1))
    }

    @Test
    fun `the playing chapter is the last one that has started`() {
        assertEquals(-1, currentChapterIndex(chapters, positionMs = -1))
        assertEquals(0, currentChapterIndex(chapters, positionMs = 0))
        assertEquals(0, currentChapterIndex(chapters, positionMs = 111_999))
        assertEquals(1, currentChapterIndex(chapters, positionMs = 112_000))
        assertEquals(2, currentChapterIndex(chapters, positionMs = 299_000))
        assertEquals(-1, currentChapterIndex(emptyList(), positionMs = 5))
    }

    @Test
    fun `next goes to the first chapter after the position, and stops at the last`() {
        assertEquals(112_000L, nextChapterStart(chapters, positionMs = 50_000))
        assertEquals(190_000L, nextChapterStart(chapters, positionMs = 112_000))
        assertNull(nextChapterStart(chapters, positionMs = 190_000))
    }

    @Test
    fun `previous restarts the chapter when well into it, and steps back near its start`() {
        assertEquals(0L, previousChapterStart(chapters, positionMs = 50_000))
        assertEquals(0L, previousChapterStart(chapters, positionMs = 112_000 + 2_000))
        assertEquals(112_000L, previousChapterStart(chapters, positionMs = 150_000))
        assertNull(previousChapterStart(chapters, positionMs = 1_000))
        assertNull(previousChapterStart(emptyList(), positionMs = 1_000))
    }

    @Test
    fun `the known length is the last chapter's end, and a file far from it is approximate`() {
        assertEquals(300_000L, knownLengthMs(chapters))
        assertFalse(chaptersApproximate(chapters, fileDurationMs = 301_500))
        assertTrue(chaptersApproximate(chapters, fileDurationMs = 310_000))
        assertFalse(chaptersApproximate(chapters, fileDurationMs = 0))
        assertFalse(chaptersApproximate(listOf(chapter(0, null, null, null)), fileDurationMs = 310_000))
    }

    private fun chapter(
        start: Long,
        end: Long?,
        title: String?,
        dance: String?,
    ) = ChapterEntity(
        mbid = "rec",
        startMs = start,
        endMs = end,
        title = title,
        danceTagId = dance?.let { 1L },
        danceName = dance,
    )
}
