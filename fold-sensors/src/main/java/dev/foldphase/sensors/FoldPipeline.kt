package dev.foldphase.sensors

import android.view.Choreographer
import dev.foldphase.core.ActiveDisplay
import dev.foldphase.core.Curves
import dev.foldphase.core.FoldDirection
import dev.foldphase.core.FoldProgress
import dev.foldphase.core.FoldState
import dev.foldphase.core.HingeSample
import dev.foldphase.core.ProgressSourceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Sensor tuning knobs that the dev panel can change at runtime.
 */
data class FilterConfig(
    var oneEuroMinCutoff: Float = OneEuroFilter.DEFAULT_MIN_CUTOFF,
    var oneEuroBeta: Float = OneEuroFilter.DEFAULT_BETA,
    /**
     * Predictive lead in seconds. The filter costs a little latency; extrapolating the
     * filtered angle forward by `velocity * lead` buys some of it back.
     *
     * Kept deliberately small and **clamped**: over-prediction produces overshoot when
     * the hinge stops abruptly, which reads as a "bounce" and would violate the restrained
     * aesthetic the reference animation is built on (research §1.2 E). 0 disables it.
     */
    var predictionSeconds: Float = 0.012f,
    var enterVelocityThreshold: Float = FoldStateMachine.DEFAULT_ENTER_THRESHOLD,
    var exitVelocityThreshold: Float = FoldStateMachine.DEFAULT_EXIT_THRESHOLD,
    var directionDwellMs: Float = 24f,
    /**
     * Progress deadband near the closed end (brief §9 Phase A). Prevents small hinge
     * movements while the phone is nominally shut from wobbling the UI.
     */
    var closedDeadband: Float = 0.02f,
    /** If no sensor event arrives for this long while we believed we were moving,
     *  the pipeline declares ERROR_RECOVERY. */
    var sensorTimeoutMs: Long = 1500L,
)

/**
 * The frame-synchronised heart of the project.
 *
 * ## The architectural rule (brief §5, §7)
 *
 * > Sensor events establish the **target**. Display refresh drives **rendering**.
 *
 * Sensor callbacks do nothing but stash the newest raw sample into an
 * [AtomicReference]. A [Choreographer] callback — one per display frame, on the same
 * clock the compositor uses — picks that sample up, runs the filter *at frame time*,
 * estimates velocity, updates the state machine, and publishes a [FoldProgress].
 *
 * This matters for three concrete reasons:
 *
 * 1. The hinge sensor is **on-change**, so its event rate is a function of how fast you
 *    are moving the hinge. Driving animation from the callback would make the animation's
 *    frame rate depend on hinge speed — smooth when moving fast, stuttery when creeping,
 *    frozen when held. Exactly backwards.
 * 2. Evaluating the filter at frame time means a held hinge (which emits nothing) still
 *    settles smoothly to its resting value instead of freezing mid-transient.
 * 3. It decouples sensor jitter from presentation entirely, so a burst of events between
 *    two vsyncs collapses into one coherent visual step rather than three wasted ones.
 *
 * ## Idle behaviour (brief §23)
 *
 * The Choreographer loop is **not** run continuously. It runs while the fold is moving,
 * plus a short settle tail, and then stops; the sensor alone remains registered, which is
 * cheap because an on-change sensor that nothing is touching costs essentially nothing.
 * [isAnimating] exposes that state so the renderer can release GPU resources.
 */
class FoldPipeline(
    private val scope: CoroutineScope,
    var config: FilterConfig = FilterConfig(),
) {

    private val filter = OneEuroFilter()
    private val velocityEstimator = VelocityEstimator()
    private val stateMachine = FoldStateMachine()
    val handoffLearner = HandoffLearner()

    private val pendingSample = AtomicReference<HingeSample?>(null)
    private var lastConsumedNanos = 0L
    private var lastSampleArrivalNanos = 0L

    private val _progress = MutableStateFlow(FoldProgress.CLOSED)
    val progress: StateFlow<FoldProgress> = _progress.asStateFlow()

    private val _isAnimating = MutableStateFlow(false)
    val isAnimating: StateFlow<Boolean> = _isAnimating.asStateFlow()

    var calibration: HingeCalibration = HingeCalibration.UNCALIBRATED
        set(value) {
            field = value
            if (value.hasMeasuredHandoff) handoffLearner.seed(value.measuredHandoffProgress)
        }

    /** Half-width of the handoff concealment window, in progress units. */
    var handoffHalfWidth: Float = 0.10f

    /** Fallback handoff centre, used only until the learner has an observation. */
    var handoffCenterFallback: Float = 0.58f

    var activeDisplay: ActiveDisplay = ActiveDisplay.UNKNOWN

    private var source: FoldProgressSource? = null
    private var collectJob: Job? = null
    private var choreographer: Choreographer? = null
    private var frameScheduled = false
    private var idleFramesRemaining = 0

    /** Diagnostics: how many Choreographer frames the pipeline has processed. */
    @Volatile
    var frameCount: Long = 0L
        private set

    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        frameScheduled = false
        onFrame(frameTimeNanos)
    }

    /**
     * Attach a source and begin. Safe to call repeatedly; switching source (real hinge ⇄
     * simulator) is exactly how the dev panel's virtual-hinge mode works.
     */
    fun attach(newSource: FoldProgressSource) {
        detach()
        source = newSource
        newSource.start()
        collectJob = scope.launch {
            newSource.samples.collect { sample ->
                // Deliberately trivial: stash and return. All real work happens on the
                // frame callback.
                pendingSample.set(sample)
                lastSampleArrivalNanos = System.nanoTime()
                ensureFrameLoopRunning()
            }
        }
        choreographer = Choreographer.getInstance()
        ensureFrameLoopRunning()
    }

    fun detach() {
        collectJob?.cancel()
        collectJob = null
        source?.stop()
        source = null
        stopFrameLoop()
    }

    /** Reset filters — used when switching source or after error recovery. */
    fun resetFilters() {
        filter.reset()
        velocityEstimator.reset()
        stateMachine.reset()
        stateMachine.clearErrorRecovery()
        pendingSample.set(null)
        lastConsumedNanos = 0L
    }

    private fun ensureFrameLoopRunning() {
        idleFramesRemaining = IDLE_TAIL_FRAMES
        if (frameScheduled) return
        val c = choreographer ?: Choreographer.getInstance().also { choreographer = it }
        frameScheduled = true
        c.postFrameCallback(frameCallback)
        if (!_isAnimating.value) _isAnimating.value = true
    }

    private fun stopFrameLoop() {
        choreographer?.removeFrameCallback(frameCallback)
        frameScheduled = false
        idleFramesRemaining = 0
        if (_isAnimating.value) _isAnimating.value = false
    }

    private fun onFrame(frameTimeNanos: Long) {
        frameCount++

        val sample = pendingSample.getAndSet(null)
        val rawAngle: Float
        if (sample != null) {
            rawAngle = sample.angleDeg
            lastConsumedNanos = frameTimeNanos
            stateMachine.clearErrorRecovery()
        } else {
            rawAngle = _progress.value.rawAngleDeg
        }

        // Sanity-check the sensor before trusting it. A NaN, an infinity, or a value far
        // outside the calibrated span means something is wrong; degrade rather than
        // render garbage (brief §18).
        val plausible = rawAngle.isFinite() &&
            rawAngle > calibration.closedAngleDeg - IMPLAUSIBLE_MARGIN &&
            rawAngle < calibration.openAngleDeg + IMPLAUSIBLE_MARGIN

        val sensorStale = source?.kind == ProgressSourceKind.SENSOR &&
            lastSampleArrivalNanos != 0L &&
            _progress.value.state.isMoving &&
            (System.nanoTime() - lastSampleArrivalNanos) > config.sensorTimeoutMs * 1_000_000L

        if (!plausible || sensorStale) {
            stateMachine.enterErrorRecovery()
            publish(rawAngle, rawAngle, 0f, frameTimeNanos, FoldState.ERROR_RECOVERY, FoldDirection.HOLD)
            // Keep ticking a little longer so a recovering sensor is picked up promptly,
            // but do not spin forever.
            if (--idleFramesRemaining > 0) scheduleNextFrame() else stopFrameLoop()
            return
        }

        filter.minCutoff = config.oneEuroMinCutoff
        filter.beta = config.oneEuroBeta
        val filtered = filter.filter(rawAngle, frameTimeNanos)

        val velocity = if (sample != null) {
            velocityEstimator.add(filtered, frameTimeNanos)
        } else {
            velocityEstimator.tick(frameTimeNanos)
        }

        // Limited prediction (brief §5). Clamped hard: a runaway prediction would
        // overshoot and read as a spring, which the reference aesthetic does not have.
        val lead = Curves.clamp(config.predictionSeconds, 0f, MAX_PREDICTION_SECONDS)
        val predicted = filtered + Curves.clamp(
            velocity * lead,
            -MAX_PREDICTION_DEGREES,
            MAX_PREDICTION_DEGREES,
        )

        var p = calibration.progressFor(predicted)
        // Closed-end deadband: snap the bottom of the range flat so the UI cannot wobble
        // while the phone is nominally shut.
        if (p < config.closedDeadband) p = 0f

        stateMachine.enterVelocityThreshold = config.enterVelocityThreshold
        stateMachine.exitVelocityThreshold = config.exitVelocityThreshold
        stateMachine.directionDwellNanos = (config.directionDwellMs * 1_000_000f).toLong()

        val center = handoffLearner.estimate(opening = velocity >= 0f) ?: handoffCenterFallback
        val state = stateMachine.update(p, velocity, frameTimeNanos, center, handoffHalfWidth)

        publish(rawAngle, filtered, velocity, frameTimeNanos, state, stateMachine.direction, p)

        // Idle management (brief §23): keep the loop alive while anything is moving,
        // then run a short tail so the filter can settle, then stop entirely.
        val moving = state.isMoving || abs(velocity) > IDLE_VELOCITY_EPSILON
        if (moving) {
            idleFramesRemaining = IDLE_TAIL_FRAMES
            scheduleNextFrame()
        } else if (idleFramesRemaining-- > 0) {
            scheduleNextFrame()
        } else {
            stopFrameLoop()
        }
    }

    private fun scheduleNextFrame() {
        if (frameScheduled) return
        frameScheduled = true
        choreographer?.postFrameCallback(frameCallback)
    }

    private fun publish(
        raw: Float,
        filtered: Float,
        velocity: Float,
        timeNanos: Long,
        state: FoldState,
        direction: FoldDirection,
        progressOverride: Float? = null,
    ) {
        _progress.value = FoldProgress(
            rawAngleDeg = raw,
            filteredAngleDeg = filtered,
            progress = progressOverride ?: calibration.progressFor(filtered),
            velocityDegPerSec = velocity,
            direction = direction,
            state = state,
            activeDisplay = activeDisplay,
            source = source?.kind ?: ProgressSourceKind.NONE,
            timestampNanos = timeNanos,
            calibrated = calibration.isCalibrated,
        )
    }

    /**
     * Tell the pipeline which physical display the window is on, so the handoff learner
     * can record the switch point. Call from `onConfigurationChanged` / display listener.
     */
    fun reportDisplay(displayId: Int, isInner: Boolean) {
        activeDisplay = if (isInner) ActiveDisplay.INNER else ActiveDisplay.COVER
        handoffLearner.observe(displayId, isInner, _progress.value.progress)
        // A display change is a strong hint that something is happening; wake the loop
        // even if the sensor has been quiet.
        ensureFrameLoopRunning()
    }

    private companion object {
        /** Frames to keep rendering after motion stops, so the filter can settle. */
        const val IDLE_TAIL_FRAMES = 12
        const val IDLE_VELOCITY_EPSILON = 0.5f
        const val MAX_PREDICTION_SECONDS = 0.05f
        const val MAX_PREDICTION_DEGREES = 6f
        const val IMPLAUSIBLE_MARGIN = 45f
    }
}
