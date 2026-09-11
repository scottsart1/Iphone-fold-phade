package dev.foldphase.overlay

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.annotation.MainThread

/**
 * Adds and removes the transient full-screen overlay, and guarantees it goes away
 * (brief §17, §18).
 *
 * ## What this can and cannot do
 *
 * This overlay is **shader-only**. It dims, blurs its own drawn content, casts a hinge
 * shadow and reveals — but it does **not** and cannot contain a copy of whatever app is
 * underneath, because Android does not let a normal app read other apps' pixels without
 * `MediaProjection` (which shows a recording indicator and needs per-session consent) or
 * privileged access. The brief anticipates exactly this in §17 and asks that it be stated
 * rather than worked around; see `docs/LIMITATIONS.md` for the full account. Nothing here
 * abuses an Accessibility Service to get around it.
 *
 * ## Removal guarantees
 *
 * Every one of these removes the overlay, and they are independent of each other:
 *
 * - [hide] called by the transition logic when the fold completes.
 * - [OverlayWatchdog] heartbeat timeout — the renderer stopped drawing.
 * - [OverlayWatchdog] absolute timeout — a hard deadline that nothing can extend.
 * - `ACTION_SCREEN_OFF` — the display went away under us.
 * - `ACTION_CONFIGURATION_CHANGED` — display geometry changed unexpectedly.
 * - [onPermissionLost] — the overlay permission was revoked while we held a window.
 * - Service destruction, which calls [release].
 * - Process death, which `WindowManager` handles for us by removing all our windows.
 *
 * [hide] is idempotent and never throws: a teardown path that can fail is not a teardown
 * path. Every removal is wrapped, because `removeViewImmediate` on an already-removed
 * view throws, and the one thing worse than a stuck overlay is an exception on the code
 * that was trying to unstick it.
 */
class FoldOverlayController(
    private val context: Context,
    private val onTornDown: (reason: String) -> Unit = {},
) {
    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var overlayView: View? = null
    private var receiverRegistered = false

    private val watchdog = OverlayWatchdog { reason -> hide("watchdog: $reason") }

    /**
     * System events that must tear the overlay down.
     *
     * Screen-off is the important one: if the display turns off mid-transition and we
     * were still holding a window, the user may next see the device in a state we never
     * rendered for.
     */
    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> hide("screen off")
                Intent.ACTION_CONFIGURATION_CHANGED -> hide("configuration changed")
                Intent.ACTION_SHUTDOWN -> hide("shutdown")
            }
        }
    }

    val isShowing: Boolean get() = overlayView != null

    /** Whether the user has granted `SYSTEM_ALERT_WINDOW` (brief §2 Level C). */
    fun hasPermission(): Boolean = Settings.canDrawOverlays(context)

    /**
     * Show [view] as a non-interactive full-screen overlay.
     *
     * @return true if the overlay was added.
     */
    @MainThread
    fun show(view: View): Boolean {
        if (!hasPermission()) {
            Log.w(TAG, "Refusing to show overlay: SYSTEM_ALERT_WINDOW not granted")
            return false
        }
        if (overlayView != null) return true

        return try {
            windowManager.addView(view, buildLayoutParams())
            overlayView = view
            registerReceiver()
            watchdog.arm()
            true
        } catch (t: Throwable) {
            // A failed add can still have left partial state; run the full teardown
            // rather than assuming nothing happened.
            Log.e(TAG, "Failed to add overlay", t)
            hide("add failed: ${t.message}")
            false
        }
    }

    /** Renderer liveness signal; see [OverlayWatchdog.heartbeat]. */
    fun heartbeat() = watchdog.heartbeat()

    /**
     * Remove the overlay. Idempotent, never throws, safe from any thread that can post to
     * the main looper — and safe to call when nothing is showing.
     */
    @MainThread
    fun hide(reason: String = "requested") {
        watchdog.disarm()
        unregisterReceiver()

        val view = overlayView ?: return
        overlayView = null

        try {
            windowManager.removeViewImmediate(view)
        } catch (t: Throwable) {
            // Already gone, or never fully attached. Either way the goal — no overlay on
            // screen — is met, so this is logged and swallowed rather than propagated.
            Log.w(TAG, "removeViewImmediate threw during teardown (overlay already gone?)", t)
        }
        onTornDown(reason)
    }

    /** Called when the app observes that overlay permission was revoked. */
    fun onPermissionLost() = hide("overlay permission revoked")

    /** Full cleanup. Call from `Service.onDestroy`. */
    fun release() {
        hide("released")
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
            addAction(Intent.ACTION_SHUTDOWN)
        }
        // These are protected system broadcasts, so NOT_EXPORTED is correct and required
        // from API 34 onward.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(systemReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(systemReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        runCatching { context.unregisterReceiver(systemReceiver) }
            .onFailure { Log.w(TAG, "unregisterReceiver threw", it) }
        receiverRegistered = false
    }

    private fun buildLayoutParams() = WindowManager.LayoutParams().apply {
        type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        format = PixelFormat.TRANSLUCENT
        width = WindowManager.LayoutParams.MATCH_PARENT
        height = WindowManager.LayoutParams.MATCH_PARENT
        gravity = Gravity.TOP or Gravity.START

        // Non-interactive, per brief §2 Level C. NOT_TOUCHABLE means every touch passes
        // straight through to whatever is underneath, so even a stuck overlay would leave
        // the device usable — a second line of defence behind the watchdog.
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        // Draw under the cutout and behind the system bars so the effect is genuinely
        // edge-to-edge; a letterboxed overlay would betray itself instantly.
        layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    private companion object {
        const val TAG = "FoldOverlayController"
    }
}
