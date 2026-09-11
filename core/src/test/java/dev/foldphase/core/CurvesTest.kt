package dev.foldphase.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CurvesTest {

    @Test
    fun `remap clamps at both ends`() {
        assertEquals(0f, Curves.remap(0.1f, 0.2f, 0.8f), 1e-6f)
        assertEquals(1f, Curves.remap(0.9f, 0.2f, 0.8f), 1e-6f)
        assertEquals(0.5f, Curves.remap(0.5f, 0.2f, 0.8f), 1e-5f)
    }

    /**
     * A degenerate range must become a step function, not a division by zero. Tuning
     * sliders can easily be dragged into `start == end`, and a NaN there would propagate
     * into every uniform in the shader.
     */
    @Test
    fun `degenerate remap range does not produce NaN`() {
        val below = Curves.remap(0.3f, 0.5f, 0.5f)
        val above = Curves.remap(0.7f, 0.5f, 0.5f)
        assertTrue(below.isFinite() && above.isFinite())
        assertEquals(0f, below, 1e-6f)
        assertEquals(1f, above, 1e-6f)
    }

    /** An inverted range must still be finite rather than emitting a negative or NaN. */
    @Test
    fun `inverted remap range stays finite`() {
        for (v in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val r = Curves.remap(v, 0.8f, 0.2f)
            assertTrue("remap($v, 0.8, 0.2) = $r", r.isFinite() && r in 0f..1f)
        }
    }

    @Test
    fun `easing curves hit their endpoints exactly`() {
        CurveShape.entries.forEach { shape ->
            assertEquals("${shape.name} at 0", 0f, shape.apply(0f), 1e-4f)
            assertEquals("${shape.name} at 1", 1f, shape.apply(1f), 1e-4f)
        }
    }

    /**
     * Every shape must be monotonically non-decreasing. A non-monotonic ease would make
     * the animation briefly run backwards at some hinge angle, which is exactly the kind
     * of discontinuity the whole project is trying to avoid.
     */
    @Test
    fun `easing curves are monotonic`() {
        CurveShape.entries.forEach { shape ->
            var previous = shape.apply(0f)
            for (i in 1..1000) {
                val v = shape.apply(i / 1000f)
                assertTrue(
                    "${shape.name} decreased at t=${i / 1000f}: $previous -> $v",
                    v >= previous - 1e-4f,
                )
                previous = v
            }
        }
    }

    @Test
    fun `easing curves stay inside zero to one`() {
        CurveShape.entries.forEach { shape ->
            for (i in -50..150) {
                val v = shape.apply(i / 100f)
                assertTrue("${shape.name}(${i / 100f}) = $v", v.isFinite() && v in 0f..1f)
            }
        }
    }

    @Test
    fun `cubic bezier matches a known linear control set`() {
        // cubic-bezier(0.25, 0.25, 0.75, 0.75) is exactly the identity.
        for (i in 0..100) {
            val t = i / 100f
            assertEquals(t, Curves.cubicBezier(t, 0.25f, 0.25f, 0.75f, 0.75f), 2e-3f)
        }
    }

    /** The concealment envelope must be zero at both ends and peak in the middle. */
    @Test
    fun `bump is zero at the ends and one at the centre`() {
        assertEquals(0f, Curves.bump(0f), 1e-4f)
        assertEquals(0f, Curves.bump(1f), 1e-4f)
        assertEquals(1f, Curves.bump(0.5f), 1e-4f)
    }

    @Test
    fun `bumpAround is centred and bounded`() {
        val center = 0.58f
        val half = 0.11f
        assertEquals(1f, Curves.bumpAround(center, center, half), 1e-4f)
        assertEquals(0f, Curves.bumpAround(center - half, center, half), 1e-4f)
        assertEquals(0f, Curves.bumpAround(center + half, center, half), 1e-4f)
        assertEquals(0f, Curves.bumpAround(0f, center, half), 1e-4f)
        assertEquals(0f, Curves.bumpAround(1f, center, half), 1e-4f)
    }

    /** A zero-width window must not divide by zero. */
    @Test
    fun `bumpAround with zero width returns zero`() {
        assertEquals(0f, Curves.bumpAround(0.5f, 0.5f, 0f), 1e-6f)
    }

    @Test
    fun `smootherstep has zero first derivative at both ends`() {
        val eps = 1e-3f
        val dStart = (Curves.smootherstep(eps) - Curves.smootherstep(0f)) / eps
        val dEnd = (Curves.smootherstep(1f) - Curves.smootherstep(1f - eps)) / eps
        assertTrue("start slope $dStart", abs(dStart) < 0.02f)
        assertTrue("end slope $dEnd", abs(dEnd) < 0.02f)
    }
}

class SceneMappingTest {

    /** The cover viewport must be a strict subset of the scene, on the hinge side. */
    @Test
    fun `cover rect sits on the correct half`() {
        val right = SceneMapping.fromAspectRatios(0.43f, 0.90f, CoverHalf.RIGHT)
        assertTrue(right.coverSceneRect.right >= 0.999f)
        assertTrue(right.coverSceneRect.left > 0f)

        val left = SceneMapping.fromAspectRatios(0.43f, 0.90f, CoverHalf.LEFT)
        assertTrue(left.coverSceneRect.left <= 0.001f)
        assertTrue(left.coverSceneRect.right < 1f)
    }

    @Test
    fun `viewport interpolation spans cover to inner`() {
        val m = SceneMapping.Z_FOLD_7_DEFAULT
        assertEquals(m.coverSceneRect, m.viewportAt(0f))
        assertEquals(m.innerSceneRect, m.viewportAt(1f))
        val mid = m.viewportAt(0.5f)
        assertTrue(mid.width > m.coverSceneRect.width)
        assertTrue(mid.width < m.innerSceneRect.width)
    }

    /**
     * The sign convention is what the shader relies on to blur the seam rather than the
     * outer edge, so it is worth pinning down explicitly.
     */
    @Test
    fun `signed hinge distance is positive on the cover half`() {
        val right = SceneMapping.fromAspectRatios(0.43f, 0.90f, CoverHalf.RIGHT)
        assertTrue(right.signedHingeDistance(0.9f, 0.5f) > 0f)
        assertTrue(right.signedHingeDistance(0.1f, 0.5f) < 0f)

        val left = SceneMapping.fromAspectRatios(0.43f, 0.90f, CoverHalf.LEFT)
        assertTrue(left.signedHingeDistance(0.1f, 0.5f) > 0f)
        assertTrue(left.signedHingeDistance(0.9f, 0.5f) < 0f)
    }

    /** Absurd aspect ratios must not produce a degenerate or inverted rect. */
    @Test
    fun `extreme aspect ratios stay bounded`() {
        listOf(0.01f to 10f, 10f to 0.01f, 0f to 0f, 1f to 1f).forEach { (c, i) ->
            val m = SceneMapping.fromAspectRatios(c, i)
            val r = m.coverSceneRect
            assertTrue("width ${r.width} for aspects $c/$i", r.width in 0.09f..1.01f)
            assertTrue(r.left >= -1e-4f && r.right <= 1.0001f)
        }
    }
}
