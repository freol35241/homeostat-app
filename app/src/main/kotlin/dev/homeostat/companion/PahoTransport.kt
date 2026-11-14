package dev.homeostat.companion

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.nio.charset.StandardCharsets.UTF_8
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.MqttPingSender
import org.eclipse.paho.client.mqttv3.internal.ClientComms
import org.eclipse.paho.client.mqttv3.internal.HighResolutionTimer
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

/**
 * Paho behind [Transport]. This is where the protocol's session rules
 * live: a persistent session, the retained `false` last will on
 * `available`, and a keepalive in minutes. Reconnecting is [MqttSession]'s
 * job, so Paho's own automatic reconnect stays off.
 *
 * The keepalive has to survive the phone sleeping: Paho's own pinger is a
 * java.util.Timer and its clock is System.nanoTime, and both stop while
 * the CPU does. The ping is an alarm instead, and the clock counts sleep.
 */
class PahoTransport(private val context: Context) : Transport {
    private var client: MqttAsyncClient? = null
    private var pinger: AlarmPingSender? = null
    private val power = context.getSystemService(PowerManager::class.java)

    override fun connect(config: CompanionConfig, callbacks: Transport.Callbacks) {
        val client = client ?: newClient(config, callbacks)
        val options = MqttConnectOptions().apply {
            isCleanSession = false
            isAutomaticReconnect = false
            keepAliveInterval = KEEPALIVE_S
            connectionTimeout = CONNECT_TIMEOUT_S
            userName = config.username
            password = config.password.toCharArray()
            setWill(config.topic("available"), "false".toByteArray(UTF_8), 1, true)
        }
        Log.i(TAG, "connecting to ${config.serverUri} as ${config.clientId}")
        // Up until the attempt resolves, or a retry woken by an alarm sleeps
        // again before the broker answers.
        val wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "homeostat:connect")
            .apply { acquire((CONNECT_TIMEOUT_S + 5) * 1000L) }
        try {
            client.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(token: IMqttToken) {
                    wake.releaseIfHeld()
                    if (this@PahoTransport.client !== client) return
                    Log.i(TAG, "connected, session present: ${token.sessionPresent}")
                    callbacks.onConnected()
                }
                override fun onFailure(token: IMqttToken, cause: Throwable) {
                    wake.releaseIfHeld()
                    if (this@PahoTransport.client !== client) return
                    Log.i(TAG, "connect failed", cause)
                    callbacks.onConnectFailed(cause)
                }
            })
        } catch (e: MqttException) {
            // Paho throws rather than calls back when the client is in a
            // state it will not leave (already connecting, disconnecting).
            // Uncaught, no retry was ever scheduled; a fresh client next time.
            Log.w(TAG, "connect refused, discarding the client", e)
            wake.releaseIfHeld()
            close()
            callbacks.onConnectFailed(e)
        }
    }

    private fun newClient(config: CompanionConfig, callbacks: Transport.Callbacks): MqttAsyncClient {
        val pinger = AlarmPingSender(context, power).also { this.pinger = it }
        return MqttAsyncClient(
            config.serverUri, config.clientId, MemoryPersistence(), pinger, null,
            HighResolutionTimer { SystemClock.elapsedRealtimeNanos() },
        ).also {
            // A discarded client may still call back; only the current one speaks.
            it.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable) {
                    if (client !== it) return
                    Log.i(TAG, "connection lost", cause)
                    callbacks.onConnectionLost(cause)
                }
                override fun messageArrived(topic: String, message: MqttMessage) {
                    if (client !== it) return
                    val payload = String(message.payload, UTF_8)
                    Log.i(TAG, "received $topic, ${message.payload.size} bytes")
                    callbacks.onMessage(topic, payload)
                }
                override fun deliveryComplete(token: IMqttDeliveryToken) {
                    Log.i(TAG, "delivered ${token.topics?.joinToString()}")
                }
            })
            this.client = it
        }
    }

    override fun subscribe(topics: List<String>) {
        Log.i(TAG, "subscribing to ${topics.joinToString()}")
        client?.subscribe(topics.toTypedArray(), IntArray(topics.size) { 1 })
    }

    override fun publish(topic: String, payload: String, retained: Boolean) {
        Log.i(TAG, "publishing $topic${if (retained) " (retained)" else ""}, ${payload.length} chars")
        client?.publish(topic, payload.toByteArray(UTF_8), 1, retained)
    }

    override fun disconnect() {
        client?.let {
            Log.i(TAG, "disconnecting")
            // The quiesce lets the session's own `false` reach the broker
            // first, and bounds how long onDestroy blocks the main thread
            // (the one-argument overload waits up to 30 s).
            runCatching { it.disconnectForcibly(QUIESCE_TIMEOUT_MS, DISCONNECT_TIMEOUT_MS) }
        }
        close()
    }

    private fun close() {
        client?.let { runCatching { it.close() } }
        client = null
        pinger?.close()
        pinger = null
    }

    private companion object {
        const val TAG = "PahoTransport"
        // Minutes, not seconds: the tunnel keeps itself alive.
        const val KEEPALIVE_S = 5 * 60
        const val CONNECT_TIMEOUT_S = 30
        const val QUIESCE_TIMEOUT_MS = 2_000L
        const val DISCONNECT_TIMEOUT_MS = 2_000L
    }
}

/**
 * Paho's pinger on [AlarmScheduler]. On each alarm Paho decides whether a
 * ping is due, and the CPU stays up until the broker answers it.
 */
private class AlarmPingSender(context: Context, private val power: PowerManager) : MqttPingSender {
    private val scheduler = AlarmScheduler(context, "ping")
    private lateinit var comms: ClientComms
    private var pending: Cancellable? = null

    override fun init(comms: ClientComms) {
        this.comms = comms
    }

    override fun start() = schedule(comms.keepAlive)

    @Synchronized
    override fun stop() {
        pending?.cancel()
        pending = null
    }

    @Synchronized
    override fun schedule(delayInMilliseconds: Long) {
        pending?.cancel()
        pending = scheduler.schedule(delayInMilliseconds) { ping() }
    }

    fun close() {
        stop()
        scheduler.close()
    }

    private fun ping() {
        val wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "homeostat:ping").apply { acquire(PING_WAKE_MS) }
        val token = comms.checkForActivity(object : IMqttActionListener {
            override fun onSuccess(token: IMqttToken) = wake.releaseIfHeld()
            override fun onFailure(token: IMqttToken, cause: Throwable) = wake.releaseIfHeld()
        })
        // No token: no ping was due, and Paho has scheduled the next check.
        if (token == null) wake.releaseIfHeld()
    }

    private companion object {
        const val PING_WAKE_MS = 15_000L
    }
}

// A timed wakelock may have run out by the time its callback comes.
private fun PowerManager.WakeLock.releaseIfHeld() {
    if (isHeld) release()
}
