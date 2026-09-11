package dev.foldphase.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dev.foldphase.core.HingeSample
import dev.foldphase.core.ProgressSourceKind
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Derives a **continuous** hinge angle from the two accelerometers, one in each half.
 *
 * ## Why this exists
 *
 * Measured on a Galaxy Z Fold 7 (SM-F966U, Android 16), the standard
 * `android.sensor.hinge_angle` reports exactly **three distinct values across its whole
 * range** — 0, 90 and 180. Its declared `resolution = 90.0` was literal, not a placeholder.
 * Three detent positions cannot drive a scrubbed animation, and no filter can recover
 * intermediate angles that were never sampled.
 *
 * Samsung's finer-grained fold sensors (`folding_angle`, `folding_state`/`lid_angle_fusion`,
 * `folding_state_lpm`) accept `registerListener` and then deliver **zero events** to a
 * third-party app — the signature of vendor sensors gated behind a Samsung permission.
 *
 * What *is* available is a second accelerometer, `com.samsung.sensor.accelerometer_sub`
 * (type 65687), physically mounted in the other half of the chassis alongside a matching
 * gyroscope. Both declare a 2084 µs minimum delay — up to 479 Hz, far above display rate.
 *
 * With a gravity vector measured in each half's own frame, the angle between them **is**
 * the fold angle. This is exactly how a laptop computes its lid angle, and almost
 * certainly how `lid_angle_fusion` derives the value Samsung keeps to itself.
 *
 * ## The maths
 *
 * Both halves rotate only about the hinge axis, so the quantity wanted is the **dihedral
 * angle** — the angle between the two halves measured in the plane perpendicular to that
 * axis. Taking the raw 3-D angle between gravity vectors would be wrong: it conflates
 * folding with any rotation about the other two axes.
 *
 * So each gravity vector is projected onto the plane perpendicular to the hinge axis, and
 * the angle is taken between the projections:
 *
 * ```
 * p₁ = a₁ − (a₁·ĥ)ĥ        p₂ = a₂ − (a₂·ĥ)ĥ
 * dihedral = acos( p₁·p₂ / (|p₁||p₂|) )
 * ```
 *
 * ## Where this is honestly weak
 *
 * Two failure modes, both detected and reported rather than papered over:
 *
 * 1. **Gravity parallel to the hinge.** Hold the phone with the fold line vertical and
 *    both projections collapse toward zero length, leaving the angle undefined. Detected
 *    via projection magnitude; the last good value is held and [confidence] drops.
 * 2. **Movement.** An accelerometer measures gravity *plus* linear acceleration, so
 *    shaking the device corrupts the reading. Detected by checking each vector's magnitude
 *    against g; confidence falls as it deviates.
 *
 * Neither matters much for the actual gesture — opening a foldable is slow and usually
 * done with the hinge roughly horizontal — but both are real, and the diagnostics screen
 * shows confidence live so the limitation is visible rather than mysterious.
 */
class DualAccelHingeSource(
    context: Context,
    /**
     * Hinge axis in each sensor's body frame.
     *
     * For a book-style foldable held in portrait, the fold line runs along the device's
     * **Y** axis, which is the default. Exposed because the sub sensor is marked `[INV]`
     * (inverted mounting) and the axis convention is worth being able to correct without
     * a rebuild.
     */
    var hingeAxis: FloatArray = floatArrayOf(0f, 1f, 0f),
    /**
     * Emit `180 − dihedral` so the value rises as the device opens, matching the standard
     * hinge sensor's convention. Flip if the mounting makes it run backwards.
     */
    var invert: Boolean = true,
    private val samplingPeriodUs: Int = SensorManager.SENSOR_DELAY_GAME,
) : FoldProgressSource, SensorEventListener {

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val timeBase = TimeBase()

    /** The main-half accelerometer. Present on every device. */
    val mainAccel: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /**
     * The second-half accelerometer, found by string type.
     *
     * Matched on `com.samsung.sensor.accelerometer_sub` rather than the integer 65687,
     * because vendor type numbers are meaningless outside the HAL that defines them and
     * would not survive a firmware change.
     */
    val subAccel: Sensor? = sensorManager.getSensorList(Sensor.TYPE_ALL).firstOrNull {
        it.stringType == SUB_ACCEL_STRING_TYPE
    } ?: sensorManager.getSensorList(Sensor.TYPE_ALL).firstOrNull {
        val n = "${it.name} ${it.stringType}".lowercase()
        "accelerometer" in n && ("_sub" in n || "-sub" in n)
    }

    override val kind: ProgressSourceKind = ProgressSourceKind.SENSOR

    private val _samples = MutableSharedFlow<HingeSample>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val samples: Flow<HingeSample> = _samples.asSharedFlow()

    private val mainVec = FloatArray(3)
    private val subVec = FloatArray(3)
    private var haveMain = false
    private var haveSub = false

    /** Last computed dihedral angle in degrees, before inversion. */
    @Volatile
    var dihedralDeg: Float = Float.NaN
        private set

    /**
     * How much to trust the current reading, `0 … 1`.
     *
     * Falls when the device is accelerating (gravity is contaminated) or when the hinge
     * axis is close to parallel with gravity (the projection degenerates).
     */
    @Volatile
    var confidence: Float = 0f
        private set

    @Volatile
    var eventCount: Long = 0L
        private set

    /** Count of events from each sensor, to show which half is (or is not) reporting. */
    @Volatile
    var mainEvents: Long = 0L
        private set

    @Volatile
    var subEvents: Long = 0L
        private set

    private var lastEmitted = Float.NaN
    private var registered = false

    /**
     * Whether both halves expose an accelerometer.
     *
     * Note this only proves the sensor is *listed*. Samsung lists vendor sensors that then
     * deliver nothing, so [subEvents] is the real test — the UI reports it.
     */
    override fun isAvailable(): Boolean = mainAccel != null && subAccel != null

    override fun start() {
        if (registered) return
        val main = mainAccel ?: return
        val sub = subAccel ?: return
        sensorManager.registerListener(this, main, samplingPeriodUs)
        sensorManager.registerListener(this, sub, samplingPeriodUs)
        registered = true
    }

    override fun stop() {
        if (!registered) return
        sensorManager.unregisterListener(this)
        registered = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            mainAccel?.type -> {
                copyInto(event, mainVec)
                haveMain = true
                mainEvents++
            }
            subAccel?.type -> {
                copyInto(event, subVec)
                haveSub = true
                subEvents++
            }
            else -> return
        }
        if (!haveMain || !haveSub) return

        val angle = computeDihedral(mainVec, subVec) ?: return
        dihedralDeg = angle

        val emitted = if (invert) 180f - angle else angle
        lastEmitted = emitted
        eventCount++
        _samples.tryEmit(HingeSample(emitted, timeBase.toFrameClockSafe(event.timestamp)))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun copyInto(event: SensorEvent, out: FloatArray) {
        out[0] = event.values[0]
        out[1] = event.values[1]
        out[2] = event.values[2]
    }

    /**
     * Dihedral angle in degrees, or null when the geometry is too degenerate to trust.
     *
     * Returning null rather than a garbage number matters: a wrong angle emitted
     * confidently would make the animation jump, which is worse than briefly holding still.
     */
    private fun computeDihedral(a: FloatArray, b: FloatArray): Float? {
        val magA = magnitude(a)
        val magB = magnitude(b)
        if (magA < 1e-3f || magB < 1e-3f) {
            confidence = 0f
            return null
        }

        // Reject frames where the device is being accelerated: the vectors are then
        // gravity plus motion, and the angle between them means nothing.
        val restError = (abs(magA - GRAVITY) + abs(magB - GRAVITY)) * 0.5f
        val restConfidence = (1f - restError / REST_TOLERANCE).coerceIn(0f, 1f)

        val h = normalise(hingeAxis)
        val p1 = projectOut(a, h)
        val p2 = projectOut(b, h)

        val m1 = magnitude(p1)
        val m2 = magnitude(p2)

        // Gravity nearly parallel to the hinge: both projections collapse and the angle
        // is undefined.
        val projConfidence = ((minOf(m1, m2) / GRAVITY) / MIN_PROJECTION_RATIO)
            .coerceIn(0f, 1f)

        confidence = restConfidence * projConfidence
        if (m1 < 1e-3f || m2 < 1e-3f) return null
        if (confidence < MIN_USABLE_CONFIDENCE) return null

        val cos = ((p1[0] * p2[0] + p1[1] * p2[1] + p1[2] * p2[2]) / (m1 * m2))
            .coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cos).toDouble()).toFloat()
    }

    private fun projectOut(v: FloatArray, unitAxis: FloatArray): FloatArray {
        val d = v[0] * unitAxis[0] + v[1] * unitAxis[1] + v[2] * unitAxis[2]
        return floatArrayOf(
            v[0] - d * unitAxis[0],
            v[1] - d * unitAxis[1],
            v[2] - d * unitAxis[2],
        )
    }

    private fun magnitude(v: FloatArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    private fun normalise(v: FloatArray): FloatArray {
        val m = magnitude(v)
        if (m < 1e-6f) return floatArrayOf(0f, 1f, 0f)
        return floatArrayOf(v[0] / m, v[1] / m, v[2] / m)
    }

    companion object {
        const val SUB_ACCEL_STRING_TYPE = "com.samsung.sensor.accelerometer_sub"

        private const val GRAVITY = 9.81f

        /** Deviation from g, in m/s², at which motion confidence reaches zero. */
        private const val REST_TOLERANCE = 3.5f

        /**
         * Projection magnitude, as a fraction of g, below which the hinge axis is treated
         * as too close to parallel with gravity. ~25° off vertical.
         */
        private const val MIN_PROJECTION_RATIO = 0.42f

        /** Below this, hold the previous value rather than emit a suspect one. */
        private const val MIN_USABLE_CONFIDENCE = 0.15f
    }
}
