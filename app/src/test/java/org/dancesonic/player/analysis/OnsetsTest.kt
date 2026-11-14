package org.dancesonic.player.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnsetsTest {
    @Test
    fun `onsets are sorted, clamped and keep each strength with its time`() {
        val onsets = Onsets.of(listOf(300L, 100L, -1L, 200L), listOf(10, 400, 50, -3))

        assertEquals(listOf(100, 200, 300), onsets?.timesMs?.toList())
        assertEquals(listOf(255, 0, 10), onsets?.strengths?.toList())
    }

    @Test
    fun `lists of different length give no onsets`() {
        assertNull(Onsets.of(listOf(1L, 2L), listOf(3)))
    }

    @Test
    fun `stored onsets read back as they were written`() {
        val onsets = checkNotNull(Onsets.of(listOf(5L, 950L), listOf(200, 81)))

        val read = decodeOnsets(encodeInts(onsets.timesMs), encodeInts(onsets.strengths))

        assertEquals(listOf(5, 950), read?.timesMs?.toList())
        assertEquals(listOf(200, 81), read?.strengths?.toList())
    }

    @Test
    fun `empty storage is no onsets, and malformed storage is none at all`() {
        assertEquals(0, decodeOnsets("", "")?.size)
        assertNull(decodeOnsets("1,x", "2,3"))
        assertNull(decodeOnsets("1,2", "3"))
    }
}
