package dev.homeostat.companion

import android.util.Log
import java.nio.charset.StandardCharsets.UTF_8
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

/**
 * Paho behind [Transport]. This is where the protocol's session rules
 * live: a persistent session, the retained `false` last will on
 * `available`, and a keepalive in minutes. Reconnecting is [MqttSession]'s
 * job, so Paho's own automatic reconnect stays off.
 */
class PahoTransport : Transport {
    private var client: MqttAsyncClient? = null

    override fun connect(config: CompanionConfig, callbacks: Transport.Callbacks) {
        val client = client ?: MqttAsyncClient(config.serverUri, config.clientId, MemoryPersistence()).also {
            it.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable) {
                    Log.i(TAG, "connection lost", cause)
                    callbacks.onConnectionLost(cause)
                }
                override fun messageArrived(topic: String, message: MqttMessage) {
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
        client.connect(options, null, object : IMqttActionListener {
            override fun onSuccess(token: IMqttToken) {
                Log.i(TAG, "connected, session present: ${token.sessionPresent}")
                callbacks.onConnected()
            }
            override fun onFailure(token: IMqttToken, cause: Throwable) {
                Log.i(TAG, "connect failed", cause)
                callbacks.onConnectFailed(cause)
            }
        })
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
            runCatching { it.close() }
        }
        client = null
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
