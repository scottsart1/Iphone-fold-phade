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

/**
 * The single abstraction both the physical hinge and the simulator implement (brief §27).
 *
 * Everything downstream — filter, velocity, state machine, animation engine, renderer —
 * sees only this, which is what lets the virtual-hinge slider exercise *exactly* the same
 * code path as the real sensor. If tuning looks right in the simulator it will look right
 * on the device, because nothing below this interface knows the difference.
 */
interface FoldProgressSource {
    val kind: ProgressSourceKind

    /** Raw readings, already converted to the frame clock base. */
    val samples: Flow<HingeSample>

    /** True if this source can actually produce values on this device. */
    fun isAvailable(): Boolean

    fun start()
    fun stop()
}

/**
 * A hardware hinge-angle source.
 *
 * ## Why the sensor is a parameter
 *
 * This defaulted to `TYPE_HINGE_ANGLE` and hard-coded it. On the Galaxy Z Fold 7
 * (SM-F966U) that sensor declares `resolution = 90.0` over a `0 … 180` range, which read
 * literally means three reportable values — useless for scrubbing an animation. The same
 * device exposes several Samsung-specific fold sensors declaring far finer resolution.
 *
 * Which one actually delivers continuous data is an empirical question, answered by
 * [SensorProbe] on the device rather than guessed here. So the sensor is injected, and the
 * app can switch source without a rebuild.
 *
 * ## Notes that matter
 *
 * - The sensor is **on-change**. It fires when the angle changes and goes silent when the
 *   hinge is still, so the requested sampling period is an upper bound on latency, not a
 *   promise of a steady rate. Nothing downstream may assume a fixed rate.
 * - Wake-up behaviour is per-device. On the Fold 7 the hinge sensor *is* a wake-up sensor,
 *   contrary to what is often assumed.
 * - `values[0]` is in whatever unit the sensor chooses — degrees for the standard one, but
 *   possibly a normalised `0 … 1` for a vendor sensor. That is deliberately not this
 *   class's problem: [HingeCalibration] normalises whatever range it is handed, so the
 *   pipeline is unit-agnostic.
 */
class HingeAngleSensorSource(
    context: Context,
    /** The sensor to read. Defaults to the standard hinge sensor when present. */
    sensor: Sensor? = null,
    /** Requested sampling period. `SENSOR_DELAY_FASTEST` for the lowest latency. */
    private val samplingPeriodUs: Int = SensorManager.SENSOR_DELAY_FASTEST,
) : FoldProgressSource, SensorEventListener {

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val timeBase = TimeBase()

    /** The sensor being read, or null if none is available. */
    val sensor: Sensor? = sensor ?: sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)

    override val kind: ProgressSourceKind = ProgressSourceKind.SENSOR

    // Replay 1 so a late subscriber immediately knows where the hinge is instead of
    // waiting for the next physical movement — which, on an on-change sensor, might
    // never come.
    private val _samples = MutableSharedFlow<HingeSample>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val samples: Flow<HingeSample> = _samples.asSharedFlow()

    /** Count of events received — the diagnostics screen derives event rate from this. */
    @Volatile
    var eventCount: Long = 0L
        private set

    @Volatile
    var lastEventNanos: Long = 0L
        private set

    private var registered = false

    override fun isAvailable(): Boolean = sensor != null

    override fun start() {
        val s = sensor ?: return
        if (registered) return
        registered = sensorManager.registerListener(this, s, samplingPeriodUs)
    }

    override fun stop() {
        if (!registered) return
        sensorManager.unregisterListener(this)
        registered = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        // Match the sensor we actually registered; a vendor fold sensor has its own type.
        if (event.sensor.type != sensor?.type) return
        // Keep this callback as cheap as possible (brief §22): convert the clock, publish,
        // return. No filtering, no state machine, no allocation beyond the sample itself.
        val nanos = timeBase.toFrameClockSafe(event.timestamp)
        eventCount++
        lastEventNanos = nanos
        _samples.tryEmit(HingeSample(event.values[0], nanos))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

/**
 * The virtual hinge (brief §27).
 *
 * Feeds synthetic samples through the identical pipeline so the animation can be tuned
 * without folding the phone hundreds of times, and so the effect can be developed on a
 * device that has no hinge sensor at all.
 */
class VirtualHingeSource : FoldProgressSource {

    override val kind: ProgressSourceKind = ProgressSourceKind.SIMULATOR

    private val _samples = MutableSharedFlow<HingeSample>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val samples: Flow<HingeSample> = _samples.asSharedFlow()

    override fun isAvailable(): Boolean = true

    override fun start() = Unit
    override fun stop() = Unit

    /** Push an angle, as if the hinge had just moved there. */
    fun emit(angleDeg: Float, timeNanos: Long = System.nanoTime()) {
        _samples.tryEmit(HingeSample(angleDeg, timeNanos))
    }
}
