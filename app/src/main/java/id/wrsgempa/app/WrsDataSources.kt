package id.wrsgempa.app

/**
 * Source attribution shown inside the app and on exported share images.
 * Use official public source pages; do not treat the app as an emergency authority.
 */
internal object WrsDataSources {
    const val BMKG_EARTHQUAKE_DATA = "https://data.bmkg.go.id/gempabumi/"
    const val BMKG_FORECAST_API = "https://api.bmkg.go.id/publik/prakiraan-cuaca"
    const val INATEWS_HOME = "https://inatews.bmkg.go.id/"
    const val INATEWS_REALTIME = "https://inatews.bmkg.go.id/web/realtime"
    const val OPENSTREETMAP = "https://www.openstreetmap.org/copyright"

    const val SAFETY_NOTE =
        "Informasi aplikasi adalah ringkasan pemantauan. Untuk keselamatan, ikuti instruksi resmi BMKG/InaTEWS dan petugas berwenang."
}
