package dev.homeostat.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttSessionTest {
    private val config = CompanionConfig("h", 1883, "companion", "alice", "u", "p")
    private val transport = FakeTransport()
    private val scheduler = FakeScheduler()
    private val listener = RecordingListener()
    private val session = MqttSession(config, transport, scheduler, listener)

    @Test
    fun `start connects, then subscribes both channels before the birth message`() {
        session.start()
        assertEquals(listOf("connect"), transport.calls)
        transport.callbacks.onConnected()
        assertEquals(
            listOf(
                "connect",
                "subscribe companion/alice/notifier/message,companion/alice/notifier/alert",
                "publish companion/alice/available true retained",
            ),
            transport.calls,
        )
        assertEquals(listOf(MqttSession.State.Connecting, MqttSession.State.Connected), listener.states)
    }

    @Test
    fun `a failed connect backs off, doubling to a cap`() {
        session.start()
        val delays = mutableListOf<Long>()
        repeat(12) {
            transport.callbacks.onConnectFailed(RuntimeException("no route"))
            delays += scheduler.pending.single().delayMs
            scheduler.runNext()
        }
        assertEquals(
            listOf(2_000L, 4_000, 8_000, 16_000, 32_000, 64_000, 128_000, 256_000, 300_000, 300_000, 300_000, 300_000),
            delays,
        )
        assertEquals(13, transport.calls.count { it == "connect" })
        assertEquals(MqttSession.State.Waiting(2_000, "no route"), listener.states[1])
    }

    @Test
    fun `a lost connection reconnects and resubscribes, with the backoff reset`() {
        session.start()
        transport.callbacks.onConnectFailed(RuntimeException())
        scheduler.runNext()
        transport.callbacks.onConnected()
        transport.calls.clear()

        transport.callbacks.onConnectionLost(RuntimeException("eof"))
        assertEquals(MqttSession.INITIAL_BACKOFF_MS, scheduler.pending.single().delayMs)
        scheduler.runNext()
        transport.callbacks.onConnected()
        assertEquals("connect", transport.calls[0])
        assertTrue(transport.calls[1].startsWith("subscribe "))
        assertTrue(transport.calls[2].startsWith("publish companion/alice/available true"))
    }

    @Test
    fun `stop cancels a pending retry and disconnects, and late callbacks are ignored`() {
        session.start()
        transport.callbacks.onConnectFailed(RuntimeException())
        session.stop()
        assertTrue(scheduler.pending.single().cancelled)
        assertEquals("disconnect", transport.calls.last())
        transport.calls.clear()

        transport.callbacks.onConnected()
        transport.callbacks.onConnectionLost(RuntimeException())
        assertEquals(emptyList<String>(), transport.calls)
        assertEquals(emptyList<Cancellable>(), scheduler.pending.filter { !it.cancelled })
    }

    @Test
    fun `publish goes straight out while connected, never retained`() {
        session.start()
        transport.callbacks.onConnected()
        session.publish("notifier/ack", "1752600000")
        assertEquals("publish companion/alice/notifier/ack 1752600000", transport.calls.last())
    }

    @Test
    fun `publish while disconnected waits for the next connect, after the birth message, in order`() {
        session.publish("notifier/ack", "1")
        session.start()
        transport.callbacks.onConnectFailed(RuntimeException())
        session.publish("person/presence", "false")
        scheduler.runNext()
        transport.callbacks.onConnected()
        assertEquals(
            listOf(
                "publish companion/alice/available true retained",
                "publish companion/alice/notifier/ack 1",
                "publish companion/alice/person/presence false",
            ),
            transport.calls.filter { it.startsWith("publish") },
        )

        transport.callbacks.onConnectionLost(RuntimeException())
        transport.calls.clear()
        session.publish("notifier/ack", "2")
        assertEquals(emptyList<String>(), transport.calls)
    }

    @Test
    fun `only the two downward topics reach the listener, by leaf`() {
        session.start()
        transport.callbacks.onConnected()
        transport.callbacks.onMessage("companion/alice/notifier/alert", "{}")
        transport.callbacks.onMessage("companion/bob/notifier/alert", "{}")
        transport.callbacks.onMessage("companion/alice/notifier/ack", "1")
        assertEquals(listOf("alert" to "{}"), listener.notifications)
    }

    private class FakeTransport : Transport {
        val calls = mutableListOf<String>()
        lateinit var callbacks: Transport.Callbacks

        override fun connect(config: CompanionConfig, callbacks: Transport.Callbacks) {
            this.callbacks = callbacks
            calls += "connect"
        }

        override fun subscribe(topics: List<String>) {
            calls += "subscribe ${topics.joinToString(",")}"
        }

        override fun publish(topic: String, payload: String, retained: Boolean) {
            calls += "publish $topic $payload" + if (retained) " retained" else ""
        }

        override fun disconnect() {
            calls += "disconnect"
        }
    }

    private class FakeScheduler : Scheduler {
        class Scheduled(val delayMs: Long, val task: Runnable) : Cancellable {
            var cancelled = false
            override fun cancel() { cancelled = true }
        }

        val pending = mutableListOf<Scheduled>()

        override fun schedule(delayMs: Long, task: Runnable): Cancellable =
            Scheduled(delayMs, task).also { pending += it }

        fun runNext() {
            val next = pending.removeFirst()
            if (!next.cancelled) next.task.run()
        }
    }

    private class RecordingListener : MqttSession.Listener {
        val states = mutableListOf<MqttSession.State>()
        val notifications = mutableListOf<Pair<String, String>>()
        override fun onStateChanged(state: MqttSession.State) { states += state }
        override fun onNotification(leaf: String, payload: String) { notifications += leaf to payload }
    }
}
