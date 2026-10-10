package id.wrsgempa.app

/** Shared earthquake data model used by image sharing and notification utilities. */
data class Quake(
    val date: String = "",
    val time: String = "",
    val magnitude: String = "0",
    val depth: String = "",
    val location: String = "",
    val coordinates: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val tsunami: String = "",
    val felt: String = "",
    val shakemap: String = "",
    val id: String = "",
    val source: String = "WRS GEMPA",
    val warningEnded: Boolean? = null,
    val warningEventId: String? = null,
    val warningUpdatedAt: String? = null
) {
    val magnitudeValue: Double
        get() = magnitude.replace(",", ".").toDoubleOrNull() ?: 0.0
}
