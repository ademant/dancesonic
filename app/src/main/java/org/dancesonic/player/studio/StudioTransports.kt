package org.dancesonic.player.studio

/** The two links a paired Studio can be reached over, by the names Studio uses in `welcome.transport`. */
object StudioTransports {
    const val BLUETOOTH = "ble"
    const val NETWORK = "network"

    /** The order to try the links in when a session opens: Bluetooth first, the network as the fallback. */
    fun initial(): List<String> = listOf(BLUETOOTH, NETWORK)

    /** The order after the link on [dropped] was lost: the other transport only. */
    fun after(dropped: String): List<String> = if (dropped == BLUETOOTH) listOf(NETWORK) else listOf(BLUETOOTH)
}
