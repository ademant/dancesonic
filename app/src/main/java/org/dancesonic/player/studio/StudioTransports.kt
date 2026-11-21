package org.dancesonic.player.studio

/** The links a paired Studio can be reached over, by the names Studio uses in `welcome.transport`. */
object StudioTransports {
    const val BLUETOOTH = "ble"
    const val RELAY = "relay"
    const val NETWORK = "network"

    /**
     * The order to try the links in when a session opens, the same as Studio's: Bluetooth, folksonic's relay, then the
     * local network (#104).
     */
    fun initial(): List<String> = listOf(BLUETOOTH, RELAY, NETWORK)

    /** The order after the link on [dropped] was lost: the other transports, in the usual order. */
    fun after(dropped: String): List<String> = initial() - dropped
}
