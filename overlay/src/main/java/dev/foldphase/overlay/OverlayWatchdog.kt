package dev.foldphase.overlay

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * The "never leave a black overlay stuck" enforcement (brief §18).
 *
 * ## Threat model
 *
 * A full-screen, non-interactive overlay that fails to be removed leaves the user with a
 * black screen and no way to interact with the device — a reboot-level failure on a
 * personal phone. The brief is unambiguous that no realistic scenario may produce it.
 *
 * One risk is already handled by the platform and worth stating so it is not re-solved
 * here: **if the process dies, `WindowManager` removes all of its windows automatically.**
 * A crash therefore cannot strand an overlay. What *can* strand one is a process that is
 * still alive but whose logic has stopped making progress — a wedged coroutine, a sensor
 * that went silent, a display reconfiguration that left the state machine confused.
 *
 * So the watchdog defends specifically against a **live process with stale state**, using
 * two independent timers:
 *
 * 1. **Heartbeat.** The renderer must call [heartbeat] on every frame it draws. If
 *    [heartbeatTimeoutMs] passes without one, the overlay is torn down. This catches a
 *    stalled render loop or a sensor that stopped delivering.
 * 2. **Absolute deadline.** No transition can legitimately last longer than
 *    [absoluteTimeoutMs]. When it expires the overlay is torn down regardless of how
 *    healthy everything claims to be. This is the backstop that catches whatever the
 *    heartbeat check did not anticipate, including a heartbeat that keeps firing for the
 *    wrong reason.
 *
 * Both timers run on the main looper, which is also where the overlay is added and
 * removed, so a teardown can never race an add.
 */
class OverlayWatchdog(
    private val heartbeatTimeoutMs: Long = DEFAULT_HEARTBEAT_TIMEOUT_MS,
    private val absoluteTimeoutMs: Long = DEFAULT_ABSOLUTE_TIMEOUT_MS,
    private val onTimeout: (reason: String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var armed = false

    private val heartbeatRunnable = Runnable {
        if (armed) fire("heartbeat timeout (${heartbeatTimeoutMs}ms without a frame)")
    }

    private val absoluteRunnable = Runnable {
        if (armed) fire("absolute timeout (${absoluteTimeoutMs}ms)")
    }

    /** Start watching. Call immediately before the overlay window is added. */
    fun arm() {
        disarm()
        armed = true
        handler.postDelayed(heartbeatRunnable, heartbeatTimeoutMs)
        handler.postDelayed(absoluteRunnable, absoluteTimeoutMs)
    }

    /**
     * Renderer liveness signal. Resets only the heartbeat timer — deliberately **not**
     * the absolute one, since an absolute deadline you can postpone is not a deadline.
     */
    fun heartbeat() {
        if (!armed) return
        handler.removeCallbacks(heartbeatRunnable)
        handler.postDelayed(heartbeatRunnable, heartbeatTimeoutMs)
    }

    /** Stop watching. Call after the overlay window has been removed. */
    fun disarm() {
        armed = false
        handler.removeCallbacks(heartbeatRunnable)
        handler.removeCallbacks(absoluteRunnable)
    }

    val isArmed: Boolean get() = armed

    private fun fire(reason: String) {
        Log.w(TAG, "Overlay watchdog fired: $reason")
        armed = false
        handler.removeCallbacks(heartbeatRunnable)
        handler.removeCallbacks(absoluteRunnable)
        onTimeout(reason)
    }

    companion object {
        private const val TAG = "OverlayWatchdog"

        /**
         * At 120 Hz a frame is 8.33 ms, so 600 ms is ~72 missed frames — far beyond any
         * legitimate hitch, but short enough that a user who hits the failure never sees
         * more than a blink.
         */
        const val DEFAULT_HEARTBEAT_TIMEOUT_MS = 600L

        /**
         * A fold takes at most a couple of seconds even done slowly and deliberately.
         * Six seconds is generous enough never to cut a real transition short and short
         * enough to be unnoticeable as a failure mode.
         */
        const val DEFAULT_ABSOLUTE_TIMEOUT_MS = 6_000L
    }
}
