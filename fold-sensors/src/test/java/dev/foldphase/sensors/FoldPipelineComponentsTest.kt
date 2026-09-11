package dev.foldphase.sensors

import dev.foldphase.core.FoldDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class HingeCalibrationTest {

    /** Brief §4: normalised progress is `(angle - closed) / (open - closed)`, clamped. */
    @Test
    fun `progress normalises and clamps`() {
        val c = HingeCalibration(closedAngleDeg = 2f, openAngleDeg = 178f, isCalibrated = true)
        assertEquals(0f, c.progressFor(2f), 1e-5f)
        assertEquals(1f, c.progressFor(178f), 1e-5f)
        assertEquals(0.5f, c.progressFor(90f), 1e-3f)
        assertEquals(0f, c.progressFor(-50f), 1e-5f)
        assertEquals(1f, c.progressFor(500f), 1e-5f)
    }

    /**
     * Research §3.1: Samsung hardware has been reported reading past 180° when flat, and
     * the closed end is often not 0. A non-0/180 calibration must work normally.
     */
    @Test
    fun `handles a range that is not zero to one eighty`() {
        val c = HingeCalibration(closedAngleDeg = 4.5f, openAngleDeg = 181.5f, isCalibrated = true)
        assertEquals(0f, c.progressFor(4.5f), 1e-5f)
        assertEquals(1f, c.progressFor(181.5f), 1e-5f)
        assertEquals(177f, c.spanDeg, 1e-3f)
    }

    /** A degenerate calibration must not divide by zero or emit NaN. */
    @Test
    fun `degenerate range cannot produce NaN`() {
        val c = HingeCalibration(closedAngleDeg = 90f, openAngleDeg = 90f)
        val p = c.progressFor(90f)
        assertTrue("progress was $p", p.isFinite() && p in 0f..1f)
        assertTrue(c.spanDeg >= HingeCalibration.MIN_SPAN)
    }

    @Test
    fun `angleFor inverts progressFor`() {
        val c = HingeCalibration(closedAngleDeg = 3f, openAngleDeg = 179f, isCalibrated = true)
        for (i in 0..100) {
            val p = i / 100f
            assertEquals(p, c.progressFor(c.angleFor(p)), 1e-4f)
        }
    }

    @Test
    fun `deadband is derived from measured noise and always bounded`() {
        val quiet = HingeCalibration(noiseStdDevDeg = 0f, observedResolutionDeg = 0f)
        assertTrue(quiet.noiseDeadbandProgress() >= 0.001f)

        val noisy = HingeCalibration(noiseStdDevDeg = 40f, observedResolutionDeg = 40f)
        assertTrue(noisy.noiseDeadbandProgress() <= 0.05f)
    }
}

class OneEuroFilterTest {

    /**
     * The whole justification for One Euro (brief §5): it must crush jitter on a still
     * signal while still tracking a fast one. A fixed-cutoff filter cannot do both, so
     * this test asserts both at once.
     */
    @Test
    fun `suppresses jitter on a still signal`() {
        val filter = OneEuroFilter()
        val rng = Random(42)
        var t = 0L
        var maxDeviation = 0f

        // Warm up, then measure.
        repeat(400) { i ->
            val noisy = 90f + (rng.nextFloat() - 0.5f) * 0.4f
            val out = filter.filter(noisy, t)
            if (i > 100) maxDeviation = maxOf(maxDeviation, abs(out - 90f))
            t += 8_000_000L
        }
        assertTrue("filtered output deviated by $maxDeviation", maxDeviation < 0.15f)
    }

    @Test
    fun `tracks a fast ramp without excessive lag`() {
        val filter = OneEuroFilter()
        var t = 0L
        var angle = 0f
        var output = 0f
        // 180 degrees in 300 ms - a deliberate snap-open.
        repeat(38) {
            angle += 180f / 38f
            output = filter.filter(angle, t)
            t += 8_000_000L
        }
        assertTrue("lag was ${angle - output}°", abs(angle - output) < 18f)
    }

    @Test
    fun `does not divide by zero on duplicate timestamps`() {
        val filter = OneEuroFilter()
        filter.filter(10f, 1_000L)
        val out = filter.filter(20f, 1_000L)
        assertTrue("got $out", out.isFinite())
    }

    @Test
    fun `reset clears state`() {
        val filter = OneEuroFilter()
        filter.filter(100f, 0L)
        filter.filter(120f, 8_000_000L)
        filter.reset()
        assertEquals(5f, filter.filter(5f, 0L), 1e-4f)
    }
}

class VelocityEstimatorTest {

    @Test
    fun `estimates a constant slope`() {
        val est = VelocityEstimator()
        var t = 0L
        var angle = 0f
        // 100 deg/s sampled at 125 Hz.
        repeat(20) {
            est.add(angle, t)
            angle += 0.8f
            t += 8_000_000L
        }
        assertEquals(100f, est.velocityDegPerSec, 8f)
    }

    @Test
    fun `sign distinguishes opening from closing`() {
        val opening = VelocityEstimator()
        val closing = VelocityEstimator()
        var t = 0L
        repeat(20) { i ->
            opening.add(i * 0.8f, t)
            closing.add(100f - i * 0.8f, t)
            t += 8_000_000L
        }
        assertTrue(opening.velocityDegPerSec > 10f)
        assertTrue(closing.velocityDegPerSec < -10f)
    }

    /**
     * A hinge that stops emits nothing, so velocity must decay on `tick` alone. Without
     * this, a stopped hinge would keep its last velocity forever and the state machine
     * would never leave OPENING.
     */
    @Test
    fun `velocity decays to zero when samples stop`() {
        val est = VelocityEstimator()
        var t = 0L
        repeat(20) { i ->
            est.add(i * 0.8f, t)
            t += 8_000_000L
        }
        assertTrue(est.velocityDegPerSec > 10f)
        // Advance well past the regression window with no new samples.
        t += 500_000_000L
        assertEquals(0f, est.tick(t), 1e-3f)
    }

    @Test
    fun `quantised input does not produce wild spikes`() {
        val est = VelocityEstimator()
        var t = 0L
        var last = 0f
        var maxV = 0f
        // 1-degree quantisation on a 60 deg/s true rate.
        repeat(60) { i ->
            val trueAngle = i * 0.5f
            val quantised = kotlin.math.floor(trueAngle)
            est.add(quantised, t)
            if (i > 5) maxV = maxOf(maxV, est.velocityDegPerSec)
            last = quantised
            t += 8_000_000L
        }
        assertTrue("spiked to $maxV deg/s on a 60 deg/s signal", maxV < 200f)
    }
}

class FoldStateMachineTest {

    /**
     * Brief §6: "Do not rapidly bounce between opening and closing because of ±0.2°
     * sensor noise." This simulates exactly that and asserts the direction never commits.
     */
    @Test
    fun `noise around zero velocity does not flip direction`() {
        val sm = FoldStateMachine()
        val rng = Random(7)
        var t = 0L
        var flips = 0
        var previous = sm.direction

        repeat(500) {
            // Noise-driven velocity: a few deg/s either way, below the enter threshold.
            val v = (rng.nextFloat() - 0.5f) * 8f
            sm.update(0.5f, v, t, 0.58f, 0.11f)
            if (sm.direction != previous) flips++
            previous = sm.direction
            t += 8_000_000L
        }
        assertEquals("direction flipped $flips times on noise", 0, flips)
    }

    @Test
    fun `commits to opening on sustained positive velocity`() {
        val sm = FoldStateMachine()
        var t = 0L
        repeat(20) {
            sm.update(0.3f, 60f, t, 0.58f, 0.11f)
            t += 8_000_000L
        }
        assertEquals(FoldDirection.OPENING, sm.direction)
    }

    @Test
    fun `commits to closing on sustained negative velocity`() {
        val sm = FoldStateMachine()
        var t = 0L
        repeat(20) {
            sm.update(0.7f, -60f, t, 0.58f, 0.11f)
            t += 8_000_000L
        }
        assertEquals(FoldDirection.CLOSING, sm.direction)
    }

    /** Reversal must be tracked, just not on a single noisy sample. */
    @Test
    fun `reversal is picked up promptly`() {
        val sm = FoldStateMachine()
        var t = 0L
        repeat(20) { sm.update(0.5f, 80f, t, 0.58f, 0.11f); t += 8_000_000L }
        assertEquals(FoldDirection.OPENING, sm.direction)

        // Reverse hard. Dwell is 24 ms, so ~3 frames at 120 Hz should be enough.
        repeat(6) { sm.update(0.5f, -80f, t, 0.58f, 0.11f); t += 8_000_000L }
        assertEquals(FoldDirection.CLOSING, sm.direction)
    }

    @Test
    fun `enters handoff state inside the concealment window`() {
        val sm = FoldStateMachine()
        var t = 0L
        repeat(20) { sm.update(0.58f, 60f, t, 0.58f, 0.11f); t += 8_000_000L }
        assertTrue(sm.state.isHandoff)
        assertEquals(dev.foldphase.core.FoldState.OPENING_HANDOFF, sm.state)
    }

    @Test
    fun `error recovery latches until cleared`() {
        val sm = FoldStateMachine()
        sm.enterErrorRecovery()
        sm.update(0.5f, 60f, 0L, 0.58f, 0.11f)
        assertEquals(dev.foldphase.core.FoldState.ERROR_RECOVERY, sm.state)
        sm.clearErrorRecovery()
        sm.update(0.5f, 60f, 8_000_000L, 0.58f, 0.11f)
        assertTrue(sm.state != dev.foldphase.core.FoldState.ERROR_RECOVERY)
    }
}

class HandoffLearnerTest {

    @Test
    fun `returns nothing before any observation`() {
        assertNull(HandoffLearner().estimate(opening = true))
    }

    @Test
    fun `learns the switch point from display changes`() {
        val learner = HandoffLearner()
        // First call establishes the baseline; it is not itself a transition.
        assertNull(learner.observe(displayId = 0, isInner = false, progress = 0.1f))
        assertNotNull(learner.observe(displayId = 1, isInner = true, progress = 0.62f))
        assertEquals(0.62f, learner.estimate(opening = true)!!, 1e-3f)
    }

    /** A median, so one late first-boot observation cannot drag the estimate. */
    @Test
    fun `median rejects an outlier`() {
        val learner = HandoffLearner()
        learner.observe(0, false, 0.1f)
        listOf(0.60f, 0.61f, 0.90f, 0.62f, 0.61f).forEach { p ->
            learner.observe(1, true, p)
            learner.observe(0, false, 0.1f)
        }
        val est = learner.estimate(opening = true)!!
        assertTrue("estimate $est was dragged by the outlier", est in 0.58f..0.64f)
    }

    /** Switches recorded at the extremes are app launches, not hinge crossings. */
    @Test
    fun `ignores implausible observations at the extremes`() {
        val learner = HandoffLearner()
        learner.observe(0, false, 0.5f)
        assertNull(learner.observe(1, true, 0.001f))
        assertNull(learner.observe(0, false, 0.999f))
        assertEquals(0, learner.observationCount)
    }

    @Test
    fun `seeding gives a usable estimate before any live observation`() {
        val learner = HandoffLearner()
        learner.seed(0.55f)
        assertEquals(0.55f, learner.estimate(opening = true)!!, 1e-3f)
    }
}
