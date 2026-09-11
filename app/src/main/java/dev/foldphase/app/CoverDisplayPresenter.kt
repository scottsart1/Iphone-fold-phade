package dev.foldphase.app

import android.app.Activity
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.core.ExperimentalWindowApi
import androidx.window.area.WindowAreaCapability
import androidx.window.area.WindowAreaController
import androidx.window.area.WindowAreaInfo
import androidx.window.area.WindowAreaPresentationSessionCallback
import androidx.window.area.WindowAreaSession
import androidx.window.area.WindowAreaSessionPresenter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

/**
 * Renders content on the **cover display while the device is unfolded**, via
 * `WindowAreaController`'s concurrent-display (dual-screen) mode.
 *
 * ## What this buys us, and what it does not
 *
 * The reference proof-of-concept (research §2) used the Presentation API to drive both
 * panels, and that is the right mechanism: it is how a normal app gets to draw on the
 * cover display at all. `WindowAreaController.presentContentOnWindowArea()` is the
 * supported Jetpack route to the same thing.
 *
 * The honest limitation, found during research (§3.2) and worth stating at the call site
 * rather than burying in a document:
 *
 * - The capability only reports `AVAILABLE` while the device is **unfolded**. So this
 *   cannot be used to silently own the cover display *through* the fold — which is
 *   exactly the moment we care most about.
 * - Starting a session shows a **non-customisable system dialog**.
 * - Concurrent display support is device-dependent and must be probed, never assumed.
 *
 * Consequently this is an **enhancement**, not the mechanism the transition depends on.
 * The transition's continuity comes from the portal model (both panels evaluating the
 * same function of progress — research §4.1), which needs no privileged access at all.
 * When a session is available, this additionally keeps the cover panel showing the
 * matching frame; when it is not, nothing breaks.
 *
 * [status] exposes the real capability so the UI can tell the user what their device
 * actually supports instead of guessing.
 */
@OptIn(ExperimentalWindowApi::class)
class CoverDisplayPresenter(
    private val activity: Activity,
    private val lifecycleOwner: LifecycleOwner,
) : WindowAreaPresentationSessionCallback {

    private val controller: WindowAreaController = WindowAreaController.getOrCreate()
    private val executor: Executor = ContextCompat.getMainExecutor(activity)

    private var windowAreaInfo: WindowAreaInfo? = null
    private var session: WindowAreaSession? = null
    private var presenter: WindowAreaSessionPresenter? = null

    private val _status = MutableStateFlow(WindowAreaCapability.Status.WINDOW_AREA_STATUS_UNSUPPORTED)
    val status: StateFlow<WindowAreaCapability.Status> = _status.asStateFlow()

    private val _isPresenting = MutableStateFlow(false)
    val isPresenting: StateFlow<Boolean> = _isPresenting.asStateFlow()

    /** Supplies the view to show on the cover display. Set before calling [start]. */
    var contentFactory: ((android.content.Context) -> View)? = null

    private val operation = WindowAreaCapability.Operation.OPERATION_PRESENT_ON_AREA

    fun observe() {
        lifecycleOwner.lifecycleScope.launch {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.windowAreaInfos
                    .map { infos -> infos.firstOrNull { it.type == WindowAreaInfo.Type.TYPE_REAR_FACING } }
                    .onEach { windowAreaInfo = it }
                    .map { info ->
                        info?.getCapability(operation)?.status
                            ?: WindowAreaCapability.Status.WINDOW_AREA_STATUS_UNSUPPORTED
                    }
                    .distinctUntilChanged()
                    .collect { _status.value = it }
            }
        }
    }

    /** Begin presenting on the cover display, if the device allows it right now. */
    fun start(): Boolean {
        if (_status.value != WindowAreaCapability.Status.WINDOW_AREA_STATUS_AVAILABLE) {
            Log.i(TAG, "Cover presentation not available (status=${_status.value})")
            return false
        }
        val token = windowAreaInfo?.token ?: return false
        return runCatching {
            controller.presentContentOnWindowArea(
                token = token,
                activity = activity,
                executor = executor,
                windowAreaPresentationSessionCallback = this,
            )
            true
        }.getOrElse {
            Log.e(TAG, "presentContentOnWindowArea failed", it)
            false
        }
    }

    fun stop() {
        runCatching { session?.close() }
            .onFailure { Log.w(TAG, "Closing cover session threw", it) }
        session = null
        presenter = null
        _isPresenting.value = false
    }

    override fun onSessionStarted(session: WindowAreaSessionPresenter) {
        this.session = session
        this.presenter = session
        _isPresenting.value = true
        val factory = contentFactory
        if (factory == null) {
            Log.w(TAG, "Cover session started with no content factory; closing")
            stop()
            return
        }
        runCatching { session.setContentView(factory(session.context)) }
            .onFailure {
                Log.e(TAG, "setContentView on cover display failed", it)
                stop()
            }
    }

    override fun onSessionEnded(t: Throwable?) {
        if (t != null) Log.e(TAG, "Cover session ended with error", t)
        session = null
        presenter = null
        _isPresenting.value = false
    }

    override fun onContainerVisibilityChanged(isVisible: Boolean) {
        Log.d(TAG, "Cover container visible: $isVisible")
    }

    /** Human-readable capability, for the UI. */
    fun statusDescription(): String = when (_status.value) {
        WindowAreaCapability.Status.WINDOW_AREA_STATUS_UNSUPPORTED ->
            "Not supported on this device"
        WindowAreaCapability.Status.WINDOW_AREA_STATUS_UNAVAILABLE ->
            "Unavailable right now (usually means the device is folded)"
        WindowAreaCapability.Status.WINDOW_AREA_STATUS_AVAILABLE ->
            "Available"
        WindowAreaCapability.Status.WINDOW_AREA_STATUS_ACTIVE ->
            "Active"
        else -> "Unknown"
    }

    private companion object {
        const val TAG = "CoverDisplayPresenter"
    }
}
