package dev.foldphase.renderer

import kotlin.math.roundToInt

/**
 * Frame-timing instrumentation (brief §22).
 *
 * Records inter-frame intervals into a fixed ring buffer and reports mean, P95, P99,
 * dropped-frame count and effective refresh rate. Fixed capacity and no allocation on
 * [record] — an instrument that perturbs what it measures is worse than no instrument.
 *
 * A "dropped" frame is one whose interval exceeded 1.5x the expected frame time, which
 * catches a genuinely missed vsync without flagging the normal jitter around it.
 */
class FrameMetrics(private val capacity: Int = 240) {

    private val intervalsNanos = LongArray(capacity)
    private var head = 0
    private var size = 0
    private var lastFrameNanos = 0L

    var droppedFrames: Int = 0
        private set

    var totalFrames: Long = 0L
        private set

    /** Expected frame interval, derived from the display's reported refresh rate. */
    var expectedFrameNanos: Long = 8_333_333L // 120 Hz

    fun setRefreshRate(hz: Float) {
        if (hz > 1f) expectedFrameNanos = (1_000_000_000.0 / hz).toLong()
    }

    fun reset() {
        head = 0
        size = 0
        lastFrameNanos = 0L
        droppedFrames = 0
        totalFrames = 0L
    }

    fun record(frameTimeNanos: Long) {
        totalFrames++
        if (lastFrameNanos != 0L) {
            val delta = frameTimeNanos - lastFrameNanos
            // A negative or absurd delta means the clock moved under us; ignore it rather
            // than poisoning the statistics.
            if (delta in 1..MAX_PLAUSIBLE_NANOS) {
                intervalsNanos[head] = delta
                head = (head + 1) % capacity
                if (size < capacity) size++
                if (delta > expectedFrameNanos * 3 / 2) droppedFrames++
            }
        }
        lastFrameNanos = frameTimeNanos
    }

    fun snapshot(): FrameStats {
        if (size == 0) return FrameStats()
        val sorted = LongArray(size) { intervalsNanos[it] }
        sorted.sort()
        var sum = 0L
        for (v in sorted) sum += v
        val mean = sum.toDouble() / size
        return FrameStats(
            sampleCount = size,
            averageMs = (mean / 1_000_000.0).toFloat(),
            p95Ms = (percentile(sorted, 0.95) / 1_000_000.0).toFloat(),
            p99Ms = (percentile(sorted, 0.99) / 1_000_000.0).toFloat(),
            droppedFrames = droppedFrames,
            totalFrames = totalFrames,
            effectiveHz = if (mean > 0) (1_000_000_000.0 / mean).toFloat() else 0f,
            expectedHz = (1_000_000_000.0 / expectedFrameNanos).toFloat(),
        )
    }

    private fun percentile(sorted: LongArray, q: Double): Long {
        if (sorted.isEmpty()) return 0L
        val idx = ((sorted.size - 1) * q).roundToInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    private companion object {
        const val MAX_PLAUSIBLE_NANOS = 1_000_000_000L
    }
}

data class FrameStats(
    val sampleCount: Int = 0,
    val averageMs: Float = 0f,
    val p95Ms: Float = 0f,
    val p99Ms: Float = 0f,
    val droppedFrames: Int = 0,
    val totalFrames: Long = 0L,
    val effectiveHz: Float = 0f,
    val expectedHz: Float = 0f,
) {
    /** Fraction of frames that missed their deadline. */
    val dropRate: Float
        get() = if (totalFrames > 0) droppedFrames.toFloat() / totalFrames else 0f
}
