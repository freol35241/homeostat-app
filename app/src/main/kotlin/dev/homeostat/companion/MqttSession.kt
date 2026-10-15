package dev.homeostat.companion

/**
 * The one persistent session the phone holds (companion-protocol.md,
 * "Connection"): connect, resubscribe to the two downward topics, publish
 * the retained birth message, and on any drop back off and try again.
 *
 * The transport and the clock are seams so this is a pure-JVM test. What
 * lives behind them — clean_session=false, the last will, the keepalive
 * — is [PahoTransport]'s, and is not covered by the unit tests.
 */
class MqttSession(
    private val config: CompanionConfig,
    private val transport: Transport,
    private val scheduler: Scheduler,
    private val listener: Listener,
    // What the phone said while it had no broker: acks and presence
    // transitions wait here for the next connect, in order.
    private val outbox: Outbox,
) : Transport.Callbacks {

    sealed interface State {
        data object Connecting : State
        data object Connected : State
        data class Waiting(val retryInMs: Long, val cause: String?) : State
    }

    interface Listener {
        fun onStateChanged(state: State)

        /** A `message` or `alert` arrived; [leaf] is which. */
        fun onNotification(leaf: String, payload: String)
    }

    private val downward = listOf(config.topic("notifier/message"), config.topic("notifier/alert"))
    private var running = false
    private var connected = false
    private var attempt = 0
    private var pendingRetry: Cancellable? = null
    // The last notifications shown, oldest first, to drop redeliveries.
    private val recent = LinkedHashSet<String>()

    @Synchronized
    fun start() {
        if (running) return
        running = true
        connect()
    }

    @Synchronized
    fun stop() {
        // A clean disconnect suppresses the last will, so the phone says
        // `false` itself; otherwise the retained `true` outlives it.
        if (connected) transport.publish(config.topic("available"), "false", retained = true)
        running = false
        connected = false
        pendingRetry?.cancel()
        pendingRetry = null
        transport.disconnect()
    }

    /** Publish `{base}/{phone}/{leaf}` at QoS 1, now or on the next connect; retained only if presence. */
    @Synchronized
    fun publish(leaf: String, payload: String) {
        if (connected) transport.publish(config.topic(leaf), payload, retained = leaf in RETAINED) else outbox.add(leaf, payload)
    }

    private fun connect() {
        listener.onStateChanged(State.Connecting)
        transport.connect(config, this)
    }

    @Synchronized
    override fun onConnected() {
        if (!running) return
        attempt = 0
        connected = true
        // Resubscribe on every reconnect, as the protocol asks; with a
        // persistent session the broker already remembers, and it is cheap.
        transport.subscribe(downward)
        transport.publish(config.topic("available"), "true", retained = true)
        for ((leaf, payload) in outbox.drain()) {
            transport.publish(config.topic(leaf), payload, retained = leaf in RETAINED)
        }
        listener.onStateChanged(State.Connected)
    }

    @Synchronized
    override fun onConnectFailed(cause: Throwable) = retryLater(cause)

    @Synchronized
    override fun onConnectionLost(cause: Throwable) = retryLater(cause)

    @Synchronized
    override fun onMessage(topic: String, payload: String) {
        if (!running || topic !in downward) return
        // QoS 1 is at least once: a redelivery is byte-identical, `sent_at`
        // included, and two real notifications never share a `sent_at`.
        val key = "$topic $payload"
        if (key in recent) return
        recent += key
        if (recent.size > RECENT_MAX) recent.remove(recent.first())
        listener.onNotification(topic.substringAfterLast('/'), payload)
    }

    private fun retryLater(cause: Throwable) {
        connected = false
        if (!running) return
        val delay = backoffMs(attempt++)
        listener.onStateChanged(State.Waiting(delay, cause.message))
        pendingRetry = scheduler.schedule(delay) {
            synchronized(this) {
                pendingRetry = null
                if (running) connect()
            }
        }
    }

    companion object {
        const val INITIAL_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 5 * 60_000L
        private const val RECENT_MAX = 64

        // Presence is state, not an event: retained, so an adapter that was
        // restarting reads the current side of the fence when it is back.
        private val RETAINED = setOf("person/presence")

        /** Doubling from 2 s, capped at 5 min. */
        fun backoffMs(attempt: Int): Long =
            (INITIAL_BACKOFF_MS shl attempt.coerceAtMost(20)).coerceAtMost(MAX_BACKOFF_MS)
    }
}

/** What the session needs from an MQTT client. Every call is asynchronous. */
interface Transport {
    interface Callbacks {
        fun onConnected()
        fun onConnectFailed(cause: Throwable)
        fun onConnectionLost(cause: Throwable)
        fun onMessage(topic: String, payload: String)
    }

    fun connect(config: CompanionConfig, callbacks: Callbacks)
    fun subscribe(topics: List<String>)
    fun publish(topic: String, payload: String, retained: Boolean)
    fun disconnect()
}

/** Leaf and payload pairs, in order, kept somewhere a process death does not reach. */
interface Outbox {
    fun add(leaf: String, payload: String)

    /** Everything queued, oldest first, and empty afterwards. */
    fun drain(): List<Pair<String, String>>
}

fun interface Scheduler {
    fun schedule(delayMs: Long, task: Runnable): Cancellable
}

fun interface Cancellable {
    fun cancel()
}
