package dev.foldphase.core

/**
 * Where the fold is, and what it is doing.
 *
 * Brief §6. The intermediate states exist so the renderer can make coarse decisions
 * (start/stop the frame loop, arm the overlay, begin warming textures) without
 * re-deriving them from raw angle everywhere.
 */
enum class FoldState {
    /** At or below the closed calibration point, within the deadband. */
    CLOSED,

    /** Moving open with velocity above the hysteresis threshold. */
    OPENING,

    /** Between the extremes but not moving appreciably — the hinge is being held. */
    PARTIALLY_OPEN,

    /** Inside the concealment window while opening; the panel swap is imminent or in progress. */
    OPENING_HANDOFF,

    /** At or above the open calibration point. */
    OPEN,

    /** Moving closed with velocity below the negative hysteresis threshold. */
    CLOSING,

    /** Inside the concealment window while closing. */
    CLOSING_HANDOFF,

    /**
     * Sensor has stopped delivering, delivered nonsense, or the pipeline lost
     * confidence. Consumers must degrade safely — in particular the overlay must tear
     * itself down (brief §18).
     */
    ERROR_RECOVERY,
    ;

    val isHandoff: Boolean get() = this == OPENING_HANDOFF || this == CLOSING_HANDOFF

    val isMoving: Boolean
        get() = this == OPENING || this == CLOSING || isHandoff
}

/** Direction of hinge travel, after hysteresis. */
enum class FoldDirection {
    OPENING,
    CLOSING,

    /** Velocity is inside the hysteresis band. The visual state must hold exactly. */
    HOLD,
    ;

    val sign: Float get() = when (this) {
        OPENING -> 1f
        CLOSING -> -1f
        HOLD -> 0f
    }
}

/** Which physical panel the app believes is currently lit. */
enum class ActiveDisplay { COVER, INNER, UNKNOWN }

/**
 * One fully-resolved sample of the fold pipeline, ready for the animation engine.
 *
 * Produced at **frame rate** by the render loop, not at sensor rate (brief §5). A
 * consumer can treat this as the complete description of "where the hinge is right now".
 */
data class FoldProgress(
    /** Raw sensor degrees, unfiltered and uncalibrated. Diagnostics only. */
    val rawAngleDeg: Float,
    /** Degrees after the noise filter. Diagnostics only. */
    val filteredAngleDeg: Float,
    /**
     * Calibrated, clamped `[0,1]` progress. **Every animation decision uses this**
     * and not degrees (brief §4).
     */
    val progress: Float,
    /** Filtered angular velocity in degrees/second, signed. Positive = opening. */
    val velocityDegPerSec: Float,
    val direction: FoldDirection,
    val state: FoldState,
    val activeDisplay: ActiveDisplay = ActiveDisplay.UNKNOWN,
    /** Whether the underlying values came from the real sensor or the simulator. */
    val source: ProgressSourceKind = ProgressSourceKind.SENSOR,
    /** Frame-clock timestamp (nanos, `System.nanoTime` base) this sample was resolved at. */
    val timestampNanos: Long = 0L,
    /** True once calibration has been completed and stored. */
    val calibrated: Boolean = false,
) {
    /** Normalised progress velocity, i.e. `d(progress)/dt`, given a calibrated span. */
    fun progressVelocity(spanDegrees: Float): Float =
        if (spanDegrees <= 1e-3f) 0f else velocityDegPerSec / spanDegrees

    companion object {
        val CLOSED = FoldProgress(
            rawAngleDeg = 0f,
            filteredAngleDeg = 0f,
            progress = 0f,
            velocityDegPerSec = 0f,
            direction = FoldDirection.HOLD,
            state = FoldState.CLOSED,
        )
    }
}

/** Where a [FoldProgress] came from. */
enum class ProgressSourceKind {
    /** The physical `TYPE_HINGE_ANGLE` sensor. */
    SENSOR,

    /** The virtual-hinge slider or scripted sweep (brief §27). */
    SIMULATOR,

    /** No source is producing values — the sensor is absent or unregistered. */
    NONE,
}

/** A single raw reading, straight off the sensor or the simulator. */
data class HingeSample(
    val angleDeg: Float,
    /**
     * Timestamp in **nanoseconds, `System.nanoTime` base**.
     *
     * Sensor events arrive on the `elapsedRealtimeNanos` base on Samsung hardware while
     * `Choreographer` runs on `System.nanoTime`; the two differ by however long the
     * device has been suspended. `TimeBase` converts at the source so that everything
     * downstream shares one clock (research §5).
     */
    val timestampNanos: Long,
)
