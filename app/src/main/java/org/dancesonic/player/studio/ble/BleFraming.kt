package org.dancesonic.player.studio.ble

/**
 * Messages over the Bluetooth link. A message is cut into frames that fit one GATT write or notification. Each frame
 * is a header byte followed by a chunk; bit 0 of the header marks the last frame. Same rules as Studio's `ble.rs`.
 */
object BleFraming {
    /** The header bit that marks the last frame of a message. */
    const val FIN: Byte = 0x01

    /** The smallest ATT MTU, the default before the link negotiates a larger one. */
    const val MIN_MTU = 23

    /** The largest message after reassembly: a Studio message plus its Noise tag. */
    const val MAX_MESSAGE = 16 * 1024 + 16

    /** ATT's own header is three bytes; our header is one. */
    private const val HEADER_OVERHEAD = 4

    /** Cuts [message] into frames for a link whose ATT MTU is [mtu]. Every frame fits one write. */
    fun frames(
        message: ByteArray,
        mtu: Int,
    ): List<ByteArray> {
        require(message.size <= MAX_MESSAGE) { "a message of ${message.size} bytes is too large for Bluetooth" }
        val chunk = mtu.coerceAtLeast(MIN_MTU) - HEADER_OVERHEAD
        val out = ArrayList<ByteArray>()
        var offset = 0
        do {
            val end = minOf(offset + chunk, message.size)
            val last = end == message.size
            val frame = ByteArray(1 + end - offset)
            frame[0] = if (last) FIN else 0
            message.copyInto(frame, destinationOffset = 1, startIndex = offset, endIndex = end)
            out += frame
            offset = end
        } while (offset < message.size)
        return out
    }
}

/** Puts the frames of one message back together. */
class BleReassembler {
    private val buffer = java.io.ByteArrayOutputStream()

    /** Adds one frame. Returns the whole message when this frame ends it, otherwise null. */
    fun push(frame: ByteArray): ByteArray? {
        if (frame.isEmpty()) throw BleStudioException("an empty frame")
        val header = frame[0]
        if (header.toInt() and BleFraming.FIN.toInt().inv() != 0) {
            throw BleStudioException("a frame header has unknown bits")
        }
        if (buffer.size() + frame.size - 1 > BleFraming.MAX_MESSAGE) throw BleStudioException("a message is too large")
        buffer.write(frame, 1, frame.size - 1)
        if (header.toInt() and BleFraming.FIN.toInt() == 0) return null
        return buffer.toByteArray().also { buffer.reset() }
    }
}

/** Why a Bluetooth connection to Studio could not be opened or kept: framing, the handshake, or Studio's identity. */
class BleStudioException(message: String, cause: Throwable? = null) : Exception(message, cause)
