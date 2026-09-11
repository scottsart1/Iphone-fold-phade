package dev.foldphase.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The geometry behind [DualAccelHingeSource], verified independently of Android.
 *
 * `DualAccelHingeSource` itself needs a `SensorManager`, so the maths is re-implemented
 * here against the same specification. That is a deliberate trade: it catches a wrong
 * formula, which is the likely error, while a mistyped constant would still need
 * hardware. The alternative — no coverage of the maths at all — is worse, since this is
 * the one part of the fallback that cannot be eyeballed.
 */
class DihedralMathTest {

    private val g = 9.81f

    /** Projection of [v] onto the plane perpendicular to unit axis [h]. */
    private fun projectOut(v: FloatArray, h: FloatArray): FloatArray {
        val d = v[0] * h[0] + v[1] * h[1] + v[2] * h[2]
        return floatArrayOf(v[0] - d * h[0], v[1] - d * h[1], v[2] - d * h[2])
    }

    private fun mag(v: FloatArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    private fun dihedral(a: FloatArray, b: FloatArray, h: FloatArray): Float {
        val p1 = projectOut(a, h)
        val p2 = projectOut(b, h)
        val m1 = mag(p1)
        val m2 = mag(p2)
        if (m1 < 1e-4f || m2 < 1e-4f) return Float.NaN
        val cos = ((p1[0] * p2[0] + p1[1] * p2[1] + p1[2] * p2[2]) / (m1 * m2))
            .coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cos).toDouble()).toFloat()
    }

    /**
     * Simulate the two halves.
     *
     * The hinge runs along Y. Half one is fixed with gravity in its XZ plane; half two is
     * rotated about Y by the fold angle. This is the device lying with the fold line
     * horizontal — the normal way to open one.
     */
    private fun halves(foldDeg: Float): Pair<FloatArray, FloatArray> {
        val a1 = floatArrayOf(0f, 0f, -g)
        val r = Math.toRadians(foldDeg.toDouble())
        val a2 = floatArrayOf(
            (-g * sin(r)).toFloat(),
            0f,
            (-g * cos(r)).toFloat(),
        )
        return a1 to a2
    }

    private val hingeY = floatArrayOf(0f, 1f, 0f)

    @Test
    fun `flat device gives a zero dihedral`() {
        val (a1, a2) = halves(0f)
        assertEquals(0f, dihedral(a1, a2, hingeY), 0.01f)
    }

    @Test
    fun `folded right back gives one eighty`() {
        val (a1, a2) = halves(180f)
        assertEquals(180f, dihedral(a1, a2, hingeY), 0.01f)
    }

    @Test
    fun `ninety degrees reads as ninety`() {
        val (a1, a2) = halves(90f)
        assertEquals(90f, dihedral(a1, a2, hingeY), 0.01f)
    }

    /**
     * The property the animation actually depends on: monotonic and finely resolved.
     * This is exactly what the hardware hinge sensor fails to provide.
     */
    @Test
    fun `angle is monotonic and continuous across the full sweep`() {
        var previous = dihedral(halves(0f).first, halves(0f).second, hingeY)
        val seen = mutableSetOf<Int>()
        var deg = 1f
        while (deg <= 180f) {
            val (a1, a2) = halves(deg)
            val d = dihedral(a1, a2, hingeY)
            assertTrue("went backwards at $deg: $previous -> $d", d >= previous - 0.01f)
            // Hundredths of a degree, to show the resolution genuinely available.
            seen.add((d * 100).toInt())
            previous = d
            deg += 0.5f
        }
        assertTrue("only ${seen.size} distinct values", seen.size > 300)
    }

    /**
     * The documented degenerate case: gravity parallel to the hinge axis. Both projections
     * collapse and the angle is undefined — which is why the source reports confidence and
     * holds its last value rather than emitting a number.
     */
    @Test
    fun `gravity along the hinge axis degenerates`() {
        val along = floatArrayOf(0f, -g, 0f)
        val p = projectOut(along, hingeY)
        assertTrue("projection magnitude was ${mag(p)}", mag(p) < 1e-4f)
        assertTrue(dihedral(along, along, hingeY).isNaN())
    }

    /**
     * Rotating the whole device without folding must not change the reading — this is why
     * the projection exists rather than taking the raw 3-D angle between the vectors.
     */
    @Test
    fun `rotating the whole device about the hinge does not change the angle`() {
        val fold = 70f
        val (a1, a2) = halves(fold)
        val baseline = dihedral(a1, a2, hingeY)

        // Rotate both halves together about Y by 35 degrees.
        val r = Math.toRadians(35.0)
        fun rotY(v: FloatArray) = floatArrayOf(
            (v[0] * cos(r) + v[2] * sin(r)).toFloat(),
            v[1],
            (-v[0] * sin(r) + v[2] * cos(r)).toFloat(),
        )
        val rotated = dihedral(rotY(a1), rotY(a2), hingeY)
        assertTrue(
            "baseline $baseline vs rotated $rotated",
            abs(baseline - rotated) < 0.05f,
        )
    }
}
