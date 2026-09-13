package dev.homeostat.companion

import java.net.URI
import java.net.URISyntaxException
import org.tomlj.Toml
import org.tomlj.TomlInvalidTypeException

/**
 * The provisioning blob (companion-protocol.md, "Provisioning a phone"):
 * four TOML keys, scanned from a QR code or pasted, and everything the
 * session needs is derived from them.
 *
 * ```toml
 * broker = "mqtt://10.0.0.1:1883/companion"
 * phone = "alice"
 * username = "alice-phone"
 * password = "..."
 * ```
 *
 * The broker URL's path is the base topic; absent, it is `companion`.
 *
 * `dashboard` (optional) is the dashboard unit's URL for the WebView, and
 * `home = { lat, lon, radius_m }` (optional) the one geofence — both
 * proposed in homeostat#84 and not yet in the protocol document.
 */
data class CompanionConfig(
    val host: String,
    val port: Int,
    val baseTopic: String,
    val phone: String,
    val username: String,
    val password: String,
    val dashboard: String? = null,
    val home: Home? = null,
) {
    data class Home(val lat: Double, val lon: Double, val radiusM: Float)

    /** Stable for the life of the install, per the protocol. */
    val clientId: String get() = "companion-$phone"

    /** Paho's spelling of a plain TCP broker. */
    val serverUri: String get() = "tcp://$host:$port"

    /** A topic in this phone's subtree: `{base}/{phone}/{leaf}`. */
    fun topic(leaf: String): String = "$baseTopic/$phone/$leaf"

    companion object {
        const val DEFAULT_BASE_TOPIC = "companion"
        private const val DEFAULT_PORT = 1883
        // Android's geofencing is unreliable much under 100 m; a lot plus GPS slop.
        private const val DEFAULT_RADIUS_M = 150f

        /** @throws ConfigException with a message fit for showing to the person holding the phone. */
        fun parse(text: String): CompanionConfig {
            val toml = Toml.parse(text)
            if (toml.hasErrors()) {
                throw ConfigException("Not valid TOML: ${toml.errors().first().message}")
            }
            fun string(key: String): String {
                val value = try {
                    toml.getString(key)
                } catch (e: TomlInvalidTypeException) {
                    throw ConfigException("'$key' must be a string")
                }
                return value?.takeIf { it.isNotEmpty() } ?: throw ConfigException("Missing '$key'")
            }

            val broker = try {
                URI(string("broker"))
            } catch (e: URISyntaxException) {
                throw ConfigException("'broker' is not a URL")
            }
            if (broker.scheme != "mqtt") throw ConfigException("'broker' must be an mqtt:// URL")
            val host = broker.host ?: throw ConfigException("'broker' has no host")
            val baseTopic = broker.path.orEmpty().trim('/').ifEmpty { DEFAULT_BASE_TOPIC }

            val phone = string("phone")
            if (phone.any { it == '/' || it == '+' || it == '#' }) {
                throw ConfigException("'phone' must be a single topic segment")
            }

            val dashboard = toml.getString("dashboard")?.takeIf { it.isNotEmpty() }?.also {
                val scheme = runCatching { URI(it).scheme }.getOrNull()
                if (scheme != "http" && scheme != "https") throw ConfigException("'dashboard' must be an http:// URL")
            }

            val home = toml.getTable("home")?.let { table ->
                fun number(key: String): Double? = (table.get(key) as? Number)?.toDouble()
                Home(
                    lat = number("lat") ?: throw ConfigException("'home.lat' must be a number"),
                    lon = number("lon") ?: throw ConfigException("'home.lon' must be a number"),
                    radiusM = number("radius_m")?.toFloat() ?: DEFAULT_RADIUS_M,
                )
            }

            return CompanionConfig(
                host = host,
                port = if (broker.port == -1) DEFAULT_PORT else broker.port,
                baseTopic = baseTopic,
                phone = phone,
                username = string("username"),
                password = string("password"),
                dashboard = dashboard,
                home = home,
            )
        }
    }
}

class ConfigException(message: String) : Exception(message)
