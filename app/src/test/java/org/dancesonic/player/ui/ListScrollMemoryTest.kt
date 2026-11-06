package org.dancesonic.player.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ListScrollMemoryTest {
    @Test
    fun `a list never scrolled starts at the top`() {
        assertEquals(0 to 0, ListScrollMemory().position("albums:artist-1"))
    }

    @Test
    fun `a list keeps its place, and another list keeps its own`() {
        val memory = ListScrollMemory()

        memory.remember("albums:artist-1", firstVisibleItem = 42, offset = 17)
        memory.remember("albums:artist-2", firstVisibleItem = 3, offset = 0)

        assertEquals(42 to 17, memory.position("albums:artist-1"))
        assertEquals(3 to 0, memory.position("albums:artist-2"))
    }

    @Test
    fun `scrolling again moves the remembered place`() {
        val memory = ListScrollMemory()
        memory.remember("artists", firstVisibleItem = 10, offset = 5)

        memory.remember("artists", firstVisibleItem = 80, offset = 0)

        assertEquals(80 to 0, memory.position("artists"))
    }
}
