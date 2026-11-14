package org.dancesonic.player.dance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeterValuesTest {
    private val values =
        listOf(
            MeterValue(tagId = 10, name = "8/4", parentId = null),
            MeterValue(tagId = 11, name = "3+3+2", parentId = 10),
            MeterValue(tagId = 12, name = "2+2+2+2", parentId = 10),
            MeterValue(tagId = 20, name = "3/4", parentId = null),
            MeterValue(tagId = 21, name = "3+3+2", parentId = 20),
        )

    @Test
    fun `the label shows the meter and its sub-meter, or the meter alone`() {
        assertEquals("8/4 - 3+3+2", meterLabel("8/4", "3+3+2"))
        assertEquals("8/4", meterLabel("8/4", null))
        assertNull(meterLabel(null, null))
    }

    @Test
    fun `meters are the values without a parent, by name`() {
        assertEquals(listOf("3/4", "8/4"), topMeters(values).map { it.name })
    }

    @Test
    fun `sub-meters are the values under their meter only`() {
        val eightFour = values.first { it.name == "8/4" }
        assertEquals(listOf("2+2+2+2", "3+3+2"), subMetersOf(values, eightFour).map { it.name })
    }

    @Test
    fun `a queued sub-meter resolves to the sub-meter under its own meter, not another with the same name`() {
        assertEquals(21L, resolveMeter(values, "3+3+2", parentName = "3/4")?.tagId)
        assertEquals(11L, resolveMeter(values, "3+3+2", parentName = "8/4")?.tagId)
    }

    @Test
    fun `a queued meter resolves to the top-level value, and a missing one to null`() {
        assertEquals(20L, resolveMeter(values, "3/4", parentName = null)?.tagId)
        assertNull(resolveMeter(values, "5/4", parentName = null))
        assertNull(resolveMeter(values, "3+3+2", parentName = "5/4"))
    }
}
