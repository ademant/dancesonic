package org.dancesonic.player.studio

import org.junit.Assert.assertEquals
import org.junit.Test

class StudioTransportsTest {
    @Test
    fun `Bluetooth is tried first, the network second`() {
        assertEquals(listOf("ble", "network"), StudioTransports.initial())
    }

    @Test
    fun `after Bluetooth drops only the network is tried`() {
        assertEquals(listOf("network"), StudioTransports.after(StudioTransports.BLUETOOTH))
    }

    @Test
    fun `after the network drops only Bluetooth is tried`() {
        assertEquals(listOf("ble"), StudioTransports.after(StudioTransports.NETWORK))
    }
}
