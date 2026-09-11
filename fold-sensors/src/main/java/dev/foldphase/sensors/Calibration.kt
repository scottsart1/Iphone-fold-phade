package dev.foldphase.sensors

import dev.foldphase.core.Curves

/**
 * Per-device hinge calibration.
 *
 * Brief §4 is emphatic that `0° = closed, 180° = open` must not be hard-coded until it
 * has been confirmed on the actual device — and the research bears that out: Samsung Fold
 * hardware has been reported sitting at 178.5–181.5° when flat, and the closed end is
 * frequently not 0 either. Every downstream consumer works on [progressFor]'s normalised
 * output, never on degrees.
 */
data class HingeCalibration(
    val closedAngleDeg: Float = DEFAULT_CLOSED,
    val openAngleDeg: Float = DEFAULT_OPEN,
    /** Standard deviation of the angle while the hinge was held still, in degrees. */
    val noiseStdDevDeg: Float = 0.0f,
    /** Smallest non-zero change observed between consecutive readings, in degrees. */
    val observedResolutionDeg: Float = 0.0f,
    /** Median interval between sensor events while moving, in milliseconds. */
    val medianUpdateIntervalMs: Float = 0.0f,
    /** Largest gap between consecutive distinct readings — a proxy for dead zones. */
    val largestDiscontinuityDeg: Float = 0.0f,
    /**
     * Progress at which the device physically swapped panels, learned by observing the
     * activity's display id change. `NaN` until observed. See `HandoffLearner`.
     */
    val measuredHandoffProgress: Float = Float.NaN,
    val isCalibrated: Boolean = false,
) {

    /** Calibrated span in degrees. Always positive; guarded against a degenerate range. */
    val spanDeg: Float
        get() = (openAngleDeg - closedAngleDeg).let { if (it < MIN_SPAN) MIN_SPAN else it }

    /**
     * `p = (angle - closed) / (open - closed)`, clamped to `[0,1]` (brief §4).
     */
    fun progressFor(angleDeg: Float): Float = Curves.clamp((angleDeg - closedAngleDeg) / spanDeg)

    /** Inverse of [progressFor] — used by the simulator to drive degrees from a slider. */
    fun angleFor(progress: Float): Float = closedAngleDeg + Curves.clamp(progress) * spanDeg

    /**
     * A deadband derived from measured noise rather than guessed.
     *
     * Three sigma of the still-hinge noise, expressed in progress units, with a floor so
     * a suspiciously quiet calibration run cannot produce a zero deadband.
     */
    fun noiseDeadbandProgress(): Float =
        Curves.clamp(maxOf(noiseStdDevDeg * 3f, observedResolutionDeg) / spanDeg, 0.001f, 0.05f)

    /** True if the handoff point has actually been observed on this device. */
    val hasMeasuredHandoff: Boolean
        get() = !measuredHandoffProgress.isNaN() &&
            measuredHandoffProgress > 0.02f &&
            measuredHandoffProgress < 0.98f

    companion object {
        /**
         * Pre-calibration fallbacks. Deliberately *not* presented as truth: the app shows
         * an "uncalibrated" banner until a real calibration is stored, and these values
         * exist only so the simulator and first launch have something to work with.
         */
        const val DEFAULT_CLOSED = 0f
        const val DEFAULT_OPEN = 180f
        const val MIN_SPAN = 5f

        val UNCALIBRATED = HingeCalibration()
    }
}

/**
 * Accumulates readings during the calibration wizard's third step (the slow sweep) and
 * derives the sensor-quality statistics the brief asks for in §3 and §4.
 *
 * Bounded: keeps at most [maxSamples] readings so a user who leaves the wizard open does
 * not grow the heap without limit.
 */
class CalibrationSweepAnalyzer(private val maxSamples: Int = 4096) {

    private val angles = ArrayList<Float>(1024)
    private val timesNanos = ArrayList<Long>(1024)

    var minAngle: Float = Float.POSITIVE_INFINITY
        private set
    var maxAngle: Float = Float.NEGATIVE_INFINITY
        private set

    val sampleCount: Int get() = angles.size

    fun reset() {
        angles.clear()
        timesNanos.clear()
        minAngle = Float.POSITIVE_INFINITY
        maxAngle = Float.NEGATIVE_INFINITY
    }

    fun add(angleDeg: Float, timeNanos: Long) {
        if (angles.size >= maxSamples) return
        angles.add(angleDeg)
        timesNanos.add(timeNanos)
        if (angleDeg < minAngle) minAngle = angleDeg
        if (angleDeg > maxAngle) maxAngle = angleDeg
    }

    /** Smallest non-zero step between consecutive readings — the effective resolution. */
    fun observedResolution(): Float {
        var smallest = Float.POSITIVE_INFINITY
        for (i in 1 until angles.size) {
            val d = kotlin.math.abs(angles[i] - angles[i - 1])
            if (d > 1e-4f && d < smallest) smallest = d
        }
        return if (smallest.isFinite()) smallest else 0f
    }

    /** Largest step between consecutive readings — reveals dead zones and jumps. */
    fun largestDiscontinuity(): Float {
        var largest = 0f
        for (i in 1 until angles.size) {
            val d = kotlin.math.abs(angles[i] - angles[i - 1])
            if (d > largest) largest = d
        }
        return largest
    }

    /** Median inter-event interval in milliseconds. Median, not mean: on-change sensors
     *  produce long idle gaps that would wreck a mean. */
    fun medianUpdateIntervalMs(): Float {
        if (timesNanos.size < 2) return 0f
        val deltas = FloatArray(timesNanos.size - 1) { i ->
            (timesNanos[i + 1] - timesNanos[i]) / 1_000_000f
        }
        deltas.sort()
        return deltas[deltas.size / 2]
    }

    /**
     * Standard deviation over the quietest run of samples — an estimate of noise while
     * the hinge is being held rather than moved.
     *
     * Finds the window of [WINDOW] consecutive samples with the smallest spread and
     * reports its standard deviation, which isolates "held still" from "moving".
     */
    fun heldNoiseStdDev(): Float {
        if (angles.size < WINDOW) return 0f
        var bestStdDev = Float.POSITIVE_INFINITY
        for (start in 0..angles.size - WINDOW) {
            var sum = 0f
            for (i in start until start + WINDOW) sum += angles[i]
            val mean = sum / WINDOW
            var sq = 0f
            for (i in start until start + WINDOW) {
                val d = angles[i] - mean
                sq += d * d
            }
            val sd = kotlin.math.sqrt(sq / WINDOW)
            if (sd < bestStdDev) bestStdDev = sd
        }
        return if (bestStdDev.isFinite()) bestStdDev else 0f
    }

    /** Distinct angle values seen, as a crude check that the sensor is not stuck. */
    fun distinctValueCount(): Int = angles.distinct().size

    private companion object {
        const val WINDOW = 16
    }
}
