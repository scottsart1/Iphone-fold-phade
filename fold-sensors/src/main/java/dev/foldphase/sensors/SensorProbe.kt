package dev.foldphase.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live statistics for one candidate hinge sensor.
 *
 * [distinctValues] is the field that matters. Everything else is context.
 */
data class ProbeStats(
    val sensorType: Int,
    val name: String,
    val stringType: String,
    val declaredResolution: Float,
    val declaredMaxRange: Float,
    val isWakeUp: Boolean,
    val eventCount: Long = 0L,
    val values: FloatArray = FloatArray(3),
    val valueCount: Int = 0,
    val minValue: Float = Float.NaN,
    val maxValue: Float = Float.NaN,
    /**
     * How many distinct `values[0]` readings have been seen.
     *
     * This is the whole point of the probe. A sensor that genuinely reports a continuous
     * angle produces dozens of distinct values across one slow fold. A sensor quantised to
     * a handful of detent positions produces three or four, no matter how slowly you move
     * it — and no amount of filtering can invent the values in between.
     */
    val distinctValues: Int = 0,
    /** The distinct readings themselves, capped. Shows *which* values, not just how many. */
    val sampleValues: List<Float> = emptyList(),
    val recentRateHz: Float = 0f,
) {
    /**
     * Verdict on whether this sensor can drive a hinge-coupled animation.
     *
     * Deliberately refuses to judge before enough of a fold has been seen: calling a
     * sensor quantised after four events would be worse than saying nothing.
     */
    val verdict: Verdict
        get() = when {
            eventCount < MIN_EVENTS_TO_JUDGE -> Verdict.INSUFFICIENT_DATA
            distinctValues >= CONTINUOUS_THRESHOLD -> Verdict.CONTINUOUS
            distinctValues <= QUANTISED_THRESHOLD -> Verdict.QUANTISED
            else -> Verdict.COARSE
        }

    enum class Verdict {
        /** Not enough events yet. Move the hinge more. */
        INSUFFICIENT_DATA,

        /** Enough distinct values to scrub an animation. Usable. */
        CONTINUOUS,

        /** Some granularity, but chunky. Usable with heavy smoothing; will feel stepped. */
        COARSE,

        /** Only a few detent positions. Cannot drive a continuous animation. */
        QUANTISED,
    }

    val span: Float
        get() = if (minValue.isFinite() && maxValue.isFinite()) maxValue - minValue else 0f

    // FloatArray in a data class needs these; the generated ones compare by identity.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProbeStats) return false
        return sensorType == other.sensorType &&
            eventCount == other.eventCount &&
            distinctValues == other.distinctValues &&
            values.contentEquals(other.values)
    }

    override fun hashCode(): Int =
        31 * (31 * sensorType + eventCount.hashCode()) + values.contentHashCode()

    companion object {
        const val MIN_EVENTS_TO_JUDGE = 12
        const val CONTINUOUS_THRESHOLD = 20
        const val QUANTISED_THRESHOLD = 6
    }
}

/**
 * Registers every plausible hinge sensor at once and reports what each actually delivers.
 *
 * ## Why this exists
 *
 * On the Galaxy Z Fold 7 (SM-F966U), the standard `android.sensor.hinge_angle` declares
 * `resolution = 90.0` over a `0 … 180` range — which, read literally, means it reports
 * three values and cannot drive a continuously-scrubbed animation at all.
 *
 * Declared resolution is not reliable, though: vendor HALs frequently fill it with a
 * placeholder. The only way to know is to watch what the sensor emits during a real fold
 * and count the distinct readings.
 *
 * The same device also exposes three Samsung-specific fold sensors — `folding_angle`,
 * `folding_state`/`lid_angle_fusion`, and `folding_state_lpm` — each declaring a much
 * finer `0.01` resolution. If the standard sensor turns out to be quantised, one of those
 * is the way forward, so the probe watches all of them simultaneously and lets the winner
 * be chosen by evidence.
 *
 * Vendor sensors may report in any unit — degrees, a normalised `0 … 1`, or something
 * else entirely. That does not matter downstream: [HingeCalibration] normalises whatever
 * range it is given, so the pipeline is unit-agnostic by construction.
 */
class SensorProbe(context: Context) : SensorEventListener {

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    /** Every sensor worth testing as a hinge-angle source, standard one first. */
    val candidates: List<Sensor> = buildList {
        sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)?.let { add(it) }
        sensorManager.getSensorList(Sensor.TYPE_ALL)
            .filter { it.type != Sensor.TYPE_HINGE_ANGLE && looksFoldRelated(it) }
            .sortedBy { it.name }
            .forEach { add(it) }
    }

    private val trackers = HashMap<Int, Tracker>()

    private val _stats = MutableStateFlow<List<ProbeStats>>(emptyList())
    val stats: StateFlow<List<ProbeStats>> = _stats.asStateFlow()

    private var registered = false

    fun start() {
        if (registered) return
        candidates.forEach { sensor ->
            trackers.getOrPut(sensor.type) { Tracker(sensor) }
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST)
        }
        registered = true
        publish()
    }

    fun stop() {
        if (!registered) return
        sensorManager.unregisterListener(this)
        registered = false
    }

    fun reset() {
        trackers.values.forEach { it.reset() }
        publish()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val tracker = trackers[event.sensor.type] ?: return
        tracker.record(event)
        publish()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun publish() {
        _stats.value = candidates.mapNotNull { trackers[it.type]?.snapshot() }
    }

    /** The candidate with the strongest evidence of being continuous, if any. */
    fun bestCandidate(): ProbeStats? = _stats.value
        .filter { it.verdict == ProbeStats.Verdict.CONTINUOUS }
        .maxByOrNull { it.distinctValues }

    private class Tracker(val sensor: Sensor) {
        private val distinct = LinkedHashSet<Float>()
        private val latest = FloatArray(3)
        private var count = 0L
        private var valueCount = 0
        private var min = Float.NaN
        private var max = Float.NaN
        private var firstNanos = 0L
        private var lastNanos = 0L

        fun reset() {
            distinct.clear()
            count = 0
            min = Float.NaN
            max = Float.NaN
            firstNanos = 0L
            lastNanos = 0L
        }

        fun record(event: SensorEvent) {
            count++
            valueCount = minOf(event.values.size, 3)
            for (i in 0 until valueCount) latest[i] = event.values[i]

            val v = event.values[0]
            if (v.isFinite()) {
                // Bounded: a genuinely continuous sensor would otherwise grow this
                // without limit over a long session.
                if (distinct.size < MAX_DISTINCT) distinct.add(v)
                if (min.isNaN() || v < min) min = v
                if (max.isNaN() || v > max) max = v
            }

            if (firstNanos == 0L) firstNanos = event.timestamp
            lastNanos = event.timestamp
        }

        fun snapshot(): ProbeStats {
            val spanNanos = lastNanos - firstNanos
            val rate = if (spanNanos > 0 && count > 1) {
                ((count - 1) * 1_000_000_000.0 / spanNanos).toFloat()
            } else {
                0f
            }
            return ProbeStats(
                sensorType = sensor.type,
                name = sensor.name ?: "(unnamed)",
                stringType = sensor.stringType ?: "",
                declaredResolution = sensor.resolution,
                declaredMaxRange = sensor.maximumRange,
                isWakeUp = sensor.isWakeUpSensor,
                eventCount = count,
                values = latest.copyOf(),
                valueCount = valueCount,
                minValue = min,
                maxValue = max,
                distinctValues = distinct.size,
                // Sorted so a quantised sensor's detents are immediately obvious.
                sampleValues = distinct.sorted().take(MAX_SHOWN),
                recentRateHz = rate,
            )
        }

        companion object {
            const val MAX_DISTINCT = 512
            const val MAX_SHOWN = 24
        }
    }

    private companion object {
        /**
         * Name/type matching for vendor fold sensors.
         *
         * Matches on the string type and the human name, because Samsung's fold sensors
         * use vendor type integers (65686, 65695, 65697 on the Fold 7) that carry no
         * meaning outside their own HAL.
         */
        fun looksFoldRelated(sensor: Sensor): Boolean {
            val haystack = "${sensor.name} ${sensor.stringType}".lowercase()
            if ("grip" in haystack || "pocket" in haystack) return false
            return "hinge" in haystack ||
                "folding" in haystack ||
                "fold_" in haystack ||
                "lid_angle" in haystack ||
                "lid angle" in haystack
        }
    }
}
