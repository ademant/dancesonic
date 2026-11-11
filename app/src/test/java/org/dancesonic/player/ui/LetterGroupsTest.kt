package org.dancesonic.player.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LetterGroupsTest {
    @Test
    fun `a name goes under its first letter, in upper case`() {
        assertEquals("A", sectionOf("ada"))
        assertEquals("Z", sectionOf("Zorba"))
    }

    @Test
    fun `accents are removed before the letter is taken`() {
        assertEquals("E", sectionOf("Émile"))
        assertEquals("N", sectionOf("Ñandú"))
        assertEquals("A", sectionOf("Ångström"))
    }

    @Test
    fun `a name that does not start with a basic letter goes under the hash section`() {
        assertEquals(OTHER_SECTION, sectionOf("1 Pas"))
        assertEquals(OTHER_SECTION, sectionOf("(Les) Amis"))
        assertEquals(OTHER_SECTION, sectionOf(""))
        assertEquals(OTHER_SECTION, sectionOf("   "))
    }

    @Test
    fun `leading spaces do not decide the section`() {
        assertEquals("B", sectionOf("  Bella ciao"))
    }

    @Test
    fun `sections are alphabetical with the hash section last`() {
        val names = listOf("Waltz", "1 Pas", "Adar", "Ñandú", "Ada", "émile")

        val groups = groupByFirstLetter(names) { it }

        assertEquals(listOf("A", "E", "N", "W", OTHER_SECTION), groups.map { it.letter })
    }

    @Test
    fun `items keep their order within a section`() {
        val names = listOf("Ada", "Émile", "adar", "Ada 2")

        val groups = groupByFirstLetter(names) { it }

        assertEquals(listOf("Ada", "adar", "Ada 2"), groups.single { it.letter == "A" }.items)
        assertEquals(listOf("Émile"), groups.single { it.letter == "E" }.items)
    }

    @Test
    fun `no sections are made for an empty list`() {
        assertEquals(emptyList<LetterGroup<String>>(), groupByFirstLetter(emptyList<String>()) { it })
    }
}
