package dev.homeostat.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CompanionConfigTest {
    private val example = """
        broker = "mqtt://10.0.0.1:1883/companion"
        phone = "alice"
        username = "alice-phone"
        password = "s3cret"
    """.trimIndent()

    @Test
    fun `parses the protocol's example`() {
        val config = CompanionConfig.parse(example)
        assertEquals("10.0.0.1", config.host)
        assertEquals(1883, config.port)
        assertEquals("companion", config.baseTopic)
        assertEquals("alice", config.phone)
        assertEquals("alice-phone", config.username)
        assertEquals("s3cret", config.password)
        assertEquals("companion-alice", config.clientId)
        assertEquals("tcp://10.0.0.1:1883", config.serverUri)
        assertEquals("companion/alice/notifier/ack", config.topic("notifier/ack"))
    }

    @Test
    fun `base topic defaults to companion and port to 1883`() {
        val config = CompanionConfig.parse(example.replace("mqtt://10.0.0.1:1883/companion", "mqtt://broker.house"))
        assertEquals("broker.house", config.host)
        assertEquals(1883, config.port)
        assertEquals("companion", config.baseTopic)
    }

    @Test
    fun `the broker path is the base topic, slashes and all`() {
        val config = CompanionConfig.parse(example.replace("/companion", "/other/prefix/"))
        assertEquals("other/prefix", config.baseTopic)
        assertEquals("other/prefix/alice/available", config.topic("available"))
    }

    @Test
    fun `dashboard is optional and must be a web URL`() {
        assertNull(CompanionConfig.parse(example).dashboard)
        assertEquals(
            "http://10.0.0.1:8080",
            CompanionConfig.parse(example + "\ndashboard = \"http://10.0.0.1:8080\"").dashboard,
        )
        assertThrows(ConfigException::class.java) {
            CompanionConfig.parse(example + "\ndashboard = \"10.0.0.1:8080\"")
        }
    }

    @Test
    fun `home is optional, takes integers or floats, and defaults the radius`() {
        assertNull(CompanionConfig.parse(example).home)
        assertEquals(
            CompanionConfig.Home(59.33, 18.0, 150f),
            CompanionConfig.parse(example + "\nhome = { lat = 59.33, lon = 18 }").home,
        )
        assertEquals(
            CompanionConfig.Home(59.33, 18.06, 200f),
            CompanionConfig.parse(example + "\nhome = { lat = 59.33, lon = 18.06, radius_m = 200 }").home,
        )
        assertThrows(ConfigException::class.java) {
            CompanionConfig.parse(example + "\nhome = { lat = \"59.33\", lon = 18.06 }")
        }
    }

    @Test
    fun `a missing key names the key`() {
        val e = assertThrows(ConfigException::class.java) {
            CompanionConfig.parse(example.replace("password = \"s3cret\"", ""))
        }
        assertEquals("Missing 'password'", e.message)
    }

    @Test
    fun `rejects a non-mqtt broker`() {
        assertThrows(ConfigException::class.java) {
            CompanionConfig.parse(example.replace("mqtt://", "http://"))
        }
    }

    @Test
    fun `rejects a phone that is not one topic segment`() {
        assertThrows(ConfigException::class.java) {
            CompanionConfig.parse(example.replace("\"alice\"", "\"alice/person\""))
        }
    }

    @Test
    fun `rejects a wrongly typed key`() {
        assertThrows(ConfigException::class.java) {
            CompanionConfig.parse(example.replace("\"alice\"", "7"))
        }
    }

    @Test
    fun `rejects text that is not TOML`() {
        assertThrows(ConfigException::class.java) { CompanionConfig.parse("https://example.com/not-a-config") }
    }
}
