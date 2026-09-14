package dev.homeostat.companion

import android.content.Context

/**
 * The provisioning blob as scanned, in app-private storage, parsed on load —
 * plus the two things the phone decides for itself: whether to share
 * position, and which side of the fence it was last on.
 */
class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE)

    /** Opt-in, off by default, never in the blob: the person holding the phone decides. */
    var sharePosition: Boolean
        get() = prefs.getBoolean(SHARE_POSITION, false)
        set(value) = prefs.edit().putBoolean(SHARE_POSITION, value).apply()

    /** The last geofence transition; null until one has happened. */
    var lastPresence: Boolean?
        get() = if (prefs.contains(PRESENCE)) prefs.getBoolean(PRESENCE, false) else null
        set(value) = prefs.edit().putBoolean(PRESENCE, value!!).apply()

    fun load(): CompanionConfig? =
        prefs.getString(KEY, null)?.let { runCatching { CompanionConfig.parse(it) }.getOrNull() }

    fun save(toml: String) {
        prefs.edit().putString(KEY, toml).apply()
    }

    private companion object {
        const val KEY = "toml"
        const val SHARE_POSITION = "share_position"
        const val PRESENCE = "presence"
    }
}
