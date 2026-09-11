package dev.foldphase.sensors

import dev.foldphase.core.CoverHalf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCalibratorTest {

    /**
     * The point of the trust threshold: a user who has only wiggled the hinge must not end
     * up with a calibration claiming the device opens to 10°, which would make every tiny
     * movement drive the whole animation.
     */
    @Test
    fun `small movements do not produce a trusted range`() {
        val a = AutoCalibrator()
        listOf(90f, 92f, 95f, 93f, 91f).forEach { a.observe(it) }
        assertFalse(a.isTrusted)

        val stored = HingeCalibration.UNCALIBRATED
        assertEquals(stored, a.refine(stored))
    }

    @Test
    fun `a full sweep produces a trusted range`() {
        val a = AutoCalibrator()
        var angle = 3f
        while (angle <= 179f) {
            a.observe(angle)
            angle += 1f
        }
        assertTrue(a.isTrusted)

        val refined = a.refine(HingeCalibration.UNCALIBRATED)
        assertEquals(3f, refined.closedAngleDeg, 0.01f)
        assertEquals(179f, refined.openAngleDeg, 0.01f)
    }

    /**
     * Research §3.1: Samsung hinges have been reported reading above 180° when flat, and
     * the closed end is rarely 0. The learned range must reflect that rather than being
     * clamped back to a textbook 0–180.
     */
    @Test
    fun `learns a range that is not zero to one eighty`() {
        val a = AutoCalibrator()
        listOf(4.5f, 40f, 90f, 140f, 181.5f).forEach { a.observe(it) }
        val refined = a.refine(HingeCalibration.UNCALIBRATED)
        assertEquals(4.5f, refined.closedAngleDeg, 0.01f)
        assertEquals(181.5f, refined.openAngleDeg, 0.01f)
    }

    /** An explicit wizard calibration is the user's deliberate measurement. Never touch it. */
    @Test
    fun `never overrides an explicit calibration`() {
        val a = AutoCalibrator()
        listOf(0f, 90f, 180f).forEach { a.observe(it) }
        assertTrue(a.isTrusted)

        val wizard = HingeCalibration(
            closedAngleDeg = 5f,
            openAngleDeg = 175f,
            isCalibrated = true,
        )
        assertEquals(wizard, a.refine(wizard))
    }

    /** A provisional range must stay flagged as inferred, not masquerade as measured. */
    @Test
    fun `refined calibration is still marked uncalibrated`() {
        val a = AutoCalibrator()
        listOf(0f, 90f, 180f).forEach { a.observe(it) }
        assertFalse(a.refine(HingeCalibration.UNCALIBRATED).isCalibrated)
    }

    /** One glitched reading must not permanently stretch the range. */
    @Test
    fun `implausible readings are rejected`() {
        val a = AutoCalibrator()
        listOf(10f, 90f, 170f).forEach { a.observe(it) }
        val before = a.observedSpan

        assertFalse(a.observe(9999f))
        assertFalse(a.observe(-500f))
        assertFalse(a.observe(Float.NaN))
        assertEquals(before, a.observedSpan, 0.001f)
    }

    /** Extremes widen only. Noise cannot shrink the range or make it oscillate. */
    @Test
    fun `range only ever widens`() {
        val a = AutoCalibrator()
        a.observe(10f)
        a.observe(170f)
        a.observe(90f)
        a.observe(100f)
        assertEquals(10f, a.observedMin, 0.01f)
        assertEquals(170f, a.observedMax, 0.01f)
    }
}

class DisplayProfileTest {

    /** Classification must not depend on display ids, which Samsung reuses. */
    @Test
    fun `classifies cover and inner panels by aspect`() {
        val p = DisplayProfile()
        p.observe(1080, 2520) // Z Fold 7 cover: very tall
        p.observe(1968, 2184) // Z Fold 7 inner: near square

        assertTrue(p.hasCover)
        assertTrue(p.hasInner)
        assertTrue(p.isComplete)
        assertEquals(1080f / 2520f, p.coverAspect, 0.001f)
        assertEquals(1968f / 2184f, p.innerAspect, 0.001f)
    }

    /** Orientation must not flip the classification. */
    @Test
    fun `classification survives a rotated window`() {
        val p = DisplayProfile()
        p.observe(2520, 1080) // cover, landscape
        assertTrue(p.hasCover)
        assertFalse(p.hasInner)
    }

    /**
     * The app is on exactly one panel at a time, so a mapping that needed both before
     * improving on the default would only improve after the first fold — the fold it most
     * needs to get right.
     */
    @Test
    fun `derives a usable mapping from one panel only`() {
        val p = DisplayProfile()
        p.observe(1080, 2520)
        assertFalse(p.isComplete)

        val m = p.deriveMapping(coverHalf = CoverHalf.RIGHT)
        assertTrue(m.coverSceneRect.width in 0.09f..1f)
        assertTrue(m.coverSceneRect.right >= 0.999f)
    }

    @Test
    fun `repeat observations of the same panel report no change`() {
        val p = DisplayProfile()
        assertTrue(p.observe(1080, 2520))
        assertFalse(p.observe(1080, 2520))
    }

    @Test
    fun `degenerate sizes are ignored`() {
        val p = DisplayProfile()
        assertFalse(p.observe(0, 0))
        assertFalse(p.observe(-5, 100))
        assertFalse(p.hasCover || p.hasInner)
    }
}
