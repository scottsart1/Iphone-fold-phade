package dev.foldphase.sensors

import kotlin.math.abs

/**
 * Estimates hinge angular velocity in degrees/second by least-squares fitting a line
 * through a short sliding window of recent samples.
 *
 * ## Why not just `(x1 - x0) / (t1 - t0)`
 *
 * The hinge sensor is on-change with a quantised resolution (often 0.1–1.0°). A two-point
 * difference across a short interval therefore reports either 0 or a large spike,
 * depending on whether a quantisation step happened to land inside the interval. Feeding
 * that into the direction logic makes it thrash between OPENING and CLOSING exactly as
 * the brief (§6) warns.
 *
 * A regression over a window spanning ~80 ms averages the quantisation out while staying
 * short enough to track a fast snap-open. The window is a fixed-size ring buffer, so this
 * allocates nothing after construction and is safe on the frame path (brief §22).
 */
class VelocityEstimator(
    private val capacity: Int = DEFAULT_CAPACITY,
    /** Samples older than this are excluded from the fit. */
    var windowNanos: Long = DEFAULT_WINDOW_NANOS,
) {
    private val values = FloatArray(capacity)
    private val times = LongArray(capacity)
    private var head = 0
    private var size = 0

    /** Most recent velocity estimate, degrees/second. Positive = opening. */
    var velocityDegPerSec: Float = 0f
        private set

    fun reset() {
        head = 0
        size = 0
        velocityDegPerSec = 0f
    }

    fun add(angleDeg: Float, timeNanos: Long): Float {
        values[head] = angleDeg
        times[head] = timeNanos
        head = (head + 1) % capacity
        if (size < capacity) size++
        velocityDegPerSec = computeVelocity(timeNanos)
        return velocityDegPerSec
    }

    /**
     * Re-evaluate with no new sample. Called on frames where the sensor stayed silent so
     * that a hinge which has stopped moving decays to zero velocity rather than holding
     * the last non-zero estimate forever.
     */
    fun tick(nowNanos: Long): Float {
        velocityDegPerSec = computeVelocity(nowNanos)
        return velocityDegPerSec
    }

    private fun computeVelocity(nowNanos: Long): Float {
        if (size < 2) return 0f

        val cutoff = nowNanos - windowNanos
        var n = 0
        var sumT = 0.0
        var sumV = 0.0
        var sumTT = 0.0
        var sumTV = 0.0

        // Walk newest-to-oldest so we can stop as soon as we fall out of the window.
        for (i in 0 until size) {
            val idx = ((head - 1 - i) % capacity + capacity) % capacity
            val t = times[idx]
            if (t < cutoff) break
            // Seconds relative to now: keeps the numbers small and well-conditioned.
            val ts = (t - nowNanos) / 1_000_000_000.0
            val v = values[idx].toDouble()
            sumT += ts
            sumV += v
            sumTT += ts * ts
            sumTV += ts * v
            n++
        }

        if (n < 2) return 0f
        val denom = n * sumTT - sumT * sumT
        if (abs(denom) < 1e-12) return 0f
        val slope = (n * sumTV - sumT * sumV) / denom
        return slope.toFloat()
    }

    private companion object {
        const val DEFAULT_CAPACITY = 24
        const val DEFAULT_WINDOW_NANOS = 80_000_000L // 80 ms
    }
}
