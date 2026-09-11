package dev.foldphase.engine

import dev.foldphase.core.FoldDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * These tests exist to lock in the acceptance criteria from brief §30 that can be checked
 * without hardware. They are not coverage for its own sake: each one corresponds to a
 * specific promise the project makes about how the animation behaves.
 */
class FoldAnimationEngineTest {

    private val engine = FoldAnimationEngine()

    /**
     * Acceptance criterion 2: "Pausing the hinge pauses the effect."
     *
     * The strongest possible form of this is that `evaluate` is a pure function of
     * progress — so holding the hinge cannot change the frame, because there is no other
     * input that varies with time.
     */
    @Test
    fun `evaluate is a pure function of progress`() {
        val first = engine.evaluate(0.27f)
        repeat(100) {
            val again = engine.evaluate(0.27f)
            assertEquals(first, again)
        }
    }

    /**
     * Acceptance criterion 3: "Reversing the hinge reverses the effect immediately."
     *
     * With `directionalBias = 0` (the default, per research §1.3), closing through a given
     * progress must render exactly what opening through it rendered.
     */
    @Test
    fun `closing is the mathematical reverse of opening`() {
        var p = 0.05f
        while (p <= 1f) {
            val opening = engine.evaluate(p, direction = FoldDirection.OPENING)
            val closing = engine.evaluate(p, direction = FoldDirection.CLOSING)
            assertEquals("at p=$p", opening, closing)
            p += 0.01f
        }
    }

    /** Brief §9 Phase A: below the deadband, nothing moves at all. */
    @Test
    fun `deadband produces an untouched cover frame`() {
        val tuning = FoldTuningPresets.APPLE_LIKE
        var p = 0f
        while (p <= tuning.deadbandEnd) {
            val s = engine.evaluate(p)
            assertEquals(1f, s.coverAlpha, 1e-6f)
            assertEquals(1f, s.coverScale, 1e-6f)
            assertEquals(0f, s.coverBlurDp, 1e-6f)
            assertEquals(0f, s.blackOverlayAlpha, 1e-6f)
            p += 0.005f
        }
    }

    /** The endpoints must be exactly clean, or resting open/closed would shimmer. */
    @Test
    fun `endpoints are exact`() {
        val closed = engine.evaluate(0f)
        assertEquals(1f, closed.coverAlpha, 1e-6f)
        assertEquals(0f, closed.innerAlpha, 1e-6f)

        val open = engine.evaluate(1f)
        assertEquals(0f, open.coverAlpha, 1e-6f)
        assertEquals(1f, open.innerAlpha, 1e-6f)
        assertEquals(0f, open.coverBlurDp, 1e-6f)
        assertEquals(0f, open.blackOverlayAlpha, 1e-6f)
    }

    /**
     * Acceptance criteria 4 and 5: slow and fast folding must both stay smooth.
     *
     * A discontinuity anywhere in the curve would read as a visible jump at some hinge
     * angle, so no property may step by more than a small amount across a 0.5% change in
     * progress.
     */
    @Test
    fun `no property jumps discontinuously across the range`() {
        var p = 0f
        var previous = engine.evaluate(0f)
        while (p <= 1f) {
            val current = engine.evaluate(p)
            assertSmooth("coverAlpha", previous.coverAlpha, current.coverAlpha, p)
            assertSmooth("innerAlpha", previous.innerAlpha, current.innerAlpha, p)
            assertSmooth("coverScale", previous.coverScale, current.coverScale, p)
            assertSmooth("innerScale", previous.innerScale, current.innerScale, p)
            assertSmooth("crossDissolve", previous.crossDissolve, current.crossDissolve, p)
            assertSmooth("blackOverlay", previous.blackOverlayAlpha, current.blackOverlayAlpha, p)
            assertSmooth("vignette", previous.vignetteIntensity, current.vignetteIntensity, p)
            assertSmooth("brightness", previous.coverBrightness, current.coverBrightness, p)
            // Blur is in dp and has a much larger range, so it gets its own tolerance.
            assertTrue(
                "coverBlurDp jumped at p=$p",
                abs(current.coverBlurDp - previous.coverBlurDp) < 3f,
            )
            previous = current
            p += 0.005f
        }
    }

    private fun assertSmooth(name: String, a: Float, b: Float, p: Float) {
        assertTrue("$name jumped from $a to $b at p=$p", abs(b - a) < 0.08f)
    }

    /**
     * The viewport must grow monotonically: content expands outward from the hinge and
     * never briefly contracts (research §4.1).
     */
    @Test
    fun `viewport width increases monotonically`() {
        var p = 0f
        var lastWidth = engine.evaluate(0f).viewport.width
        while (p <= 1f) {
            val w = engine.evaluate(p).viewport.width
            assertTrue("viewport shrank at p=$p ($lastWidth -> $w)", w >= lastWidth - 1e-4f)
            lastWidth = w
            p += 0.01f
        }
    }

    /**
     * The black veil is a *bump*, not a ramp: it must return to zero by the time the
     * device is open, or the inner display would stay permanently dimmed.
     */
    @Test
    fun `black veil returns to zero at both ends`() {
        assertEquals(0f, engine.evaluate(0f).blackOverlayAlpha, 1e-6f)
        assertEquals(0f, engine.evaluate(1f).blackOverlayAlpha, 1e-6f)
        // And it must actually peak somewhere in between, or it is doing nothing.
        val peak = (0..100).maxOf { engine.evaluate(it / 100f).blackOverlayAlpha }
        assertTrue("black veil never rises", peak > 0.01f)
    }

    /**
     * Research §1.2(B): the default preset must NOT blackout. This test is the guard
     * against someone "fixing" a flash by turning the default into a black frame, which
     * would quietly abandon the reference behaviour.
     */
    @Test
    fun `apple-like preset keeps the black veil subtle`() {
        val peak = (0..100).maxOf { engine.evaluate(it / 100f).blackOverlayAlpha }
        assertTrue("Apple-like preset went too dark: $peak", peak < 0.25f)
    }

    /** Velocity may deepen concealment but must never change geometry. */
    @Test
    fun `velocity affects concealment but not viewport`() {
        val still = engine.evaluate(0.5f, velocityDegPerSec = 0f)
        val fast = engine.evaluate(0.5f, velocityDegPerSec = 500f)
        assertEquals(still.viewport, fast.viewport)
        assertEquals(still.coverScale, fast.coverScale, 1e-6f)
        assertTrue("fast fold should not conceal less", fast.coverBlurDp >= still.coverBlurDp)
    }

    /** Every emitted value must be finite and inside its declared domain. */
    @Test
    fun `all outputs stay in range`() {
        var p = 0f
        while (p <= 1f) {
            val s = engine.evaluate(p, velocityDegPerSec = 300f)
            listOf(
                "coverAlpha" to s.coverAlpha,
                "innerAlpha" to s.innerAlpha,
                "iconAlpha" to s.iconAlpha,
                "crossDissolve" to s.crossDissolve,
                "blackOverlayAlpha" to s.blackOverlayAlpha,
                "vignetteIntensity" to s.vignetteIntensity,
                "hingeShadowIntensity" to s.hingeShadowIntensity,
                "coverBrightness" to s.coverBrightness,
                "coverSaturation" to s.coverSaturation,
            ).forEach { (name, v) ->
                assertTrue("$name = $v out of [0,1] at p=$p", v in 0f..1f)
            }
            assertTrue(s.coverBlurDp.isFinite() && s.coverBlurDp >= 0f)
            assertTrue(s.innerScale.isFinite() && s.innerScale > 0f)
            p += 0.01f
        }
    }

    /** Every shipped preset must satisfy the same invariants as the default. */
    @Test
    fun `every preset produces valid output across the range`() {
        FoldTuningPresets.ALL.forEach { (name, tuning) ->
            val e = FoldAnimationEngine(tuning)
            var p = 0f
            while (p <= 1f) {
                val s = e.evaluate(p)
                assertTrue("$name: coverAlpha out of range at $p", s.coverAlpha in 0f..1f)
                assertTrue("$name: innerAlpha out of range at $p", s.innerAlpha in 0f..1f)
                assertTrue("$name: blur negative at $p", s.coverBlurDp >= 0f)
                p += 0.02f
            }
        }
    }
}
