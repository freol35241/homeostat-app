package dev.homeostat.companion

import android.annotation.SuppressLint
import android.content.Context
import org.json.JSONArray

/**
 * The provisioning blob as scanned, in app-private storage, parsed on load —
 * plus the two things the phone decides for itself: whether to share
 * position, and which side of the fence it was last on — and the
 * session's outbox, so what the phone said offline outlives the process.
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

    /**
     * Committed rather than applied: the point is surviving a kill that
     * lands right after the write. A re-provision empties it, since a
     * queued leaf would otherwise go out under the new phone's subtree.
     * Positions are capped, see [withoutOldPositions].
     */
    @SuppressLint("ApplySharedPref")
    val outbox = object : Outbox {
        override fun add(leaf: String, payload: String) {
            val items = JSONArray()
            for ((l, p) in withoutOldPositions(queued() + (leaf to payload))) items.put(JSONArray().put(l).put(p))
            prefs.edit().putString(OUTBOX, items.toString()).commit()
        }

        override fun drain(): List<Pair<String, String>> =
            queued().also { prefs.edit().remove(OUTBOX).commit() }

        private fun queued(): List<Pair<String, String>> {
            val items = JSONArray(prefs.getString(OUTBOX, "[]"))
            return List(items.length()) { i -> items.getJSONArray(i).let { it.getString(0) to it.getString(1) } }
        }
    }

    fun load(): CompanionConfig? =
        prefs.getString(KEY, null)?.let { runCatching { CompanionConfig.parse(it) }.getOrNull() }

    fun save(toml: String) {
        prefs.edit().putString(KEY, toml).remove(OUTBOX).apply()
    }

    private companion object {
        const val KEY = "toml"
        const val SHARE_POSITION = "share_position"
        const val PRESENCE = "presence"
        const val OUTBOX = "outbox"
    }
}

/**
 * [items] with all but the newest [max] `person/position` fixes dropped,
 * everything else kept, in order. A fix can arrive every 30 s, so a long
 * stretch without the tunnel would otherwise grow the outbox without end,
 * and a lost fix is corrected by the next one (companion-protocol.md,
 * "Known limits"). Acks and presence are never dropped.
 */
internal fun withoutOldPositions(items: List<Pair<String, String>>, max: Int = 200): List<Pair<String, String>> {
    var excess = items.count { it.first == "person/position" } - max
    return items.filter { it.first != "person/position" || excess-- <= 0 }
}
