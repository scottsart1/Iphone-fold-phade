package dev.foldphase.sensors

import dev.foldphase.core.Curves

/**
 * Learns the hinge's usable range passively, from ordinary use.
 *
 * ## Why this exists
 *
 * The calibration wizard is the accurate path, but it assumes the user will run it. If
 * they do not — or cannot — the alternative was falling back to `0° … 180°`, which
 * research §3.1 says is wrong on this hardware: Samsung Fold hinges have been reported
 * reading up to 181.5° flat, and the closed end is rarely 0.
 *
 * Being wrong at the extremes is not cosmetic. Every phase boundary is expressed in
 * normalised progress, so a range that is off by 5° shifts the whole animation — including
 * the concealment window, which then no longer sits on the panel handoff. That is exactly
 * the failure that shows up as a visible flash.
 *
 * So the app watches what the sensor actually reports and widens a provisional range to
 * fit. After one full open-and-close it is typically as good as the wizard's.
 *
 * ## Guard rails
 *
 * - A provisional range is only **trusted** once the observed span exceeds
 *   [MIN_TRUSTED_SPAN_DEG]. Before that, a user who has only wiggled the hinge 10° would
 *   otherwise get a calibration claiming the device opens to 10°, making every small
 *   movement drive the full animation.
 * - Extremes only ever widen, never narrow, so a single noisy reading cannot shrink the
 *   range and it cannot oscillate.
 * - An explicit wizard calibration always wins. This never overwrites one.
 */
class AutoCalibrator(
    /**
     * Span that counts as "enough of the range seen", in the sensor's own units.
     *
     * Not fixed at 60 degrees any more: a vendor fold sensor may report a normalised
     * `0 … 1`, where 60 would be unreachable and the range would never be trusted. The
     * caller sets this from the selected sensor's declared maximum range.
     */
    private var minTrustedSpan: Float = MIN_TRUSTED_SPAN_DEG,
    /** Readings outside this are treated as sensor faults, in the sensor's own units. */
    private var plausibleMin: Float = ABSOLUTE_MIN_DEG,
    private var plausibleMax: Float = ABSOLUTE_MAX_DEG,
) {

    /**
     * Re-scale the guard rails for a sensor reporting in units other than degrees.
     *
     * @param maxRange the sensor's declared `maximumRange`
     */
    fun configureForRange(maxRange: Float) {
        if (!maxRange.isFinite() || maxRange <= 0f) return
        // A third of the declared range, matching the 60-of-180 ratio the degree default
        // encodes, so the trust threshold means the same thing in any unit.
        minTrustedSpan = maxRange / 3f
        val margin = maxRange * 0.12f
        plausibleMin = -margin
        plausibleMax = maxRange + margin
    }

    var observedMin: Float = Float.POSITIVE_INFINITY
        private set

    var observedMax: Float = Float.NEGATIVE_INFINITY
        private set

    /** Readings accepted so far. */
    var sampleCount: Int = 0
        private set

    /** Observe one raw angle. Returns true if the trusted range changed meaningfully. */
    fun observe(angleDeg: Float): Boolean {
        if (!angleDeg.isFinite()) return false
        // Reject readings far outside any plausible hinge range before they can poison
        // the extremes; a single bad value would otherwise stretch the span permanently.
        if (angleDeg < plausibleMin || angleDeg > plausibleMax) return false

        sampleCount++
        var changed = false

        if (angleDeg < observedMin - EPSILON) {
            observedMin = angleDeg
            changed = true
        }
        if (angleDeg > observedMax + EPSILON) {
            observedMax = angleDeg
            changed = true
        }
        return changed && isTrusted
    }

    val observedSpan: Float
        get() = if (observedMin.isFinite() && observedMax.isFinite()) {
            observedMax - observedMin
        } else {
            0f
        }

    /** Whether enough of the range has been seen to prefer this over the fallback. */
    val isTrusted: Boolean
        get() = observedSpan >= minTrustedSpan

    /**
     * Fold [stored] with what has been observed.
     *
     * An explicit wizard calibration is returned untouched — the user measured it
     * deliberately and this must not second-guess that. Otherwise a provisional
     * calibration is produced, marked `isCalibrated = false` so the UI keeps telling the
     * user it is provisional rather than quietly implying it was measured.
     */
    fun refine(stored: HingeCalibration): HingeCalibration {
        if (stored.isCalibrated) return stored
        if (!isTrusted) return stored

        return stored.copy(
            closedAngleDeg = observedMin,
            openAngleDeg = observedMax,
            // Deliberately still false: this is inferred, not measured.
            isCalibrated = false,
        )
    }

    fun reset() {
        observedMin = Float.POSITIVE_INFINITY
        observedMax = Float.NEGATIVE_INFINITY
        sampleCount = 0
    }

    /** Seed from a stored provisional range so a restart does not start blind. */
    fun seed(minDeg: Float, maxDeg: Float) {
        if (minDeg.isFinite() && minDeg >= plausibleMin) observedMin = minDeg
        if (maxDeg.isFinite() && maxDeg <= plausibleMax) observedMax = maxDeg
    }

    /** Fraction of a plausible full range seen so far, for a progress readout. */
    fun coverage(): Float = Curves.clamp(observedSpan / (minTrustedSpan * 3f))

    companion object {
        /**
         * Below this the range is not trusted. A book foldable opens to ~180°, so 60°
         * means a third of the travel has been seen — enough that the extremes are
         * roughly right, and far more than an idle wiggle would produce.
         */
        const val MIN_TRUSTED_SPAN_DEG = 60f

        /** Hinge angles outside this are treated as sensor faults, not readings. */
        const val ABSOLUTE_MIN_DEG = -10f
        const val ABSOLUTE_MAX_DEG = 200f

        const val TYPICAL_FULL_SPAN_DEG = 180f

        private const val EPSILON = 0.01f
    }
}
