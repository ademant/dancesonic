package org.dancesonic.player.studio.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BleFramingTest {
    @Test
    fun `a message is cut so every frame fits the MTU, with the last frame marked`() {
        val message = ByteArray(100) { it.toByte() }

        val frames = BleFraming.frames(message, mtu = 23)

        assertTrue(frames.all { it.size <= 23 - 3 })
        assertEquals(BleFraming.FIN, frames.last()[0])
        assertTrue(frames.dropLast(1).all { it[0] == 0.toByte() })
    }

    @Test
    fun `frames put back together give the original message`() {
        val message = ByteArray(1000) { (it * 7).toByte() }
        val reassembler = BleReassembler()

        var out: ByteArray? = null
        for (frame in BleFraming.frames(message, mtu = 247)) {
            out = reassembler.push(frame) ?: out
        }

        assertArrayEquals(message, out)
    }

    @Test
    fun `an empty message is one frame that ends it`() {
        val frames = BleFraming.frames(ByteArray(0), mtu = 100)

        assertEquals(1, frames.size)
        assertArrayEquals(ByteArray(0), BleReassembler().push(frames[0]))
    }

    @Test
    fun `a message is not complete until its last frame arrives`() {
        val reassembler = BleReassembler()

        assertNull(reassembler.push(byteArrayOf(0, 1, 2)))
        assertArrayEquals(byteArrayOf(1, 2, 3), reassembler.push(byteArrayOf(BleFraming.FIN, 3)))
    }

    @Test
    fun `a frame with unknown header bits is refused`() {
        assertThrows(BleStudioException::class.java) { BleReassembler().push(byteArrayOf(0x02, 1)) }
    }

    @Test
    fun `an empty frame is refused`() {
        assertThrows(BleStudioException::class.java) { BleReassembler().push(ByteArray(0)) }
    }

    @Test
    fun `a message larger than Studio sends is refused before it is cut`() {
        assertThrows(IllegalArgumentException::class.java) {
            BleFraming.frames(ByteArray(BleFraming.MAX_MESSAGE + 1), mtu = 247)
        }
    }
}
