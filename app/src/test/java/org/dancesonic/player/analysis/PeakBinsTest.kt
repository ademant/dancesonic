package org.dancesonic.player.analysis

import org.junit.Assert.assertEquals
import org.junit.Test

class PeakBinsTest {
    @Test
    fun `each bin keeps the loudest frame of its slice`() {
        val bins = PeakBins(bins = 2, totalFrames = 4)
        bins.add(frame = 0, peak = 100)
        bins.add(frame = 1, peak = 400)
        bins.add(frame = 2, peak = 200)
        bins.add(frame = 3, peak = 100)

        // Maxima are 400 and 200; the loudest (400) becomes 255 and the other is scaled to 127.
        assertEquals(listOf(255, 127), bins.scaled())
    }

    @Test
    fun `frames past the end fall into the last bin`() {
        val bins = PeakBins(bins = 3, totalFrames = 3)
        bins.add(frame = 10, peak = 50)

        assertEquals(listOf(0, 0, 255), bins.scaled())
    }

    @Test
    fun `a silent track gives all zeros`() {
        val bins = PeakBins(bins = 4, totalFrames = 8)
        bins.add(frame = 0, peak = 0)

        assertEquals(listOf(0, 0, 0, 0), bins.scaled())
    }

    @Test
    fun `an empty slice stays at zero beside louder ones`() {
        val bins = PeakBins(bins = 3, totalFrames = 9)
        bins.add(frame = 0, peak = 1000)
        bins.add(frame = 8, peak = 1000)

        assertEquals(listOf(255, 0, 255), bins.scaled())
    }

    @Test
    fun `peaks round-trip through the stored text`() {
        val peaks = listOf(0, 7, 255, 128)

        assertEquals(peaks, decodePeaks(encodePeaks(peaks)))
    }

    @Test
    fun `malformed stored text reads as no waveform`() {
        assertEquals(emptyList<Int>(), decodePeaks("1,x,3"))
        assertEquals(emptyList<Int>(), decodePeaks(""))
    }

    @Test
    fun `stored values out of range are clamped`() {
        assertEquals(listOf(0, 255), decodePeaks("-4,300"))
    }
}
