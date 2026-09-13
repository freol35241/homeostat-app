package dev.homeostat.companion

import android.content.Context

/** The provisioning blob as scanned, in app-private storage; parsed on load. */
class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE)

    fun load(): CompanionConfig? =
        prefs.getString(KEY, null)?.let { runCatching { CompanionConfig.parse(it) }.getOrNull() }

    fun save(toml: String) {
        prefs.edit().putString(KEY, toml).apply()
    }

    private companion object {
        const val KEY = "toml"
    }
}
