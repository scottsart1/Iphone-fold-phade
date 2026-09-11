package dev.foldphase.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeStatsTest {

    private fun stats(distinct: Int, events: Long) = ProbeStats(
        sensorType = 36,
        name = "hinge_angle",
        stringType = "android.sensor.hinge_angle",
        declaredResolution = 90f,
        declaredMaxRange = 180f,
        isWakeUp = true,
        eventCount = events,
        distinctValues = distinct,
    )

    /**
     * Refusing to judge early matters: calling a sensor quantised after four events would
     * be worse than saying nothing, because it would send someone off to a vendor sensor
     * they did not need.
     */
    @Test
    fun `withholds a verdict until there is enough data`() {
        assertEquals(ProbeStats.Verdict.INSUFFICIENT_DATA, stats(2, 3).verdict)
        assertEquals(ProbeStats.Verdict.INSUFFICIENT_DATA, stats(50, 5).verdict)
    }

    /**
     * The Fold 7 case: if the standard hinge sensor really does report only 0/90/180, a
     * full slow fold yields three distinct values across many events.
     */
    @Test
    fun `three detent values across many events reads as quantised`() {
        assertEquals(ProbeStats.Verdict.QUANTISED, stats(3, 200).verdict)
    }

    @Test
    fun `plenty of distinct values reads as continuous`() {
        assertEquals(ProbeStats.Verdict.CONTINUOUS, stats(85, 200).verdict)
    }

    @Test
    fun `an in-between count reads as coarse rather than either extreme`() {
        assertEquals(ProbeStats.Verdict.COARSE, stats(12, 200).verdict)
    }

    @Test
    fun `span is computed from observed extremes and is zero when unseen`() {
        val seen = stats(50, 100).copy(minValue = 4.5f, maxValue = 181.5f)
        assertEquals(177f, seen.span, 0.01f)
        assertEquals(0f, stats(0, 0).span, 0.01f)
    }

    /** A data class holding a FloatArray needs hand-written equality to behave. */
    @Test
    fun `equality compares live values by content`() {
        val a = stats(10, 50).copy(values = floatArrayOf(1f, 2f, 3f))
        val b = stats(10, 50).copy(values = floatArrayOf(1f, 2f, 3f))
        val c = stats(10, 50).copy(values = floatArrayOf(9f, 2f, 3f))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a != c)
    }
}

class AutoCalibratorUnitsTest {

    /**
     * A vendor fold sensor may report a normalised 0…1. With the degree-based threshold a
     * span of 1.0 would never be trusted, so the range would stay permanently provisional
     * and the animation would run on a wrong calibration forever.
     */
    @Test
    fun `a normalised sensor can still reach a trusted range`() {
        val a = AutoCalibrator()
        a.configureForRange(1.0f)

        var v = 0f
        while (v <= 1.0f) {
            a.observe(v)
            v += 0.01f
        }
        assertTrue("span ${a.observedSpan} was not trusted", a.isTrusted)

        val refined = a.refine(HingeCalibration.UNCALIBRATED)
        assertEquals(0f, refined.closedAngleDeg, 0.02f)
        assertEquals(1f, refined.openAngleDeg, 0.02f)
    }

    /**
     * Checked via the extremes rather than `observe`'s return value: that return means
     * "the trusted range changed", which is false for the first sample regardless of
     * whether the value was accepted.
     */
    @Test
    fun `guard rails scale to the configured range`() {
        val a = AutoCalibrator()
        a.configureForRange(1.0f)

        a.observe(0.25f)
        a.observe(0.75f)
        assertEquals(0.25f, a.observedMin, 0.001f)
        assertEquals(0.75f, a.observedMax, 0.001f)

        // Far outside a 0..1 sensor's range: must not stretch the extremes.
        a.observe(50f)
        a.observe(-50f)
        assertEquals(0.25f, a.observedMin, 0.001f)
        assertEquals(0.75f, a.observedMax, 0.001f)
    }

    @Test
    fun `degree defaults are unchanged when not configured`() {
        val a = AutoCalibrator()
        listOf(0f, 90f, 180f).forEach { a.observe(it) }
        assertTrue(a.isTrusted)
    }
}
