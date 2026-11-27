package dev.homeostat.companion

import org.junit.Assert.assertEquals
import org.junit.Test

class OutboxTest {
    @Test
    fun `under the cap nothing is dropped`() {
        val items = listOf("person/position" to "1", "notifier/ack" to "5", "person/position" to "2")
        assertEquals(items, withoutOldPositions(items, max = 2))
    }

    @Test
    fun `over the cap the oldest positions go, the rest stay in order`() {
        val items = listOf(
            "person/position" to "1",
            "person/presence" to "false",
            "person/position" to "2",
            "notifier/ack" to "5",
            "person/position" to "3",
        )
        assertEquals(
            listOf("person/presence" to "false", "notifier/ack" to "5", "person/position" to "3"),
            withoutOldPositions(items, max = 1),
        )
    }

    @Test
    fun `acks and presence are never dropped`() {
        val items = List(300) { "notifier/ack" to "$it" } + List(300) { "person/presence" to "${it % 2 == 0}" }
        assertEquals(items, withoutOldPositions(items))
    }
}
