package dev.foldphase.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.View
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service hosting the experimental system-wide overlay (brief §17, §18).
 *
 * A foreground service is required because the overlay must be able to appear while the
 * app is not in the foreground — which is the entire point of the "fold while another app
 * is open" experiment. Android requires a visible notification for that, and the brief
 * asks for it to be persistent while the mode is enabled, which this does deliberately
 * rather than trying to hide it: a user should always be able to see that an overlay mode
 * is armed, and always be one tap from turning it off.
 *
 * The service owns nothing visual itself. It supervises [FoldOverlayController], adds a
 * further **permission poll** (the user can revoke overlay permission from Settings while
 * we hold a window, and there is no broadcast for that), and guarantees teardown in
 * `onDestroy`.
 */
class FoldOverlayService : LifecycleService() {

    private lateinit var controller: FoldOverlayController
    private var permissionPollJob: Job? = null

    /**
     * Supplies the view to show. Set by the app module before starting the service so
     * this module does not have to depend on the renderer's Compose stack directly.
     */
    var overlayViewFactory: ((Context) -> View)? = null

    override fun onCreate() {
        super.onCreate()
        controller = FoldOverlayController(this) { reason ->
            Log.i(TAG, "Overlay torn down: $reason")
        }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> {
                stopSelfSafely()
                return START_NOT_STICKY
            }
            ACTION_SHOW -> showOverlay()
            ACTION_HIDE -> controller.hide("explicit hide")
        }

        startForegroundSafely()
        startPermissionPolling()

        // START_NOT_STICKY on purpose: if the system kills this service, it must NOT be
        // resurrected automatically. A silently restarted overlay service is precisely
        // the "how did this get on my screen" failure the brief rules out, and the user
        // re-enabling the mode explicitly is the correct recovery.
        return START_NOT_STICKY
    }

    private fun showOverlay() {
        val factory = overlayViewFactory
        if (factory == null) {
            Log.w(TAG, "No overlay view factory set; refusing to show")
            return
        }
        if (!controller.hasPermission()) {
            Log.w(TAG, "Overlay permission missing; stopping")
            stopSelfSafely()
            return
        }
        controller.show(factory(this))
    }

    /**
     * Poll for revoked overlay permission.
     *
     * There is no broadcast when a user toggles "Display over other apps" off, so polling
     * is the only way to notice. Two seconds is frequent enough that a revocation is
     * acted on almost immediately and infrequent enough to be free.
     */
    private fun startPermissionPolling() {
        if (permissionPollJob?.isActive == true) return
        permissionPollJob = lifecycleScope.launch {
            while (true) {
                delay(PERMISSION_POLL_MS)
                if (!controller.hasPermission()) {
                    Log.w(TAG, "Overlay permission revoked; tearing down")
                    controller.onPermissionLost()
                    stopSelfSafely()
                    break
                }
            }
        }
    }

    private fun startForegroundSafely() {
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                },
            )
        }.onFailure {
            // If we cannot go foreground we must not keep an overlay alive in the
            // background, so fail closed.
            Log.e(TAG, "startForeground failed; stopping service", it)
            stopSelfSafely()
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, FoldOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Fold overlay active")
            .setContentText("Experimental system-wide fold effect is armed. Tap Stop to disable.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Fold overlay",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while the experimental system-wide fold overlay is enabled."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun stopSelfSafely() {
        controller.hide("service stopping")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // The last line of defence inside the process. Beyond this, process death itself
        // removes the window via WindowManager.
        permissionPollJob?.cancel()
        controller.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    companion object {
        private const val TAG = "FoldOverlayService"
        private const val CHANNEL_ID = "fold_overlay"
        private const val NOTIFICATION_ID = 0x0F01D
        private const val PERMISSION_POLL_MS = 2_000L

        const val ACTION_SHOW = "dev.foldphase.overlay.SHOW"
        const val ACTION_HIDE = "dev.foldphase.overlay.HIDE"
        const val ACTION_STOP = "dev.foldphase.overlay.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, FoldOverlayService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, FoldOverlayService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
