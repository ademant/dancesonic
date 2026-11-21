package org.dancesonic.player.studio

import org.junit.Assert.assertEquals
import org.junit.Test

class StudioTransportsTest {
    @Test
    fun `Bluetooth is tried first, then folksonic's relay, then the network, as in Studio`() {
        assertEquals(listOf("ble", "relay", "network"), StudioTransports.initial())
    }

    @Test
    fun `after Bluetooth drops the relay is tried before the network`() {
        assertEquals(listOf("relay", "network"), StudioTransports.after(StudioTransports.BLUETOOTH))
    }

    @Test
    fun `after the relay drops Bluetooth is tried before the network`() {
        assertEquals(listOf("ble", "network"), StudioTransports.after(StudioTransports.RELAY))
    }

    @Test
    fun `after the network drops Bluetooth and the relay are tried`() {
        assertEquals(listOf("ble", "relay"), StudioTransports.after(StudioTransports.NETWORK))
    }
}
