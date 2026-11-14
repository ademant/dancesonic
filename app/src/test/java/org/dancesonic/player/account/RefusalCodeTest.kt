package org.dancesonic.player.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RefusalCodeTest {
    @Test
    fun `folksonic's flat body gives its code`() {
        assertEquals("pairing_pending", refusalCodeOf("""{"error":"pairing_pending","message":"wait"}"""))
    }

    @Test
    fun `DanceBrainz's nested body gives the code inside it`() {
        assertEquals(
            "device_unknown",
            refusalCodeOf("""{"error":{"code":"device_unknown","message":"m","hint":"h"}}"""),
        )
    }

    @Test
    fun `a body without a code gives none`() {
        assertNull(refusalCodeOf("""{"message":"no code here"}"""))
        assertNull(refusalCodeOf("""{"error":{"message":"no code inside"}}"""))
        assertNull(refusalCodeOf("""{"error":42}"""))
    }

    @Test
    fun `text that is not a JSON object gives none`() {
        assertNull(refusalCodeOf("<html>Bad gateway</html>"))
        assertNull(refusalCodeOf("[1,2]"))
        assertNull(refusalCodeOf(""))
        assertNull(refusalCodeOf(null))
    }
}
