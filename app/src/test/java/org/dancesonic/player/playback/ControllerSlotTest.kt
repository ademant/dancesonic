package org.dancesonic.player.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The player's connection holder, reset on disconnect and server switch (#130). */
@OptIn(ExperimentalCoroutinesApi::class)
class ControllerSlotTest {
    private class FakeController(
        val id: Int,
    ) {
        var released = false
    }

    @Test
    fun `concurrent first calls share one connection`() =
        runTest {
            var built = 0
            val slot = ControllerSlot { FakeController(++built) }

            val a = async { slot.get() }
            val b = async { slot.get() }

            assertSame(a.await(), b.await())
            assertEquals(1, built)
        }

    @Test
    fun `a reset during a pending connect closes that connection when it opens`() =
        runTest(StandardTestDispatcher()) {
            val gate = CompletableDeferred<Unit>()
            val slot =
                ControllerSlot {
                    gate.await()
                    FakeController(1)
                }
            val pending = async { slot.get() }
            advanceUntilIdle()
            val generation = slot.generation

            val reset = launch { slot.reset { it.released = true } }
            advanceUntilIdle()
            assertFalse("the reset waits for the pending connect", reset.isCompleted)
            assertFalse("work started before the reset is stale at once", slot.isCurrent(generation))

            gate.complete(Unit)
            advanceUntilIdle()

            assertTrue(pending.await().released)
            assertNull(slot.connection)
        }

    @Test
    fun `a connect after a reset opens a new connection`() =
        runTest {
            var built = 0
            val slot = ControllerSlot { FakeController(++built) }
            val first = slot.get()

            slot.reset { it.released = true }
            val second = slot.get()

            assertTrue(first.released)
            assertFalse(second.released)
            assertEquals(2, second.id)
        }

    @Test
    fun `repeated resets are safe and tear down once`() =
        runTest {
            val slot = ControllerSlot { FakeController(1) }
            slot.get()
            var teardowns = 0

            slot.reset { teardowns++ }
            slot.reset { teardowns++ }
            slot.reset { teardowns++ }

            assertEquals(1, teardowns)
            assertNull(slot.connection)
        }

    @Test
    fun `a reset with nothing connected only marks earlier work stale`() =
        runTest {
            val slot = ControllerSlot { FakeController(1) }
            val generation = slot.generation

            slot.reset { error("nothing to tear down") }

            assertFalse(slot.isCurrent(generation))
            assertTrue(slot.isCurrent(slot.generation))
        }
}
