package com.daftar.app.ink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.daftar.app.R
import java.lang.ref.WeakReference

/**
 * Microphone foreground service for lecture recording: while it runs, Android keeps the app's microphone access with the
 * screen off or the app in the background (both the live-transcript AudioRecord path and the MediaRecorder path).
 *
 * It does no audio work itself. The recorder stays in the editor; this service only owns the ongoing notification
 * ("Recording lecture" + a system chronometer, so there are no per-second updates) and sends its Pause / Resume / Stop
 * actions to the editor through [control]. No wakelock: while the mic is recording, the audio system keeps the CPU
 * awake as it needs.
 *
 * Main thread only. [start] must be called while the app is in the foreground (Android 12+ / 14 rules); if the system
 * refuses, recording simply continues without the service (as before).
 */
class RecordingService : Service() {

    enum class Action { PAUSE, RESUME, STOP }

    companion object {
        private const val CHANNEL = "lecture_recording"
        private const val NOTIF_ID = 4711
        private const val A_START = "com.daftar.app.rec.START"
        private const val A_PAUSE = "com.daftar.app.rec.PAUSE"
        private const val A_RESUME = "com.daftar.app.rec.RESUME"
        private const val A_STOP = "com.daftar.app.rec.STOP"

        /** The editor's handler for notification actions (null = no editor recording). */
        private var control: ((Action) -> Unit)? = null
        private var wanted = false
        private var title = ""
        private var paused = false
        private var elapsedAtUpdate = 0L      // recording time (ms) when [stamp] was taken
        private var stamp = 0L                // System.currentTimeMillis() at the last state change
        private var instance: WeakReference<RecordingService>? = null

        /** Recording started (editor in the foreground): shows the notification and keeps the mic alive. */
        fun start(ctx: Context, noteTitle: String, onAction: (Action) -> Unit) {
            control = onAction
            wanted = true; title = noteTitle; paused = false
            elapsedAtUpdate = 0L; stamp = System.currentTimeMillis()
            val running = instance?.get()
            if (running != null) { running.refresh(); return }
            runCatching {
                ContextCompat.startForegroundService(ctx.applicationContext, Intent(ctx.applicationContext, RecordingService::class.java).setAction(A_START))
            }   // ForegroundServiceStartNotAllowedException etc.: recording continues without the service
        }

        /** Pause / resume from the editor (bar or notification): switches the chronometer, one notification update. */
        fun update(isPaused: Boolean, elapsedMs: Long) {
            paused = isPaused; elapsedAtUpdate = elapsedMs; stamp = System.currentTimeMillis()
            instance?.get()?.refresh()
        }

        /** Recording stopped or editor disposed. */
        fun stop() {
            wanted = false
            control = null
            instance?.get()?.finish()
        }
    }

    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = WeakReference(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            A_START -> {
                // startForeground must follow startForegroundService even when the recording already stopped
                if (!goForeground()) { stopSelf(); return START_NOT_STICKY }
                if (!wanted) finish()
            }
            A_PAUSE -> dispatch(Action.PAUSE)
            A_RESUME -> dispatch(Action.RESUME)
            A_STOP -> dispatch(Action.STOP)
            else -> { if (!wanted) stopSelf() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (instance?.get() === this) instance = null
        super.onDestroy()
    }

    private fun dispatch(a: Action) {
        val c = control
        if (c == null || !wanted) { finish(); return }
        main.post { c(a) }
    }

    private fun goForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, build(), type)
        true
    } catch (e: Throwable) { false }

    fun refresh() {
        if (!wanted) return
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build()) }
    }

    fun finish() {
        if (instance?.get() === this) instance = null   // a new start() makes a fresh service
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun build(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.tr_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false); setSound(null, null); enableVibration(false)
            })
        val imm = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        fun svc(action: String, req: Int) = PendingIntent.getService(this, req, Intent(this, RecordingService::class.java).setAction(action), imm)
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let { PendingIntent.getActivity(this, 0, it, imm) }
        val elapsed = if (paused) elapsedAtUpdate else elapsedAtUpdate + (System.currentTimeMillis() - stamp)
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(if (paused) getString(R.string.tr_notif_paused, fmtTime(elapsed)) else getString(R.string.tr_notif_recording))
            .setContentText(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
        if (paused) {
            b.setShowWhen(false).setUsesChronometer(false)
            b.addAction(0, getString(R.string.tr_resume_rec), svc(A_RESUME, 2))
        } else {
            // system chronometer counts by itself: no per-second notification updates
            b.setShowWhen(true).setUsesChronometer(true).setWhen(System.currentTimeMillis() - elapsed)
            b.addAction(0, getString(R.string.tr_pause_rec), svc(A_PAUSE, 1))
        }
        b.addAction(0, getString(R.string.ink_stop), svc(A_STOP, 3))
        return b.build()
    }
}
