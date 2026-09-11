package dev.foldphase.sensors

import dev.foldphase.core.FoldDirection
import dev.foldphase.core.FoldState
import kotlin.math.abs

/**
 * Turns `(progress, velocity)` into a stable [FoldState] and [FoldDirection].
 *
 * ## The thrashing problem
 *
 * Brief §6: "Do not rapidly bounce between opening and closing because of ±0.2° sensor
 * noise." A naive `if (v > 0) OPENING else CLOSING` flips on every noise sample when the
 * hinge is held still, and every flip is visible because direction feeds the predictive
 * smoothing.
 *
 * Two mechanisms prevent it:
 *
 * 1. **Asymmetric hysteresis on velocity.** It takes [enterVelocityThreshold] deg/s to
 *    *enter* a direction but only [exitVelocityThreshold] to *stay* in it. Since
 *    enter > exit, noise around zero cannot flip the state, while a genuine slow
 *    movement is still tracked.
 * 2. **A dwell time.** A direction must persist for [directionDwellNanos] before the
 *    reported direction changes, which rejects single-sample spikes outright.
 */
class FoldStateMachine(
    /** deg/s needed to commit to a direction from HOLD. */
    var enterVelocityThreshold: Float = DEFAULT_ENTER_THRESHOLD,
    /** deg/s below which a committed direction relaxes back to HOLD. */
    var exitVelocityThreshold: Float = DEFAULT_EXIT_THRESHOLD,
    /** How long a candidate direction must persist before it is adopted. */
    var directionDwellNanos: Long = DEFAULT_DWELL_NANOS,
    /** Progress below which the fold counts as CLOSED. */
    var closedThreshold: Float = DEFAULT_CLOSED_THRESHOLD,
    /** Progress above which the fold counts as OPEN. */
    var openThreshold: Float = DEFAULT_OPEN_THRESHOLD,
) {

    var direction: FoldDirection = FoldDirection.HOLD
        private set

    var state: FoldState = FoldState.CLOSED
        private set

    private var candidateDirection: FoldDirection = FoldDirection.HOLD
    private var candidateSinceNanos: Long = 0L

    /**
     * Whether [candidateSinceNanos] holds a real timestamp.
     *
     * A separate flag rather than treating `0L` as "unset": the frame clock legitimately
     * reads 0 at the start of a test run, and on some clock sources at boot, and a
     * sentinel that collides with a valid value silently disables the dwell.
     */
    private var candidatePending: Boolean = false
    private var lastUpdateNanos: Long = 0L
    private var inErrorRecovery = false

    fun reset() {
        direction = FoldDirection.HOLD
        state = FoldState.CLOSED
        candidateDirection = FoldDirection.HOLD
        candidateSinceNanos = 0L
        candidatePending = false
        lastUpdateNanos = 0L
        inErrorRecovery = false
    }

    /**
     * Force the machine into [FoldState.ERROR_RECOVERY].
     *
     * Called when the sensor stops delivering, reports an implausible value, or the
     * pipeline otherwise loses confidence. Consumers — above all the overlay — treat this
     * as "tear everything down now" (brief §18).
     */
    fun enterErrorRecovery() {
        inErrorRecovery = true
        state = FoldState.ERROR_RECOVERY
        direction = FoldDirection.HOLD
        candidateDirection = FoldDirection.HOLD
    }

    fun clearErrorRecovery() {
        inErrorRecovery = false
    }

    /**
     * @param progress          normalised `[0,1]` fold progress
     * @param velocityDegPerSec filtered signed angular velocity
     * @param nowNanos          frame-clock timestamp
     * @param handoffCenter     progress at which the panels swap (measured if known)
     * @param handoffHalfWidth  half-width of the concealment window in progress units
     */
    fun update(
        progress: Float,
        velocityDegPerSec: Float,
        nowNanos: Long,
        handoffCenter: Float,
        handoffHalfWidth: Float,
    ): FoldState {
        lastUpdateNanos = nowNanos

        if (inErrorRecovery) {
            state = FoldState.ERROR_RECOVERY
            return state
        }

        direction = resolveDirection(velocityDegPerSec, nowNanos)

        val inHandoffWindow = handoffHalfWidth > 1e-4f &&
            abs(progress - handoffCenter) <= handoffHalfWidth

        state = when {
            // The handoff window takes priority over everything except the extremes,
            // because it is the only state in which the renderer must not be torn down.
            inHandoffWindow && direction == FoldDirection.OPENING -> FoldState.OPENING_HANDOFF
            inHandoffWindow && direction == FoldDirection.CLOSING -> FoldState.CLOSING_HANDOFF
            inHandoffWindow -> if (state.isHandoff) state else FoldState.PARTIALLY_OPEN

            progress <= closedThreshold && direction != FoldDirection.OPENING -> FoldState.CLOSED
            progress >= openThreshold && direction != FoldDirection.CLOSING -> FoldState.OPEN

            direction == FoldDirection.OPENING -> FoldState.OPENING
            direction == FoldDirection.CLOSING -> FoldState.CLOSING
            else -> FoldState.PARTIALLY_OPEN
        }
        return state
    }

    private fun resolveDirection(velocity: Float, nowNanos: Long): FoldDirection {
        // Asymmetric band: the threshold to keep a direction is lower than to enter one.
        val threshold = if (direction == FoldDirection.HOLD) {
            enterVelocityThreshold
        } else {
            exitVelocityThreshold
        }

        val instantaneous = when {
            velocity > threshold -> FoldDirection.OPENING
            velocity < -threshold -> FoldDirection.CLOSING
            else -> FoldDirection.HOLD
        }

        if (instantaneous == direction) {
            candidateDirection = direction
            candidatePending = false
            return direction
        }

        if (instantaneous != candidateDirection) {
            candidateDirection = instantaneous
            candidateSinceNanos = nowNanos
            candidatePending = true
            return direction
        }

        // Relaxing *into* HOLD is allowed immediately: stopping must look instant, and a
        // false stop is invisible anyway because the visual state simply holds.
        // Committing to a *direction* must serve out the dwell.
        if (instantaneous == FoldDirection.HOLD) {
            direction = FoldDirection.HOLD
            candidatePending = false
            return direction
        }

        if (candidatePending && nowNanos - candidateSinceNanos >= directionDwellNanos) {
            direction = candidateDirection
            candidatePending = false
        }
        return direction
    }

    companion object {
        /**
         * Starting thresholds. The brief asks for these to be tuned against the real
         * Fold 7 — the dev panel exposes all of them, and the diagnostics screen reports
         * the measured noise floor so the user can pick values with evidence rather than
         * by feel.
         *
         * Rationale for the defaults: a deliberate slow open covers ~180° in about 2 s,
         * i.e. ~90 deg/s; a careful crawl might be 10 deg/s. Noise on a held hinge is
         * well under 1°, and the regression window spans 80 ms, so noise-driven velocity
         * stays around a few deg/s. 6 deg/s to enter sits comfortably above the noise and
         * below the slowest deliberate movement.
         */
        const val DEFAULT_ENTER_THRESHOLD = 6.0f
        const val DEFAULT_EXIT_THRESHOLD = 2.0f
        const val DEFAULT_DWELL_NANOS = 24_000_000L // 24 ms ~ 3 frames at 120 Hz
        const val DEFAULT_CLOSED_THRESHOLD = 0.02f
        const val DEFAULT_OPEN_THRESHOLD = 0.98f
    }
}
