package dev.homeostat.companion

/**
 * One fix, as the protocol's `position` object: `lat` and `lon` required,
 * the rest omitted rather than nulled when the fix does not carry them.
 */
data class Position(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float? = null,
    val batteryPercent: Int? = null,
    val fixedAtEpochS: Long? = null,
) {
    fun toJson(): String {
        val fields = mutableListOf("\"lat\":$lat", "\"lon\":$lon")
        accuracyM?.let { fields += "\"accuracy\":$it" }
        batteryPercent?.let { fields += "\"battery\":$it" }
        fixedAtEpochS?.let { fields += "\"fixed_at\":$it" }
        return fields.joinToString(",", "{", "}")
    }
}
