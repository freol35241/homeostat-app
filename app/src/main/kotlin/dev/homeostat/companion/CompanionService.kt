package dev.homeostat.companion

import android.app.Notification
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
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONException
import org.json.JSONObject

/**
 * The app's real surface: a foreground service holding the one MQTT
 * session. The permanent "Homeostat is running" notification is the
 * accepted cost of a socket without Google's cloud (design.md, "The
 * companion app").
 */
class CompanionService : Service(), MqttSession.Listener {
    private var session: MqttSession? = null
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private val nextId = AtomicInteger(FIRST_MESSAGE_ID)

    override fun onCreate() {
        super.onCreate()
        Channels.ensure(this)
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
        when (intent?.action) {
            // Epoch seconds, a bare number: the person saw it, now.
            ACTION_ACK -> session?.publish("notifier/ack", (System.currentTimeMillis() / 1000).toString())
            ACTION_PUBLISH -> session?.publish(intent.getStringExtra(EXTRA_LEAF)!!, intent.getStringExtra(EXTRA_PAYLOAD)!!)
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
        val json = try {
            JSONObject(payload)
        } catch (e: JSONException) {
            Log.w(TAG, "$leaf: not an object: $payload")
            return
        }
        val text = json.optString("text")
        if (text.isEmpty()) {
            Log.w(TAG, "$leaf: no text: $payload")
            return
        }
        val id = nextId.getAndIncrement()
        // Open and dismiss both acknowledge: the tap reaches DashboardActivity
        // with the flag, the swipe reaches AckReceiver.
        val open = PendingIntent.getActivity(
            this, id,
            Intent(this, DashboardActivity::class.java).putExtra(EXTRA_ACK, true),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val dismissed = PendingIntent.getBroadcast(this, id, Intent(this, AckReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, leaf)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(json.optString("actor", getString(R.string.app_name)))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setDeleteIntent(dismissed)
            .setAutoCancel(true)
            .apply {
                val sentAt = json.optDouble("sent_at")
                if (!sentAt.isNaN()) setWhen((sentAt * 1000).toLong()).setShowWhen(true)
                if (leaf == Channels.ALERT) setCategory(Notification.CATEGORY_ALARM)
            }
            .build()
        // An alert keeps sounding until someone deals with it, as ntfy's
        // urgent priority did.
        if (leaf == Channels.ALERT) notification.flags = notification.flags or Notification.FLAG_INSISTENT
        notifications.notify(id, notification)
    }

    private fun statusNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, Channels.SERVICE)
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
        private const val NOTIFICATION_ID = 1
        private const val FIRST_MESSAGE_ID = 100
        private const val ACTION_ACK = "dev.homeostat.companion.ACK"
        private const val ACTION_PUBLISH = "dev.homeostat.companion.PUBLISH"
        private const val EXTRA_LEAF = "leaf"
        private const val EXTRA_PAYLOAD = "payload"
        const val EXTRA_ACK = "ack"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, CompanionService::class.java))
        }

        fun ack(context: Context) {
            context.startForegroundService(Intent(context, CompanionService::class.java).setAction(ACTION_ACK))
        }

        /** Publish in this phone's subtree, from wherever the OS woke us; queued if offline. */
        fun publish(context: Context, leaf: String, payload: String) {
            context.startForegroundService(
                Intent(context, CompanionService::class.java)
                    .setAction(ACTION_PUBLISH)
                    .putExtra(EXTRA_LEAF, leaf)
                    .putExtra(EXTRA_PAYLOAD, payload),
            )
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
