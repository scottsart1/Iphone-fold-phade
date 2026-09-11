package dev.foldphase.renderer

import dev.foldphase.engine.ShaderQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveQualityTest {

    private fun stats(p95Ms: Float, samples: Int = 120, hz: Float = 120f) = FrameStats(
        sampleCount = samples,
        averageMs = p95Ms * 0.8f,
        p95Ms = p95Ms,
        p99Ms = p95Ms * 1.2f,
        expectedHz = hz,
    )

    @Test
    fun `holds quality while inside budget`() {
        val a = AdaptiveQuality()
        repeat(50) { a.update(stats(6f)) }
        assertEquals(ShaderQuality.HIGH, a.current)
        assertFalse(a.hasStepped)
    }

    @Test
    fun `steps down after sustained over-budget frames`() {
        val a = AdaptiveQuality()
        repeat(3) { a.update(stats(14f)) }
        assertEquals(ShaderQuality.MEDIUM, a.current)
        assertTrue(a.hasStepped)
    }

    /**
     * A transient hitch — another app scrolling past, a GC pause — must not accumulate
     * toward a permanent downgrade.
     */
    @Test
    fun `a single bad window does not step down`() {
        val a = AdaptiveQuality()
        a.update(stats(14f))
        a.update(stats(6f))
        a.update(stats(14f))
        a.update(stats(6f))
        assertEquals(ShaderQuality.HIGH, a.current)
    }

    /**
     * The ratchet only descends. Oscillating between levels would visibly pulse the blur
     * radius mid-fold, which is far worse than simply running one level lower.
     */
    @Test
    fun `never steps back up on its own`() {
        val a = AdaptiveQuality()
        repeat(3) { a.update(stats(14f)) }
        assertEquals(ShaderQuality.MEDIUM, a.current)

        repeat(100) { a.update(stats(2f)) }
        assertEquals(ShaderQuality.MEDIUM, a.current)
    }

    @Test
    fun `bottoms out at LOW`() {
        val a = AdaptiveQuality()
        repeat(30) { a.update(stats(40f)) }
        assertEquals(ShaderQuality.LOW, a.current)
    }

    /** Too few frames is not evidence; judging on it would downgrade on startup noise. */
    @Test
    fun `ignores windows with too few samples`() {
        val a = AdaptiveQuality()
        repeat(20) { a.update(stats(40f, samples = 5)) }
        assertEquals(ShaderQuality.HIGH, a.current)
    }

    /** The budget must follow the panel, so 60 Hz mode does not trigger a false downgrade. */
    @Test
    fun `budget scales with refresh rate`() {
        val a = AdaptiveQuality()
        // 14 ms is over budget at 120 Hz but comfortably inside it at 60 Hz.
        repeat(10) { a.update(stats(14f, hz = 60f)) }
        assertEquals(ShaderQuality.HIGH, a.current)
    }

    @Test
    fun `manual override disables the stepped flag`() {
        val a = AdaptiveQuality()
        repeat(3) { a.update(stats(14f)) }
        assertTrue(a.hasStepped)

        a.set(ShaderQuality.HIGH)
        assertEquals(ShaderQuality.HIGH, a.current)
        assertFalse(a.hasStepped)
    }
}
