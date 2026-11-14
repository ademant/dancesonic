package org.dancesonic.player.playback.stretch

import org.dancesonic.player.analysis.Onsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StretchPlannerTest {
    private val rate = 44_100

    /** Strong onsets every [spacingMs] from [firstMs], [count] of them. */
    private fun onsets(
        spacingMs: Int,
        count: Int = 20,
        firstMs: Int = 500,
        strength: Int = 200,
    ): Onsets =
        checkNotNull(
            Onsets.of(List(count) { (firstMs + it * spacingMs).toLong() }, List(count) { strength }),
        )

    /** Output frames the plan maps [frame] to, as the native stretcher reads the map. */
    private fun StretchPlan.mapped(
        frame: Long,
        speed: Float,
    ): Double {
        if (breakIn.isEmpty() || frame >= breakIn.last()) {
            val lastIn = breakIn.lastOrNull() ?: 0L
            val lastOut = breakOut.lastOrNull() ?: 0.0
            return lastOut + (frame - lastIn) / speed.toDouble()
        }
        val i = breakIn.indexOfFirst { it > frame }
        val fraction = (frame - breakIn[i - 1]).toDouble() / (breakIn[i] - breakIn[i - 1])
        return breakOut[i - 1] + fraction * (breakOut[i] - breakOut[i - 1])
    }

    /** Slope of the map on the breakpoint interval that contains [frame]. */
    private fun StretchPlan.slopeAt(frame: Long): Double {
        val i = breakIn.indexOfFirst { it > frame }
        return (breakOut[i] - breakOut[i - 1]) / (breakIn[i] - breakIn[i - 1])
    }

    private fun msToFrames(ms: Int) = ms.toLong() * rate / 1_000

    @Test
    fun `no onsets, weak onsets or 1x give the uniform plan`() {
        assertSame(StretchPlan.UNIFORM, StretchPlanner.plan(null, 0, 0.6f, rate))
        assertSame(StretchPlan.UNIFORM, StretchPlanner.plan(Onsets.NONE, 0, 0.6f, rate))
        assertSame(StretchPlan.UNIFORM, StretchPlanner.plan(onsets(300), 0, 1f, rate))
        assertTrue(StretchPlanner.plan(onsets(300, strength = 10), 0, 0.6f, rate).breakIn.isEmpty())
    }

    @Test
    fun `the map starts at zero and never runs backwards`() {
        for (speed in listOf(0.5f, 0.8f, 1.25f, 1.5f)) {
            val plan = StretchPlanner.plan(onsets(260), 0, speed, rate)
            assertEquals(0L, plan.breakIn.first())
            assertEquals(0.0, plan.breakOut.first(), 0.0)
            for (i in 1 until plan.breakIn.size) {
                assertTrue(plan.breakIn[i] > plan.breakIn[i - 1])
                assertTrue(plan.breakOut[i] >= plan.breakOut[i - 1])
            }
        }
    }

    @Test
    fun `segment ends sit exactly on the nominal timeline, so nothing drifts`() {
        for (speed in listOf(0.5f, 0.6f, 0.8f, 1.5f)) {
            val plan = StretchPlanner.plan(onsets(300), 0, speed, rate)
            // Every third breakpoint after the first is a segment end: window start, window end, segment end.
            for (i in 3 until plan.breakIn.size step 3) {
                assertEquals(plan.breakIn[i] / speed.toDouble(), plan.breakOut[i], 1e-6)
            }
            // Past the last onset the map is nominal for good.
            val late = msToFrames(60_000)
            assertEquals(late / speed.toDouble(), plan.mapped(late, speed), 1e-6)
        }
    }

    @Test
    fun `the average speed over each segment is the chosen speed`() {
        val speed = 0.6f
        val plan = StretchPlanner.plan(onsets(300), 0, speed, rate)
        for (i in 3 until plan.breakIn.size step 3) {
            val inFrames = plan.breakIn[i] - plan.breakIn[i - 3]
            val outFrames = plan.breakOut[i] - plan.breakOut[i - 3]
            assertEquals(speed.toDouble(), inFrames / outFrames, 1e-6)
        }
    }

    @Test
    fun `attacks play at the original speed when the gaps can make up for it`() {
        val speed = 0.6f
        val plan = StretchPlanner.plan(onsets(400), 0, speed, rate)
        val blockMs = (plan.blockSamples * 1_000L / rate).toInt()
        for (k in 1 until 19) {
            val onset = msToFrames(500 + k * 400)
            assertEquals(1.0, plan.slopeAt(onset), 1e-6)
            // Just before the window the gap stretches more than nominal, within the cap.
            val gap = plan.slopeAt(onset - msToFrames(blockMs) * 6 / 10)
            assertTrue(gap > 1 / speed)
            assertTrue(gap <= StretchPlanner.MAX_GAP_FACTOR / speed + 1e-9)
        }
    }

    @Test
    fun `when speeding up, attacks also keep the original speed and the gaps go faster`() {
        val speed = 1.5f
        val plan = StretchPlanner.plan(onsets(400), 0, speed, rate)
        val onset = msToFrames(500 + 5 * 400)
        assertEquals(1.0, plan.slopeAt(onset), 1e-6)
        val gap = plan.slopeAt(onset + msToFrames(150))
        assertTrue(gap < 1 / speed)
        assertTrue(gap >= 1 / (speed * StretchPlanner.MAX_GAP_FACTOR) - 1e-9)
    }

    @Test
    fun `onsets close together merge, and the windows give way so the gaps stay within the cap`() {
        val speed = 0.5f
        val plan = StretchPlanner.plan(onsets(spacingMs = 70, count = 40), 0, speed, rate)
        for (i in 1 until plan.breakIn.size) {
            val slope = (plan.breakOut[i] - plan.breakOut[i - 1]) / (plan.breakIn[i] - plan.breakIn[i - 1])
            assertTrue("slope $slope", slope >= 1.0 - 1e-9)
            assertTrue("slope $slope", slope <= StretchPlanner.MAX_GAP_FACTOR / speed + 1e-9)
        }
        // The merged run is still on the nominal timeline at its end.
        assertEquals(plan.breakIn.last() / speed.toDouble(), plan.breakOut.last(), 1e-6)
    }

    @Test
    fun `a stream that starts mid-song maps from its own start and skips earlier onsets`() {
        val speed = 0.6f
        val startUs = 2_650_000L
        val plan = StretchPlanner.plan(onsets(300), startUs, speed, rate)
        assertEquals(0L, plan.breakIn.first())
        // The first window is the onset at 2.9 s: 250 ms into the stream, minus the window's lead.
        val firstOnset = msToFrames(250)
        assertEquals(1.0, plan.slopeAt(firstOnset), 1e-6)
        for (i in 3 until plan.breakIn.size step 3) {
            assertEquals(plan.breakIn[i] / speed.toDouble(), plan.breakOut[i], 1e-6)
        }
    }

    @Test
    fun `percussive tracks get shorter blocks and legato tracks longer ones`() {
        val percussive = StretchPlanner.blockSeconds(onsets(spacingMs = 200))
        val middle = StretchPlanner.blockSeconds(onsets(spacingMs = 500))
        val legato = StretchPlanner.blockSeconds(onsets(spacingMs = 2_000))
        val few = StretchPlanner.blockSeconds(onsets(spacingMs = 200, count = 3))
        assertTrue(percussive < middle)
        assertTrue(middle < legato)
        assertEquals(middle, few, 0.0)
    }

    @Test
    fun `the hop is a quarter of the block`() {
        val plan = StretchPlanner.plan(onsets(300), 0, 0.6f, rate)
        assertEquals(plan.blockSamples / 4, plan.intervalSamples)
    }
}
