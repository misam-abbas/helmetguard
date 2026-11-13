package com.misamabbas.helmetguard.video

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.misamabbas.helmetguard.MainActivity
import com.misamabbas.helmetguard.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Keeps a video analysis running with the screen off or the app in the background. */
@AndroidEntryPoint
class VideoAnalysisService : Service() {
    @Inject lateinit var manager: VideoAnalysisManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sessionId = intent?.getStringExtra(EXTRA_SESSION)
        if (sessionId == null || running) return START_NOT_STICKY

        createChannel()
        // If Android refuses every type, the analysis still runs while the app is open, and the
        // session stays resumable if Android stops it later.
        startInForeground()
        running = true

        val work = scope.launch(Dispatchers.Default) {
            manager.run(sessionId)
        }
        scope.launch {
            while (isActive && work.isActive) {
                val state = manager.ui.value
                val percent = if (state.durationMs > 0) (100 * state.positionMs / state.durationMs).toInt() else 0
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(percent, indeterminate = state.durationMs == 0L))
                delay(NOTIFY_EVERY_MS)
            }
            ServiceCompat.stopForeground(this@VideoAnalysisService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Android limits how long this kind of service may run. The session stays resumable. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        manager.interrupt()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Tries each service type the manifest declares. Android versions differ in which they accept. */
    private fun startInForeground(): Boolean {
        val notification = build(0, indeterminate = true)
        for (type in serviceTypes()) {
            try {
                startForeground(NOTIFICATION_ID, notification, type)
                Log.i(TAG, "Foreground service started with type $type")
                return true
            } catch (e: RuntimeException) {
                Log.w(TAG, "Android refused foreground service type $type", e)
            }
        }
        return false
    }

    private fun serviceTypes() = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) add(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        add(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        add(ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Video analysis", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun build(percent: Int, indeterminate: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Analysing video")
            .setContentText(if (indeterminate) "Starting…" else "$percent% done")
            .setProgress(100, percent, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val TAG = "HelmetGuardVideo"
        private const val CHANNEL_ID = "video_analysis"
        private const val NOTIFICATION_ID = 41
        private const val EXTRA_SESSION = "session_id"
        private const val NOTIFY_EVERY_MS = 2_000L

        fun start(context: Context, sessionId: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, VideoAnalysisService::class.java).putExtra(EXTRA_SESSION, sessionId),
            )
        }
    }
}
