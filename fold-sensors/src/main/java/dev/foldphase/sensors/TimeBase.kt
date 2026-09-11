package dev.foldphase.sensors

import android.os.SystemClock

/**
 * Reconciles the two nanosecond clocks this app has to straddle.
 *
 * `SensorEvent.timestamp` on Samsung hardware is on the **`elapsedRealtimeNanos`** base
 * (it keeps counting while the device is suspended). `Choreographer` frame times are on
 * the **`System.nanoTime`** base (it does not). Subtracting one from the other produces
 * a velocity that is wrong by however long the device has been asleep — which on a phone
 * that spends most of its life suspended is catastrophic, not subtle.
 *
 * The brief (§5) specifically asks for sensor-supplied timestamps rather than wall-clock
 * timing. That is right, but only once both are expressed in the same base. This class
 * measures the offset and applies it, re-measuring periodically so that drift between the
 * two clocks (they tick at the same rate but diverge across suspends) cannot accumulate.
 *
 * Not thread-safe by design: it is touched only from the sensor callback thread.
 */
class TimeBase {

    private var offsetNanos: Long = 0L
    private var haveOffset = false
    private var lastCalibrationNanos: Long = 0L

    /**
     * Convert an `elapsedRealtimeNanos`-based sensor timestamp to the `System.nanoTime`
     * base used by the frame clock.
     */
    fun toFrameClock(sensorTimestampNanos: Long): Long {
        val now = System.nanoTime()
        if (!haveOffset || now - lastCalibrationNanos > RECALIBRATE_INTERVAL_NANOS) {
            // Read both clocks as close together as possible. The residual error is the
            // scheduling gap between the two reads — tens of microseconds at worst,
            // which is three orders of magnitude below a frame.
            val elapsed = SystemClock.elapsedRealtimeNanos()
            val mono = System.nanoTime()
            offsetNanos = mono - elapsed
            haveOffset = true
            lastCalibrationNanos = mono
        }
        return sensorTimestampNanos + offsetNanos
    }

    /**
     * Some devices report sensor timestamps already on the monotonic base, or report
     * zero. Detect an implausible conversion and fall back to "now" rather than emitting
     * a wildly wrong delta.
     */
    fun toFrameClockSafe(sensorTimestampNanos: Long): Long {
        if (sensorTimestampNanos <= 0L) return System.nanoTime()
        val converted = toFrameClock(sensorTimestampNanos)
        val now = System.nanoTime()
        val skew = converted - now
        return if (skew > PLAUSIBLE_SKEW_NANOS || skew < -PLAUSIBLE_SKEW_NANOS) now else converted
    }

    private companion object {
        const val RECALIBRATE_INTERVAL_NANOS = 5_000_000_000L // 5 s
        const val PLAUSIBLE_SKEW_NANOS = 2_000_000_000L // 2 s
    }
}
