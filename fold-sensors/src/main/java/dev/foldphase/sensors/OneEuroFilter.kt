package dev.foldphase.sensors

import kotlin.math.abs
import kotlin.math.min

/**
 * A first-order low-pass filter with a fixed cutoff.
 *
 * Kept separate so [OneEuroFilter] can compose two of them and so the simple
 * exponential-smoothing mode can reuse one directly.
 */
class LowPassFilter {
    private var initialised = false
    private var previous = 0f
    var value: Float = 0f
        private set

    fun reset() {
        initialised = false
        previous = 0f
        value = 0f
    }

    /** @param alpha smoothing factor in `(0,1]`; 1 passes the input straight through. */
    fun filter(x: Float, alpha: Float): Float {
        value = if (!initialised) {
            initialised = true
            x
        } else {
            alpha * x + (1f - alpha) * previous
        }
        previous = value
        return value
    }

    val hasValue: Boolean get() = initialised
}

/**
 * The One Euro filter (Casiez, Roussel & Vogel, CHI 2012).
 *
 * ## Why this one
 *
 * The brief (§5) frames the real problem precisely: remove jitter *without* introducing
 * perceptible lag. Those pull in opposite directions for any fixed-cutoff filter — heavy
 * smoothing kills the jitter and also kills the responsiveness that makes the whole
 * effect feel like direct manipulation.
 *
 * One Euro resolves it by making the cutoff frequency a function of the signal's own
 * speed: when the hinge is nearly still, the cutoff drops and jitter is crushed; when the
 * hinge is moving fast, the cutoff rises and the filter gets out of the way. The result
 * is a still hinge that does not shimmer and a fast hinge that does not lag — which is
 * exactly the tradeoff this project lives or dies on.
 *
 * Tuning, per the brief's instruction to tune on real hardware:
 * - [minCutoff] sets the floor. Lower = steadier when held, more lag when slow.
 * - [beta] sets the speed coupling. Higher = less lag when fast, more jitter admitted.
 * - [derivateCutoff] smooths the internal speed estimate; rarely needs changing.
 */
class OneEuroFilter(
    var minCutoff: Float = DEFAULT_MIN_CUTOFF,
    var beta: Float = DEFAULT_BETA,
    var derivateCutoff: Float = DEFAULT_DERIVATE_CUTOFF,
) {
    private val xFilter = LowPassFilter()
    private val dxFilter = LowPassFilter()
    private var lastTimeNanos: Long = 0L
    private var lastRaw: Float = 0f

    /** The most recent smoothed derivative, in units/second. */
    var smoothedDerivative: Float = 0f
        private set

    fun reset() {
        xFilter.reset()
        dxFilter.reset()
        lastTimeNanos = 0L
        lastRaw = 0f
        smoothedDerivative = 0f
    }

    /**
     * @param x           the raw value (degrees)
     * @param timeNanos   timestamp on the frame clock base (see [TimeBase])
     * @return the filtered value
     */
    fun filter(x: Float, timeNanos: Long): Float {
        if (lastTimeNanos == 0L) {
            lastTimeNanos = timeNanos
            lastRaw = x
            smoothedDerivative = 0f
            return xFilter.filter(x, 1f)
        }

        val dtSeconds = (timeNanos - lastTimeNanos) / 1_000_000_000.0f
        // Out-of-order or duplicate timestamps happen; refuse to divide by ~zero.
        if (dtSeconds <= MIN_DT_SECONDS) {
            return if (xFilter.hasValue) xFilter.value else x
        }
        lastTimeNanos = timeNanos

        val rate = 1f / dtSeconds
        val dx = (x - lastRaw) * rate
        lastRaw = x

        smoothedDerivative = dxFilter.filter(dx, alpha(derivateCutoff, rate))

        // The adaptive step: cutoff rises with the magnitude of the derivative.
        val cutoff = minCutoff + beta * abs(smoothedDerivative)
        return xFilter.filter(x, alpha(cutoff, rate))
    }

    /** Convert a cutoff frequency plus a sampling rate into an exponential alpha. */
    private fun alpha(cutoffHz: Float, rateHz: Float): Float {
        val tau = 1f / (TWO_PI * cutoffHz)
        val te = 1f / rateHz
        return min(1f, 1f / (1f + tau / te))
    }

    companion object {
        /**
         * Defaults chosen for an on-change hinge sensor whose useful signal is well under
         * 10 Hz. They are conservative starting points; the dev panel exposes both for
         * tuning against the actual device (brief §21).
         */
        const val DEFAULT_MIN_CUTOFF = 1.2f
        const val DEFAULT_BETA = 0.035f
        const val DEFAULT_DERIVATE_CUTOFF = 1.0f

        private const val TWO_PI = 6.283185307f
        private const val MIN_DT_SECONDS = 1e-5f
    }
}
