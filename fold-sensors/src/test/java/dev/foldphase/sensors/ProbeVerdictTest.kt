package dev.foldphase.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verdict behaviour for the case actually measured on hardware.
 *
 * The Galaxy Z Fold 7's `android.sensor.hinge_angle` reports 0, 90 and 180 — three values
 * spanning its whole declared range. A quantised sensor emits very few events per fold
 * (one per detent crossed), so an event-count gate alone would withhold the verdict for
 * several folds while the evidence was already conclusive.
 */
class ProbeVerdictTest {

    private fun hinge(distinct: Int, events: Long, min: Float, max: Float) = ProbeStats(
        sensorType = 36,
        name = "hinge_angle",
        stringType = "android.sensor.hinge_angle",
        declaredResolution = 90f,
        declaredMaxRange = 180f,
        isWakeUp = true,
        eventCount = events,
        minValue = min,
        maxValue = max,
        distinctValues = distinct,
    )

    /** The exact observation from the device: 3 values, full range, only 9 events. */
    @Test
    fun `three values across the full range is conclusive even with few events`() {
        val stats = hinge(distinct = 3, events = 9, min = 0f, max = 180f)
        assertTrue(stats.spansMostOfRange)
        assertEquals(ProbeStats.Verdict.QUANTISED, stats.verdict)
    }

    /** Few values over a narrow slice proves nothing — the hinge barely moved. */
    @Test
    fun `three values over a narrow range is not yet conclusive`() {
        val stats = hinge(distinct = 3, events = 9, min = 0f, max = 20f)
        assertFalse(stats.spansMostOfRange)
        assertEquals(ProbeStats.Verdict.INSUFFICIENT_DATA, stats.verdict)
    }

    /** Full range with many values is the healthy case and must not be misread. */
    @Test
    fun `many values across the full range reads as continuous`() {
        val stats = hinge(distinct = 140, events = 400, min = 0f, max = 180f)
        assertEquals(ProbeStats.Verdict.CONTINUOUS, stats.verdict)
    }

    /** A sensor that is listed but never fires — Samsung's gated vendor sensors. */
    @Test
    fun `a silent sensor is flagged and never judged`() {
        val silent = ProbeStats(
            sensorType = 65686,
            name = "Folding Angle",
            stringType = "com.samsung.sensor.folding_angle",
            declaredResolution = 0.01f,
            declaredMaxRange = 1f,
            isWakeUp = false,
            eventCount = 0,
        )
        assertTrue(silent.isSilent)
        assertEquals(ProbeStats.Verdict.INSUFFICIENT_DATA, silent.verdict)
    }

    /** A zero declared range must not make every reading look like full coverage. */
    @Test
    fun `an unknown declared range is never treated as spanned`() {
        val stats = hinge(distinct = 2, events = 50, min = 0f, max = 1f)
            .copy(declaredMaxRange = 0f)
        assertFalse(stats.spansMostOfRange)
    }
}
