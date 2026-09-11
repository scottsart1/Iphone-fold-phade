package dev.foldphase.core

import kotlin.math.abs
import kotlin.math.pow

/**
 * Re-usable easing / remapping primitives.
 *
 * Brief §12: hinge progress is linear, but no individual visual property has to be.
 * Every effect in [dev.foldphase.engine.FoldAnimationEngine] is expressed as
 * `curve(remap(p, start, end))` rather than `p * max`, so each property can be tuned
 * independently of every other one.
 *
 * All functions here are pure, allocation-free and safe to call every frame.
 */
object Curves {

    /** Clamp [v] into `[lo, hi]`. */
    fun clamp(v: Float, lo: Float = 0f, hi: Float = 1f): Float = when {
        v < lo -> lo
        v > hi -> hi
        else -> v
    }

    /**
     * Map [v] from the range `[inStart, inEnd]` onto `[0, 1]`, clamped.
     *
     * This is the workhorse: it carves a sub-window out of the normalised fold progress
     * so a property can be active over only part of the fold. Degenerate ranges
     * (`inEnd <= inStart`) collapse to a step function rather than dividing by zero.
     */
    fun remap(v: Float, inStart: Float, inEnd: Float): Float {
        val span = inEnd - inStart
        if (abs(span) < 1e-6f) return if (v >= inEnd) 1f else 0f
        return clamp((v - inStart) / span)
    }

    /** Map [v] from `[inStart, inEnd]` onto `[outStart, outEnd]`, clamped at both ends. */
    fun remapTo(v: Float, inStart: Float, inEnd: Float, outStart: Float, outEnd: Float): Float =
        outStart + (outEnd - outStart) * remap(v, inStart, inEnd)

    /** Linear interpolation. [t] is *not* clamped; use [clamp] first if that matters. */
    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** Hermite `3t² - 2t³`. C¹ continuous — the default ease for most properties. */
    fun smoothstep(t: Float): Float {
        val x = clamp(t)
        return x * x * (3f - 2f * x)
    }

    /**
     * Ken Perlin's `6t⁵ - 15t⁴ + 10t³`. C² continuous, so its *acceleration* is
     * continuous too. Used where a visible kink at the range boundary would betray the
     * animation as scripted — notably the handoff concealment window.
     */
    fun smootherstep(t: Float): Float {
        val x = clamp(t)
        return x * x * x * (x * (x * 6f - 15f) + 10f)
    }

    /** Accelerating power ease. [power] = 2 is quadratic, 3 cubic. */
    fun easeIn(t: Float, power: Float = 2f): Float = clamp(t).pow(power)

    /** Decelerating power ease — the "settling" curve (brief §10). */
    fun easeOut(t: Float, power: Float = 2f): Float = 1f - (1f - clamp(t)).pow(power)

    /** Symmetric accelerate-then-decelerate power ease. */
    fun easeInOut(t: Float, power: Float = 2f): Float {
        val x = clamp(t)
        return if (x < 0.5f) {
            0.5f * (2f * x).pow(power)
        } else {
            1f - 0.5f * (2f * (1f - x)).pow(power)
        }
    }

    /**
     * A raised-cosine bump: 0 at both ends of `[0,1]`, 1 at the centre.
     *
     * This is the shape of every "concealment" property — blur peak, black veil,
     * vignette — which must rise and fall away symmetrically around the handoff rather
     * than ramp monotonically.
     */
    fun bump(t: Float): Float {
        val x = clamp(t)
        val s = smootherstep(if (x < 0.5f) x * 2f else (1f - x) * 2f)
        return s
    }

    /**
     * A bump centred on [center] with half-width [halfWidth], evaluated at [v].
     *
     * Used to place the handoff concealment window on the *measured* handoff progress
     * rather than a hard-coded one (see `HandoffLearner`).
     */
    fun bumpAround(v: Float, center: Float, halfWidth: Float): Float {
        if (halfWidth <= 1e-6f) return 0f
        val d = abs(v - center) / halfWidth
        if (d >= 1f) return 0f
        return smootherstep(1f - d)
    }

    /**
     * Evaluate a cubic Bézier easing curve defined by control points
     * `(0,0), (x1,y1), (x2,y2), (1,1)` — the same parameterisation as CSS
     * `cubic-bezier()` and iOS `CAMediaTimingFunction`.
     *
     * Solves `x(s) = t` for the Bézier parameter `s` by Newton-Raphson with a bisection
     * fallback, then returns `y(s)`. Fixed iteration counts keep it allocation-free and
     * bounded, so it is safe on the frame path.
     */
    fun cubicBezier(t: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val x = clamp(t)
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f

        var s = x
        repeat(NEWTON_ITERATIONS) {
            val dx = bezierDerivative(s, x1, x2)
            if (abs(dx) < 1e-6f) return@repeat
            val err = bezierAxis(s, x1, x2) - x
            if (abs(err) < NEWTON_EPSILON) return bezierAxis(s, y1, y2)
            s -= err / dx
        }

        // Newton can diverge on near-vertical segments; fall back to bisection.
        var lo = 0f
        var hi = 1f
        s = x
        repeat(BISECTION_ITERATIONS) {
            val err = bezierAxis(s, x1, x2) - x
            if (abs(err) < NEWTON_EPSILON) return bezierAxis(s, y1, y2)
            if (err > 0f) hi = s else lo = s
            s = (lo + hi) * 0.5f
        }
        return bezierAxis(s, y1, y2)
    }

    /** One axis of a cubic Bézier with implicit endpoints 0 and 1. */
    private fun bezierAxis(s: Float, c1: Float, c2: Float): Float {
        val inv = 1f - s
        return 3f * inv * inv * s * c1 + 3f * inv * s * s * c2 + s * s * s
    }

    private fun bezierDerivative(s: Float, c1: Float, c2: Float): Float {
        val inv = 1f - s
        return 3f * inv * inv * c1 + 6f * inv * s * (c2 - c1) + 3f * s * s * (1f - c2)
    }

    private const val NEWTON_ITERATIONS = 6
    private const val BISECTION_ITERATIONS = 12
    private const val NEWTON_EPSILON = 1e-5f
}

/**
 * The named curve shapes the tuning UI can select per property.
 *
 * Keeping these as an enum (rather than lambdas) means a tuning profile is trivially
 * serialisable to DataStore and survives process death.
 */
enum class CurveShape {
    LINEAR,
    SMOOTHSTEP,
    SMOOTHERSTEP,
    EASE_IN,
    EASE_OUT,
    EASE_IN_OUT,

    /** `cubic-bezier(0.42, 0, 0.58, 1)` — the restrained, symmetric "Apple" standard ease. */
    APPLE_STANDARD,

    /** `cubic-bezier(0.32, 0.94, 0.60, 1)` — fast out, long settle. Used for the inner reveal. */
    APPLE_SETTLE,
    ;

    fun apply(t: Float): Float = when (this) {
        LINEAR -> Curves.clamp(t)
        SMOOTHSTEP -> Curves.smoothstep(t)
        SMOOTHERSTEP -> Curves.smootherstep(t)
        EASE_IN -> Curves.easeIn(t)
        EASE_OUT -> Curves.easeOut(t)
        EASE_IN_OUT -> Curves.easeInOut(t)
        APPLE_STANDARD -> Curves.cubicBezier(t, 0.42f, 0f, 0.58f, 1f)
        APPLE_SETTLE -> Curves.cubicBezier(t, 0.32f, 0.94f, 0.60f, 1f)
    }
}
