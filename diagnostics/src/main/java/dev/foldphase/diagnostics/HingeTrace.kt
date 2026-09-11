package dev.foldphase.diagnostics

import dev.foldphase.core.FoldDirection
import dev.foldphase.core.FoldState

/** One row of the time/angle trace. */
data class TraceSample(
    val timestampNanos: Long,
    val rawAngleDeg: Float,
    val filteredAngleDeg: Float,
    val velocityDegPerSec: Float,
    val progress: Float,
    val direction: FoldDirection,
    val state: FoldState,
)

/**
 * A bounded ring buffer of recent hinge samples, backing both the live graph and the CSV
 * export (brief §3).
 *
 * Fixed capacity, primitive arrays, no allocation on [add]. The graph reads it every
 * frame, so anything that allocated here would show up directly in the frame budget it is
 * supposed to be measuring.
 */
class HingeTrace(val capacity: Int = 2048) {

    private val times = LongArray(capacity)
    private val raw = FloatArray(capacity)
    private val filtered = FloatArray(capacity)
    private val velocity = FloatArray(capacity)
    private val progress = FloatArray(capacity)
    private val direction = IntArray(capacity)
    private val state = IntArray(capacity)

    private var head = 0

    var size: Int = 0
        private set

    var minObservedAngle: Float = Float.POSITIVE_INFINITY
        private set

    var maxObservedAngle: Float = Float.NEGATIVE_INFINITY
        private set

    /** Events counted since the last [resetCounters], for the event-rate readout. */
    var eventCount: Long = 0L
        private set

    private var firstEventNanos = 0L
    private var lastEventNanos = 0L

    fun add(
        timestampNanos: Long,
        rawAngleDeg: Float,
        filteredAngleDeg: Float,
        velocityDegPerSec: Float,
        progressValue: Float,
        dir: FoldDirection,
        foldState: FoldState,
    ) {
        times[head] = timestampNanos
        raw[head] = rawAngleDeg
        filtered[head] = filteredAngleDeg
        velocity[head] = velocityDegPerSec
        progress[head] = progressValue
        direction[head] = dir.ordinal
        state[head] = foldState.ordinal

        head = (head + 1) % capacity
        if (size < capacity) size++

        if (rawAngleDeg.isFinite()) {
            if (rawAngleDeg < minObservedAngle) minObservedAngle = rawAngleDeg
            if (rawAngleDeg > maxObservedAngle) maxObservedAngle = rawAngleDeg
        }

        eventCount++
        if (firstEventNanos == 0L) firstEventNanos = timestampNanos
        lastEventNanos = timestampNanos
    }

    /** Mean event rate in Hz over the whole recorded span. */
    fun averageEventRateHz(): Float {
        val span = lastEventNanos - firstEventNanos
        if (span <= 0L || eventCount < 2) return 0f
        return ((eventCount - 1) * 1_000_000_000.0 / span).toFloat()
    }

    /**
     * Instantaneous event rate over the most recent [windowNanos].
     *
     * Reported alongside the average because an on-change sensor's average rate is
     * meaningless — it is dominated by however long the hinge sat still. The recent rate
     * is what tells you whether the sensor is keeping up while you actually move it.
     */
    fun recentEventRateHz(windowNanos: Long = 1_000_000_000L): Float {
        if (size < 2) return 0f
        val newest = times[((head - 1) % capacity + capacity) % capacity]
        var count = 0
        for (i in 0 until size) {
            val idx = ((head - 1 - i) % capacity + capacity) % capacity
            if (newest - times[idx] > windowNanos) break
            count++
        }
        if (count < 2) return 0f
        return count * 1_000_000_000f / windowNanos
    }

    /** Copy out in chronological order. Allocates — for the graph and export only. */
    fun snapshot(): List<TraceSample> {
        val out = ArrayList<TraceSample>(size)
        for (i in 0 until size) {
            val idx = (head - size + i + capacity * 2) % capacity
            out.add(
                TraceSample(
                    timestampNanos = times[idx],
                    rawAngleDeg = raw[idx],
                    filteredAngleDeg = filtered[idx],
                    velocityDegPerSec = velocity[idx],
                    progress = progress[idx],
                    direction = FoldDirection.entries[direction[idx]],
                    state = FoldState.entries[state[idx]],
                ),
            )
        }
        return out
    }

    /**
     * Fill [into] with the most recent [count] filtered angles, for the graph.
     *
     * Takes a caller-owned array so the graph can keep one buffer and re-use it every
     * frame instead of allocating a list per draw.
     *
     * @return how many entries were written, starting at index 0.
     */
    fun recentFiltered(into: FloatArray, count: Int = into.size): Int {
        val n = minOf(count, size, into.size)
        for (i in 0 until n) {
            val idx = (head - n + i + capacity * 2) % capacity
            into[i] = filtered[idx]
        }
        return n
    }

    fun recentRaw(into: FloatArray, count: Int = into.size): Int {
        val n = minOf(count, size, into.size)
        for (i in 0 until n) {
            val idx = (head - n + i + capacity * 2) % capacity
            into[i] = raw[idx]
        }
        return n
    }

    fun clear() {
        head = 0
        size = 0
        eventCount = 0
        firstEventNanos = 0L
        lastEventNanos = 0L
        minObservedAngle = Float.POSITIVE_INFINITY
        maxObservedAngle = Float.NEGATIVE_INFINITY
    }

    fun resetCounters() {
        eventCount = 0
        firstEventNanos = 0L
        lastEventNanos = 0L
    }

    val hasObservations: Boolean get() = minObservedAngle.isFinite() && maxObservedAngle.isFinite()

    /**
     * Estimated fully-closed / fully-open angles from observation alone (brief §3).
     *
     * These are *observations*, not calibration: they say what the sensor has actually
     * reported, which is how you check whether a stored calibration still matches
     * reality.
     */
    fun estimatedClosedAngle(): Float = if (hasObservations) minObservedAngle else Float.NaN

    fun estimatedOpenAngle(): Float = if (hasObservations) maxObservedAngle else Float.NaN
}
