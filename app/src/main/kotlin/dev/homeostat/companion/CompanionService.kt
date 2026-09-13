package dev.homeostat.companion

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
import android.util.Log

/**
 * The app's real surface: a foreground service holding the one MQTT
 * session. The permanent "Homeostat is running" notification is the
 * accepted cost of a socket without Google's cloud (design.md, "The
 * companion app").
 */
class CompanionService : Service(), MqttSession.Listener {
    private var session: MqttSession? = null
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_service), NotificationManager.IMPORTANCE_LOW),
        )
        val notification = statusNotification(getString(R.string.status_starting))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (session == null) {
            val config = ConfigStore(this).load()
            if (config == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            session = MqttSession(config, PahoTransport(), HandlerScheduler(), this).also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        session?.stop()
        session = null
        Status.update(getString(R.string.status_stopped))
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStateChanged(state: MqttSession.State) {
        val text = when (state) {
            MqttSession.State.Connecting -> getString(R.string.status_connecting)
            MqttSession.State.Connected -> getString(R.string.status_connected)
            is MqttSession.State.Waiting ->
                getString(R.string.status_waiting, state.retryInMs / 1000, state.cause ?: "")
        }
        Status.update(text)
        notifications.notify(NOTIFICATION_ID, statusNotification(text))
    }

    override fun onNotification(leaf: String, payload: String) {
        // The message and alert channels are the next step; until then the
        // subscription exists so the persistent session queues nothing lost.
        Log.i(TAG, "$leaf: $payload")
    }

    private fun statusNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    /** Main-looper timers. Under Doze these slip; the battery exemption is what keeps them honest. */
    private class HandlerScheduler : Scheduler {
        private val handler = Handler(Looper.getMainLooper())
        override fun schedule(delayMs: Long, task: Runnable): Cancellable {
            handler.postDelayed(task, delayMs)
            return Cancellable { handler.removeCallbacks(task) }
        }
    }

    companion object {
        private const val TAG = "CompanionService"
        private const val CHANNEL = "service"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, CompanionService::class.java))
        }

        /** A re-provision: the old session goes down with its own last will, the new one comes up. */
        fun restart(context: Context) {
            context.stopService(Intent(context, CompanionService::class.java))
            start(context)
        }
    }
}

/** The session's state, for the screen; observed on the main thread. */
object Status {
    @Volatile var text: String = ""
        private set
    @Volatile var observer: ((String) -> Unit)? = null

    fun update(text: String) {
        this.text = text
        Handler(Looper.getMainLooper()).post { observer?.invoke(text) }
    }
}
